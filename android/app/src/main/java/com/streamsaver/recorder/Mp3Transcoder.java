package com.streamsaver.recorder;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes an existing local recording to PCM and writes a genuine MP3 stream.
 * Author: Marco Vezzale <marcovezzale@gmail.com>.
 */
final class Mp3Transcoder {
  interface Listener {
    boolean isCancelled();
    void onProgress(int percent);
  }

  static final class Result {
    final long bytes;
    final long durationMs;

    Result(long bytes, long durationMs) {
      this.bytes = bytes;
      this.durationMs = durationMs;
    }
  }

  private Mp3Transcoder() {}

  static Result transcode(Context context, Uri source, OutputStream output,
      int bitrateKbps, Listener listener) throws IOException {
    MediaExtractor extractor = new MediaExtractor();
    MediaCodec decoder = null;
    Mp3Encoder encoder = null;
    boolean decoderStarted = false;
    try {
      checkCancelled(listener);
      extractor.setDataSource(context, source, null);
      MediaFormat inputFormat = null;
      String mime = null;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat candidate = extractor.getTrackFormat(i);
        String candidateMime = candidate.getString(MediaFormat.KEY_MIME);
        if (candidateMime != null && candidateMime.startsWith("audio/")) {
          inputFormat = candidate;
          mime = candidateMime;
          extractor.selectTrack(i);
          break;
        }
      }
      if (inputFormat == null) throw new IOException("This file has no readable audio track.");
      long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
        ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0;
      // Request signed PCM16; the actual output format is still checked below.
      inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
      decoder = MediaCodec.createDecoderByType(mime);
      decoder.configure(inputFormat, null, null, 0);
      decoder.start();
      decoderStarted = true;

      boolean inputEnded = false;
      boolean outputEnded = false;
      MediaFormat outputFormat = null;
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      int sampleRate = 0;
      int channels = 0;
      int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
      long frames = 0;
      long lastInputTimeUs = 0;
      long lastActivity = SystemClock.elapsedRealtime();
      int lastProgress = -1;
      listener.onProgress(durationUs > 0 ? 0 : -1);

      while (!outputEnded) {
        checkCancelled(listener);
        boolean worked = false;
        if (!inputEnded) {
          int inputIndex = decoder.dequeueInputBuffer(10000);
          if (inputIndex >= 0) {
            ByteBuffer input = decoder.getInputBuffer(inputIndex);
            if (input == null) throw new IOException("The audio decoder returned no input buffer.");
            input.clear();
            int size = extractor.readSampleData(input, 0);
            if (size < 0) {
              decoder.queueInputBuffer(inputIndex, 0, 0, lastInputTimeUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputEnded = true;
            } else {
              if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0)
                throw new IOException("Encrypted audio cannot be converted.");
              lastInputTimeUs = Math.max(0, extractor.getSampleTime());
              decoder.queueInputBuffer(inputIndex, 0, size, lastInputTimeUs, 0);
              extractor.advance();
            }
            worked = true;
          }
        }

        int outputIndex = decoder.dequeueOutputBuffer(info, 10000);
        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          outputFormat = decoder.getOutputFormat();
          if (encoder != null && (outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) != sampleRate
              || outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != channels))
            throw new IOException("The audio format changed during conversion.");
          pcmEncoding = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
            ? outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
          worked = true;
        } else if (outputIndex >= 0) {
          try {
            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
              if (outputFormat == null) outputFormat = decoder.getOutputFormat();
              if (encoder == null) {
                sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                pcmEncoding = outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                  ? outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                if (channels < 1 || channels > 2)
                  throw new IOException("MP3 export supports mono and stereo recordings.");
                encoder = new Mp3Encoder(output, sampleRate, channels, bitrateKbps);
              }
              ByteBuffer raw = decoder.getOutputBuffer(outputIndex);
              if (raw == null) throw new IOException("The audio decoder returned no samples.");
              raw.clear();
              raw.position(info.offset);
              raw.limit(info.offset + info.size);
              byte[] pcm = toPcm16(raw.slice().order(ByteOrder.nativeOrder()), pcmEncoding, channels);
              checkCancelled(listener);
              encoder.writePcm16(pcm, 0, pcm.length);
              frames += pcm.length / (channels * 2);
              if (durationUs > 0) {
                int value = (int) Math.min(99, Math.max(0,
                  100.0 * Math.max(0, info.presentationTimeUs) / durationUs));
                if (value > lastProgress) {
                  lastProgress = value;
                  listener.onProgress(value);
                }
              }
            }
            outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
          } finally {
            decoder.releaseOutputBuffer(outputIndex, false);
          }
          worked = true;
        }
        if (worked) lastActivity = SystemClock.elapsedRealtime();
        else if (SystemClock.elapsedRealtime() - lastActivity > 30000)
          throw new IOException("The audio decoder stopped responding.");
      }
      checkCancelled(listener);
      if (encoder == null || frames == 0) throw new IOException("This recording contains no audio samples.");
      encoder.finish();
      return new Result(encoder.getBytesWritten(), frames * 1000 / sampleRate);
    } catch (InterruptedIOException e) {
      throw e;
    } catch (IOException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new IOException("Could not decode this recording: " + message(e), e);
    } finally {
      if (encoder != null) {
        try { encoder.close(); } catch (Exception ignored) {}
      }
      if (decoder != null) {
        if (decoderStarted) {
          try { decoder.stop(); } catch (RuntimeException ignored) {}
        }
        try { decoder.release(); } catch (RuntimeException ignored) {}
      }
      extractor.release();
    }
  }

  private static byte[] toPcm16(ByteBuffer source, int encoding, int channels) throws IOException {
    int bytesPerSample;
    if (encoding == AudioFormat.ENCODING_PCM_16BIT) bytesPerSample = 2;
    else if (encoding == AudioFormat.ENCODING_PCM_FLOAT) bytesPerSample = 4;
    else throw new IOException("The decoder returned an unsupported PCM sample format.");
    int size = source.remaining();
    if (size % (channels * bytesPerSample) != 0)
      throw new IOException("The decoder returned an incomplete audio frame.");
    if (encoding == AudioFormat.ENCODING_PCM_16BIT && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
      byte[] result = new byte[size];
      source.get(result);
      return result;
    }
    int samples = size / bytesPerSample;
    byte[] result = new byte[samples * 2];
    for (int i = 0; i < samples; i++) {
      int sample;
      if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
        float value = source.getFloat();
        if (!Float.isFinite(value)) value = 0;
        value = Math.max(-1f, Math.min(1f, value));
        sample = value < 0 ? Math.round(value * 32768f) : Math.round(value * 32767f);
      } else sample = source.getShort();
      result[i * 2] = (byte) sample;
      result[i * 2 + 1] = (byte) (sample >> 8);
    }
    return result;
  }

  private static void checkCancelled(Listener listener) throws InterruptedIOException {
    if (listener.isCancelled() || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("MP3 export cancelled.");
  }

  private static String message(Throwable e) {
    String value = e.getMessage();
    return value == null || value.trim().isEmpty() ? e.getClass().getSimpleName() : value;
  }
}
