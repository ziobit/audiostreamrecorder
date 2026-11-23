package com.streamsaver.recorder;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import de.sciss.jump3r.mp3.BitStream;
import de.sciss.jump3r.mp3.GainAnalysis;
import de.sciss.jump3r.mp3.ID3Tag;
import de.sciss.jump3r.mp3.Lame;
import de.sciss.jump3r.mp3.LameGlobalFlags;
import de.sciss.jump3r.mp3.MPEGMode;
import de.sciss.jump3r.mp3.Presets;
import de.sciss.jump3r.mp3.Quantize;
import de.sciss.jump3r.mp3.QuantizePVT;
import de.sciss.jump3r.mp3.Reservoir;
import de.sciss.jump3r.mp3.Takehiro;
import de.sciss.jump3r.mp3.VBRTag;
import de.sciss.jump3r.mp3.VbrMode;
import de.sciss.jump3r.mp3.Version;
import de.sciss.jump3r.mpg.Common;
import de.sciss.jump3r.mpg.Interface;
import de.sciss.jump3r.mpg.MPGLib;

/**
 * Streaming signed PCM16 little-endian to MP3, with bounded working buffers.
 * Author: Marco Vezzale <marcovezzale@gmail.com>.
 *
 * Uses the unmodified LGPL Jump3r/LAME encoding core; the desktop Java Sound
 * LameEncoder wrapper is deliberately not referenced. See assets/third_party.
 * Each instance owns its encoder state. It never closes the caller's stream.
 */
public final class Mp3Encoder implements Closeable {
  private static final int FRAMES_PER_CHUNK = 4096;
  private static final int MP3_BUFFER_SIZE = 16384;
  private final OutputStream output;
  private final int channels;
  private final Lame lame = new Lame();
  private final VBRTag vbr = new VBRTag();
  private final int[] left = new int[FRAMES_PER_CHUNK];
  private final int[] right = new int[FRAMES_PER_CHUNK];
  private final byte[] encoded = new byte[MP3_BUFFER_SIZE];
  private LameGlobalFlags flags;
  private FileChannel seekableOutput;
  private long infoFrameOffset;
  private long bytesWritten;
  private boolean finished;
  private boolean failed;
  private boolean closed;

  /**
   * @param output owned by the caller; a direct FileOutputStream enables a
   *               finalized Info/LAME seek/duration header
   * @param sampleRate PCM sample rate, e.g. 44100 or 48000
   * @param channels 1 for mono, 2 for interleaved stereo
   * @param bitrateKbps constant MP3 bitrate in kilobits per second
   */
  public Mp3Encoder(OutputStream output, int sampleRate, int channels,
      int bitrateKbps) throws IOException {
    if (output == null) throw new NullPointerException("output");
    if (channels < 1 || channels > 2) {
      throw new IllegalArgumentException("MP3 requires mono or stereo PCM");
    }
    if (sampleRate < 8000 || sampleRate > 48000) {
      throw new IllegalArgumentException("Unsupported PCM sample rate: " + sampleRate);
    }
    if (bitrateKbps < 8 || bitrateKbps > 320) {
      throw new IllegalArgumentException("Invalid MP3 bitrate: " + bitrateKbps);
    }
    this.output = output;
    this.channels = channels;

    GainAnalysis gain = new GainAnalysis();
    BitStream bitstream = new BitStream();
    Presets presets = new Presets();
    QuantizePVT quantizePrivate = new QuantizePVT();
    Quantize quantize = new Quantize();
    Version version = new Version();
    ID3Tag tags = new ID3Tag();
    Reservoir reservoir = new Reservoir();
    Takehiro takehiro = new Takehiro();
    MPGLib mpg = new MPGLib();
    Interface decoderInterface = new Interface();
    Common common = new Common();

    lame.setModules(gain, bitstream, presets, quantizePrivate, quantize,
        vbr, version, tags, mpg);
    bitstream.setModules(gain, mpg, version, vbr);
    tags.setModules(bitstream, version);
    presets.setModules(lame);
    quantize.setModules(bitstream, reservoir, quantizePrivate, takehiro);
    quantizePrivate.setModules(takehiro, reservoir, lame.enc.psy);
    reservoir.setModules(bitstream);
    takehiro.setModules(quantizePrivate);
    vbr.setModules(lame, bitstream, version);
    mpg.setModules(decoderInterface, common);
    decoderInterface.setModules(vbr, common);

    flags = lame.lame_init();
    flags.num_channels = channels;
    flags.in_samplerate = sampleRate;
    flags.out_samplerate = sampleRate;
    flags.mode = channels == 1 ? MPEGMode.MONO : MPEGMode.JOINT_STEREO;
    flags.VBR = VbrMode.vbr_off;
    flags.brate = bitrateKbps;
    flags.quality = 5;
    flags.findReplayGain = false;
    flags.decode_on_the_fly = false;

    // Pipe/stream outputs remain valid CBR MP3 without an unfinalized Xing frame.
    // MediaStore file descriptors normally support FileChannel seeking.
    if (output instanceof FileOutputStream) {
      FileChannel candidate = ((FileOutputStream) output).getChannel();
      try {
        infoFrameOffset = candidate.position();
        candidate.position(infoFrameOffset);
        seekableOutput = candidate;
      } catch (IOException unsupportedSeeking) {
        seekableOutput = null;
      }
    }
    flags.bWriteVbrTag = seekableOutput != null;

    tags.id3tag_init(flags);
    tags.id3tag_v2_only(flags);
    tags.id3tag_set_fieldvalue(flags, "TSSE=Stream Saver / Jump3r 1.0.5 (LAME)");
    tags.id3tag_set_fieldvalue(flags,
        "TENC=Stream Saver by Marco Vezzale <marcovezzale@gmail.com>");
    flags.write_id3tag_automatic = true;
    infoFrameOffset += tags.lame_get_id3v2_tag(flags, null, 0);
    int result = lame.lame_init_params(flags);
    if (result < 0) {
      lame.lame_close(flags);
      flags = null;
      throw new IOException("Cannot initialize MP3 encoder (" + result + ")");
    }
  }

