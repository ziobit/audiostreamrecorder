package com.streamsaver.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Creates an MP3 copy without changing or deleting the original recording.
 * Author: Marco Vezzale <marcovezzale@gmail.com>.
 */
public class Mp3ExportService extends Service {
  public static final String ACTION_EXPORT = "com.streamsaver.recorder.EXPORT_MP3";
  public static final String ACTION_CANCEL = "com.streamsaver.recorder.CANCEL_MP3_EXPORT";
  private static final String CHANNEL = "streamsaver_mp3_export";
  private static final int NOTIFICATION_ID = 12;
  private static final int RESULT_NOTIFICATION_ID = 13;

  public static volatile boolean active;
  public static volatile int progress = -1;
  public static volatile String error = "";
  public static volatile String savedName = "";
  public static volatile long version;

  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile boolean cancelled;
  private volatile String timeoutMessage = "";
  private volatile String sourceName = "recording";
  private Thread worker;
  private PowerManager.WakeLock wakeLock;
  private int latestStartId;
  private long lastNotificationAt;
  private boolean foregroundStarted;

  @Override public void onCreate() {
    super.onCreate();
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel(CHANNEL,
      "MP3 export", NotificationManager.IMPORTANCE_LOW));
  }

  @Override public IBinder onBind(Intent intent) { return null; }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    latestStartId = startId;
    if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
      cancelled = true;
      if (worker != null) worker.interrupt();
      if (worker == null) stopSelfResult(startId);
      return START_NOT_STICKY;
    }
    if (active) {
      // A stopped instance can still be cleaning up its codec on the worker.
      // A new startForegroundService request must nevertheless be acknowledged
      // promptly; do not start a second encoder until that cleanup finishes.
      if (!foregroundStarted) {
        try { beginForeground(); } catch (RuntimeException ignored) {}
        endForeground();
        stopSelfResult(startId);
      }
      return START_NOT_STICKY;
    }
    if (intent == null || !ACTION_EXPORT.equals(intent.getAction())) {
      stopSelfResult(startId);
      return START_NOT_STICKY;
    }
    Uri source = intent.getData();
    sourceName = intent.getStringExtra("name");
    if (sourceName == null || sourceName.trim().isEmpty()) sourceName = "recording.m4a";
    int requestedBitrate = intent.getIntExtra("bitrate", 192);
    if (requestedBitrate >= 1000) requestedBitrate /= 1000;
    final int bitrate = requestedBitrate == 128 || requestedBitrate == 256 || requestedBitrate == 320
      ? requestedBitrate : 192;
    active = true;
    progress = -1;
    error = "";
    savedName = "";
    cancelled = false;
    timeoutMessage = "";
    try {
      beginForeground();
      if (source == null || !"content".equals(source.getScheme()))
        throw new IOException("Choose a saved recording to export.");
      PowerManager power = getSystemService(PowerManager.class);
      wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamSaver:Mp3Export");
      wakeLock.acquire(6L * 60 * 60 * 1000);
      worker = new Thread(() -> export(source, bitrate), "StreamSaver-MP3-export");
      worker.start();
    } catch (Exception e) {
      complete(false, message(e));
    }
    return START_NOT_STICKY;
  }

  private void export(Uri source, int bitrate) {
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
    Uri destination = null;
    boolean published = false;
    String failure = "";
    String exportedName = "";
    try {
      if (cancelled) throw new InterruptedIOException("MP3 export cancelled.");
      exportedName = uniqueName(sourceName);
      ContentValues values = new ContentValues();
      values.put(MediaStore.Audio.Media.DISPLAY_NAME, exportedName);
      values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg");
      values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/StreamSaver/");
      values.put(MediaStore.Audio.Media.IS_PENDING, 1);
      // If Android kills the process, its media cleanup can discard the incomplete row.
      values.put(MediaStore.Audio.Media.DATE_EXPIRES, System.currentTimeMillis() / 1000 + 86400);
      destination = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
      if (destination == null) throw new IOException("Could not create the MP3 output file.");
      Mp3Transcoder.Result result;
      ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(destination, "rw");
      if (descriptor == null) throw new IOException("Could not open the MP3 output file.");
      // Keep this unwrapped: Mp3Encoder uses its FileChannel to finalize the Info/LAME seek header.
      try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
        result = Mp3Transcoder.transcode(this, source, output, bitrate, new Mp3Transcoder.Listener() {
          @Override public boolean isCancelled() { return cancelled; }
          @Override public void onProgress(int percent) {
            progress = percent;
            long now = SystemClock.elapsedRealtime();
            if (now - lastNotificationAt >= 750) {
              lastNotificationAt = now;
              main.post(() -> { if (active && !cancelled) updateProgressNotification(); });
            }
          }
        });
      }
      if (cancelled) throw new InterruptedIOException("MP3 export cancelled.");
      if (result.bytes <= 0) throw new IOException("The MP3 encoder produced no audio.");
      values.clear();
      values.put(MediaStore.Audio.Media.IS_PENDING, 0);
      values.putNull(MediaStore.Audio.Media.DATE_EXPIRES);
      values.put(MediaStore.Audio.Media.DURATION, result.durationMs);
      if (getContentResolver().update(destination, values, null, null) != 1)
        throw new IOException("Could not finish saving the MP3 copy.");
      published = true;
      try (Cursor c = getContentResolver().query(destination,
          new String[]{MediaStore.Audio.Media.DISPLAY_NAME}, null, null, null)) {
        if (c != null && c.moveToFirst()) exportedName = c.getString(0);
      } catch (RuntimeException ignored) {}
    } catch (InterruptedIOException e) {
      failure = timeoutMessage.isEmpty() ? "MP3 export cancelled." : timeoutMessage;
    } catch (Exception e) {
      failure = cancelled
        ? (timeoutMessage.isEmpty() ? "MP3 export cancelled." : timeoutMessage)
        : "MP3 export failed: " + message(e);
    } finally {
      if (!published && destination != null) {
        try { getContentResolver().delete(destination, null, null); } catch (RuntimeException ignored) {}
      }
      final boolean success = published;
      final String resultMessage = success ? exportedName : failure;
      main.post(() -> complete(success, resultMessage));
    }
  }

  private String uniqueName(String source) {
    String base = source.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    int dot = base.lastIndexOf('.');
    if (dot > 0) base = base.substring(0, dot);
    base = base.replaceAll("[. ]+$", "");
    if (base.isEmpty()) base = "Stream Saver";
    if (base.length() > 100) base = base.substring(0, 100).trim();
    Set<String> names = new HashSet<>();
    try (Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        new String[]{MediaStore.Audio.Media.DISPLAY_NAME},
        MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
        new String[]{"Music/StreamSaver/%"}, null)) {
      while (c != null && c.moveToNext()) {
        String name = c.getString(0);
        if (name != null) names.add(name.toLowerCase(Locale.ROOT));
      }
    } catch (RuntimeException ignored) {
      // A fresh MediaStore insert still creates a separate file; it never truncates the source.
      return base + " " + System.currentTimeMillis() + ".mp3";
    }
    String name = base + ".mp3";
    for (int suffix = 2; names.contains(name.toLowerCase(Locale.ROOT)); suffix++)
      name = base + " (" + suffix + ").mp3";
    return name;
  }

  private Notification progressNotification() {
    Intent cancel = new Intent(this, Mp3ExportService.class).setAction(ACTION_CANCEL);
    PendingIntent cancelIntent = PendingIntent.getService(this, 12, cancel,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_wave)
      .setContentTitle("Saving MP3 copy")
      .setContentText((progress < 0 ? "Preparing" : progress + "%") + " · " + sourceName)
      .setContentIntent(openApp())
      .setCategory(Notification.CATEGORY_PROGRESS)
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setProgress(100, Math.max(0, progress), progress < 0)
      .addAction(0, "Cancel", cancelIntent)
      .build();
  }

  private PendingIntent openApp() {
    Intent open = new Intent(this, MainActivity.class)
      .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return PendingIntent.getActivity(this, 12, open,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private void updateProgressNotification() {
    try { getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, progressNotification()); }
    catch (RuntimeException ignored) {}
  }

  private void beginForeground() {
    int type = Build.VERSION.SDK_INT >= 35
      ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
      : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
    startForeground(NOTIFICATION_ID, progressNotification(), type);
    foregroundStarted = true;
  }

  private void endForeground() {
    stopForeground(STOP_FOREGROUND_REMOVE);
    foregroundStarted = false;
  }

  private void complete(boolean success, String result) {
    if (success) { savedName = result; progress = 100; }
    else { error = result.isEmpty() ? "MP3 export did not finish." : result; }
    worker = null;
    releaseWakeLock();
    endForeground();
    try {
      getSystemService(NotificationManager.class).notify(RESULT_NOTIFICATION_ID,
        new Notification.Builder(this, CHANNEL)
          .setSmallIcon(R.drawable.ic_wave)
          .setContentTitle(success ? "MP3 copy saved" : "MP3 export stopped")
          .setContentText(success ? savedName : error)
          .setContentIntent(openApp())
          .setAutoCancel(true)
          .build());
    } catch (RuntimeException ignored) {}
    active = false;
    version++;
    stopSelfResult(latestStartId);
  }

  @Override public void onTimeout(int startId, int foregroundServiceType) {
    timeoutMessage = "Android stopped the export after its background time limit. The original is unchanged.";
    cancelled = true;
    if (worker != null) worker.interrupt();
    releaseWakeLock();
    endForeground();
    stopSelf();
  }

  @Override public void onDestroy() {
    cancelled = true;
    if (worker != null) worker.interrupt();
    releaseWakeLock();
    super.onDestroy();
  }

  private void releaseWakeLock() {
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    wakeLock = null;
  }

  private static String message(Throwable e) {
    String value = e.getMessage();
    return value == null || value.trim().isEmpty() ? e.getClass().getSimpleName() : value;
  }
}
