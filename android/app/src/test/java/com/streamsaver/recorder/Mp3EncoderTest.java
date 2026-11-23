package com.streamsaver.recorder;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/** Codec fixtures are decoded independently by tools/verify_mp3.py in CI. */
public class Mp3EncoderTest {
  @Test
  public void writesStereoMonoAndSilenceWithFinalDurationHeaders() throws Exception {
    File directory = new File(System.getProperty("streamsaver.mp3.output",
        "build/mp3-verification"));
    assertTrue(directory.isDirectory() || directory.mkdirs());
    encodeFixture(new File(directory, "stereo-48k-192.mp3"), 48000, 2, 192, 2);
    encodeFixture(new File(directory, "mono-44k-128.mp3"), 44100, 1, 128, 1);
    encodeFixture(new File(directory, "silence-48k-128.mp3"), 48000, 2, 128, 1);
  }

  @Test
  public void supportsNonSeekableOutputWithoutBlankInfoHeader() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (Mp3Encoder encoder = new Mp3Encoder(output, 48000, 2, 192)) {
      byte[] pcm = signal(0, 4800, 48000, 2, false);
      encoder.writePcm16(pcm, 0, pcm.length);
      encoder.finish();
      long length = encoder.getBytesWritten();
      encoder.finish();
      assertEquals(length, encoder.getBytesWritten());
      assertEquals(output.size(), length);
    }
    byte[] bytes = output.toByteArray();
    int firstFrame = id3Size(bytes);
    assertEquals(0xff, bytes[firstFrame] & 0xff);
    assertEquals(0xe0, bytes[firstFrame + 1] & 0xe0);
    String metadata = new String(bytes, 0, firstFrame, StandardCharsets.ISO_8859_1);
    assertTrue(metadata.contains("Marco Vezzale"));
    assertTrue(metadata.contains("marcovezzale@gmail.com"));
    assertFalse(metadata.contains("TPE1")); // The app author is not the artist.
  }

  @Test
  public void rejectsPartialStereoFrames() throws Exception {
    try (Mp3Encoder encoder = new Mp3Encoder(new ByteArrayOutputStream(),
        48000, 2, 128)) {
      try {
        encoder.writePcm16(new byte[6], 0, 6);
        fail("A partial interleaved frame must be rejected");
      } catch (IllegalArgumentException expected) {
        assertTrue(expected.getMessage().contains("complete audio frames"));
      }
    }
  }

  @Test
  public void propagatesStorageFailureInsteadOfContinuingAnIncompleteFile() throws Exception {
    try (Mp3Encoder encoder = new Mp3Encoder(new java.io.OutputStream() {
      @Override public void write(int ignored) throws IOException {
        throw new IOException("Storage full");
      }
    }, 48000, 2, 128)) {
      try {
        byte[] pcm = signal(0, 4800, 48000, 2, false);
        encoder.writePcm16(pcm, 0, pcm.length);
        fail("Storage errors must be reported");
      } catch (IOException expected) {
        assertEquals("Storage full", expected.getMessage());
      }
    }
  }

  private static void encodeFixture(File file, int rate, int channels, int bitrate,
      int seconds) throws Exception {
    boolean silence = file.getName().startsWith("silence");
    long start = System.nanoTime();
    try (FileOutputStream output = new FileOutputStream(file);
        Mp3Encoder encoder = new Mp3Encoder(output, rate, channels, bitrate)) {
      int total = rate * seconds;
      for (int frame = 0; frame < total;) {
        // Odd frame block sizes catch assumptions about 1152-sample MP3 frames.
        int count = Math.min(1379, total - frame);
        byte[] pcm = signal(frame, count, rate, channels, silence);
        encoder.writePcm16(pcm, 0, pcm.length);
        frame += count;
      }
      encoder.finish();
      assertEquals(output.getChannel().size(), encoder.getBytesWritten());
      long length = output.getChannel().size();
      encoder.finish();
      assertEquals(length, output.getChannel().size());
    }
    double elapsed = (System.nanoTime() - start) / 1e9;
    System.out.printf("MP3 fixture %s: %.3fs to encode %ds (%.1fx realtime)%n",
        file.getName(), elapsed, seconds, seconds / elapsed);
    byte[] bytes = Files.readAllBytes(file.toPath());
    int firstFrame = id3Size(bytes);
    assertTrue(bytes.length > bitrate * seconds * 100);
    assertEquals(0xff, bytes[firstFrame] & 0xff);
    String prefix = new String(bytes, firstFrame, Math.min(192, bytes.length - firstFrame),
        StandardCharsets.ISO_8859_1);
    assertTrue("A finalized duration/seek header is required", prefix.contains("Info"));
  }

  private static int id3Size(byte[] bytes) {
    assertTrue(bytes.length > 10);
    assertEquals('I', bytes[0]);
    assertEquals('D', bytes[1]);
    assertEquals('3', bytes[2]);
    return 10 + ((bytes[6] & 0x7f) << 21) + ((bytes[7] & 0x7f) << 14)
        + ((bytes[8] & 0x7f) << 7) + (bytes[9] & 0x7f);
  }

  private static byte[] signal(int first, int count, int rate, int channels,
      boolean silence) {
    byte[] pcm = new byte[count * channels * 2];
    for (int i = 0; i < count; i++) {
      for (int channel = 0; channel < channels; channel++) {
        double frequency = channel == 0 ? 997.0 : 3997.0;
        int amplitude = channel == 0 ? 15000 : 10000;
        short sample = silence ? 0 : (short) Math.round(amplitude
            * Math.sin(2.0 * Math.PI * frequency * (first + i) / rate));
        int offset = (i * channels + channel) * 2;
        pcm[offset] = (byte) sample;
        pcm[offset + 1] = (byte) (sample >> 8);
      }
    }
    return pcm;
  }
}