  /** Writes whole PCM frames (2 bytes per mono frame, 4 bytes per stereo frame). */
  public synchronized void writePcm16(byte[] pcm, int offset, int length)
      throws IOException {
    ensureWritable();
    if (pcm == null) throw new NullPointerException("pcm");
    if (offset < 0 || length < 0 || offset > pcm.length - length) {
      throw new IndexOutOfBoundsException("Invalid PCM range");
    }
    int frameBytes = channels * 2;
    if (length % frameBytes != 0) {
      throw new IllegalArgumentException("PCM data must contain complete audio frames");
    }
    int end = offset + length;
    while (offset < end) {
      int frames = Math.min(FRAMES_PER_CHUNK, (end - offset) / frameBytes);
      for (int i = 0; i < frames; i++) {
        // Jump3r's int-input API expects signed 32-bit full-scale samples.
        // Shifting PCM16 by 16 is essential; unshifted input becomes near-silent.
        int sample = (short) ((pcm[offset] & 0xff) | (pcm[offset + 1] << 8));
        left[i] = sample << 16;
        offset += 2;
        if (channels == 2) {
          sample = (short) ((pcm[offset] & 0xff) | (pcm[offset + 1] << 8));
          right[i] = sample << 16;
          offset += 2;
        }
      }
      int count = lame.lame_encode_buffer_int(flags, left, right, frames,
          encoded, 0, encoded.length);
      writeEncoded(count);
    }
  }

  /** Flushes the final MP3 frames once and finalizes seeking/duration metadata. */
  public synchronized void finish() throws IOException {
    if (finished) return;
    ensureWritable();
    try {
      writeEncoded(lame.lame_encode_flush(flags, encoded, 0, encoded.length));
      output.flush();
      if (seekableOutput != null && flags.bWriteVbrTag) {
        int count = vbr.getLameTagFrame(flags, encoded);
        if (count <= 0 || count > encoded.length) {
          throw new IOException("Cannot finalize MP3 duration header");
        }
        long end = seekableOutput.position();
        try {
          seekableOutput.position(infoFrameOffset);
          ByteBuffer header = ByteBuffer.wrap(encoded, 0, count);
          while (header.hasRemaining()) seekableOutput.write(header);
        } finally {
          seekableOutput.position(end);
        }
      }
      finished = true;
    } catch (IOException | RuntimeException error) {
      failed = true;
      throw error;
    }
  }

  public synchronized long getBytesWritten() {
    return bytesWritten;
  }

  /** Finishes and releases the encoder; does not close the caller's stream. */
  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    try {
      if (!finished && !failed) finish();
    } finally {
      if (flags != null) lame.lame_close(flags);
      flags = null;
      closed = true;
    }
  }

  private void writeEncoded(int count) throws IOException {
    if (count < 0 || count > encoded.length) {
      failed = true;
      throw new IOException("MP3 encoder failed (" + count + ")");
    }
    try {
      if (count > 0) {
        output.write(encoded, 0, count);
        bytesWritten += count;
      }
    } catch (IOException error) {
      failed = true;
      throw error;
    }
  }

  private void ensureWritable() throws IOException {
    if (closed || finished || failed) throw new IOException("MP3 encoder is closed");
  }
}
