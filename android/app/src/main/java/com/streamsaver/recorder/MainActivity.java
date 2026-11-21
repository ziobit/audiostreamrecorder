package com.streamsaver.recorder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
  private static final int REQ_MIC = 101, REQ_CAPTURE = 102, REQ_RENAME = 104, REQ_MEDIA = 105;
  private static final int BG = Color.rgb(10,15,31);
  private static final int PANEL = Color.rgb(23,33,55);
  private static final int FORE = Color.rgb(235,244,255);
  private static final int MUTED = Color.rgb(151,172,193);
  private static final int TEAL = Color.rgb(79,222,206);
  private static final int RED = Color.rgb(255,90,115);
  private LinearLayout library;
  private TextView status, timer, size, hint;
  private ProgressBar meter;
  private EditText filename;
  private Spinner quality;
  private CheckBox waitAudio, autoStop;
  private Button start, stop;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean sawRecording = false;
  private String shownError = "";
  private Uri pendingRenameUri;
  private String pendingRenameFilename;
  private boolean mediaPermissionAsked = false;

  @Override public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    buildInterface();
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 103);
  }
  @Override public void onResume() {
    super.onResume();
    listRecordings();
    if (!mediaPermissionAsked) {
      String permission = Build.VERSION.SDK_INT >= 33
        ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
      if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        mediaPermissionAsked = true;
        requestPermissions(new String[]{permission}, REQ_MEDIA);
      }
    }
    handler.post(poll);
  }
  @Override public void onPause() { handler.removeCallbacks(poll); super.onPause(); }

  private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
  private GradientDrawable background(int c, int r) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(c); d.setCornerRadius(dp(r)); return d;
  }
  private TextView text(String s,int sp,int color,boolean bold) {
    TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
    if (bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    return t;
  }
  private LinearLayout column() { LinearLayout v=new LinearLayout(this); v.setOrientation(1); return v; }
  private void append(LinearLayout p, View c, int top) {
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
    lp.topMargin=dp(top); p.addView(c,lp);
  }
  private LinearLayout card() {
    LinearLayout v=column(); v.setPadding(dp(18),dp(18),dp(18),dp(18));
    v.setBackground(background(PANEL,18)); return v;
  }
  private Button button(String title,int color,int foreground) {
    Button b=new Button(this); b.setAllCaps(false); b.setText(title);
    b.setTextSize(15); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    b.setBackground(background(color,13)); b.setTextColor(foreground);
    b.setMinHeight(dp(52)); return b;
  }
  private void buildInterface() {
    ScrollView sc=new ScrollView(this); sc.setFillViewport(true); sc.setBackgroundColor(BG);
    LinearLayout body=column(); body.setPadding(dp(19),dp(24),dp(19),dp(28)); sc.addView(body);
    setContentView(sc);
    append(body,text("◉  STREAM SAVER",21,TEAL,true),0);
    append(body,text("Save permitted playback from your phone.",13,MUTED,false),5);
    LinearLayout panel=card(); append(body,panel,20);
    status=text("● READY TO RECORD",12,TEAL,true); append(panel,status,0);
    timer=text("00:00:00",46,FORE,true); timer.setGravity(Gravity.CENTER); append(panel,timer,12);
    size=text("0 KB",13,MUTED,false); size.setGravity(Gravity.CENTER); append(panel,size,2);
    meter=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
    meter.setMax(100); meter.setProgressTintList(android.content.res.ColorStateList.valueOf(TEAL));
    meter.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(35,52,76)));
    append(panel,meter,15);
    hint=text("Start recording, authorize Android, then switch to Chrome.",12,MUTED,false);
    append(panel,hint,12);
    append(panel,text("RECORDING NAME",11,MUTED,true),17);
    filename=new EditText(this); filename.setSingleLine(true);
    filename.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    filename.setText("Stream Saver "+new SimpleDateFormat("yyyy-MM-dd HH-mm",Locale.US).format(new Date()));
    filename.setTextColor(FORE); filename.setTextSize(15); filename.setPadding(dp(12),dp(9),dp(12),dp(9));
    filename.setBackground(background(Color.rgb(33,47,71),11)); append(panel,filename,7);
    append(panel,text("RECORDING QUALITY",11,MUTED,true),18);
    quality=new Spinner(this);
    ArrayAdapter<String> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
      new String[]{"128 kbps · AAC","192 kbps · AAC","256 kbps · AAC"});
    quality.setAdapter(ad); quality.setSelection(1); quality.setBackground(background(Color.rgb(33,47,71),11));
    append(panel,quality,7);
    waitAudio=new CheckBox(this); waitAudio.setText("Wait until audio is audible");
    waitAudio.setTextColor(FORE); waitAudio.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));
    append(panel,waitAudio,15);
    autoStop=new CheckBox(this); autoStop.setText("Stop after 8 seconds of silence");
    autoStop.setTextColor(FORE); autoStop.setButtonTintList(android.content.res.ColorStateList.valueOf(TEAL));
    append(panel,autoStop,3);
    append(panel,text("Silence detection is approximate. Long pauses may stop recording.",11,MUTED,false),3);
    start=button("●   START RECORDING",TEAL,BG); append(panel,start,20); start.setOnClickListener(v->begin());
    stop=button("■   STOP & SAVE",RED,Color.WHITE); append(panel,stop,10);
    stop.setOnClickListener(v->sendStop());
    Button chrome=button("OPEN CHROME",Color.rgb(43,59,82),FORE); append(panel,chrome,9);
    chrome.setOnClickListener(v->{
      Intent i=getPackageManager().getLaunchIntentForPackage("com.android.chrome");
      if(i!=null) startActivity(i); else toast("Chrome not found on this device.");
    });
    append(panel,text("48 kHz stereo AAC / M4A  ·  Music/StreamSaver",11,MUTED,false),13);
    append(body,text("YOUR RECORDINGS",16,FORE,true),25);
    library=column(); append(body,library,10);
    append(body,text("No accounts or uploads. Only audio that its source application permits can be captured.",12,MUTED,false),18);
  }
  private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
  private void begin() {
    if(CaptureService.active.get())return;
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
      requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);return;
    }
    MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
    startActivityForResult(m.createScreenCaptureIntent(),REQ_CAPTURE);
  }
  @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] results) {
    super.onRequestPermissionsResult(req,permissions,results);
    if(req==REQ_MIC) {
      if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)begin();
      else toast("Recording permission is required.");
    }
    if(req==REQ_MEDIA)listRecordings();
  }
  @Override protected void onActivityResult(int code,int result,Intent data) {
    super.onActivityResult(code,result,data);
    if(code==REQ_RENAME){
      if(result==RESULT_OK && pendingRenameUri!=null && pendingRenameFilename!=null)
        renameFile(pendingRenameUri,pendingRenameFilename,false);
      else if(result!=RESULT_OK)toast("Rename permission not granted.");
      pendingRenameUri=null;
      pendingRenameFilename=null;
      return;
    }
    if(code!=REQ_CAPTURE)return;
    if(result!=RESULT_OK||data==null){toast("Android capture permission was not granted.");return;}
    Intent service=new Intent(this,CaptureService.class);
    service.setAction(CaptureService.ACTION_START);
    service.putExtra("result",result); service.putExtra("token",data);
    service.putExtra("name",filename.getText().toString().trim());
    service.putExtra("bitrate",new int[]{128000,192000,256000}[quality.getSelectedItemPosition()]);
    service.putExtra("wait",waitAudio.isChecked()); service.putExtra("auto",autoStop.isChecked());
    startForegroundService(service);
    toast("Capture requested. Return to the audio player.");
  }
  private void sendStop() {
    Intent s=new Intent(this,CaptureService.class).setAction(CaptureService.ACTION_STOP);
    startService(s);
  }
  private final Runnable poll=new Runnable() {
    @Override public void run() {
      boolean running=CaptureService.active.get();
      status.setText(running?"● RECORDING IN PROGRESS":"● READY TO RECORD");
      status.setTextColor(running?RED:TEAL);
      start.setVisibility(running?View.GONE:View.VISIBLE);
      stop.setVisibility(running?View.VISIBLE:View.GONE);
      filename.setEnabled(!running); quality.setEnabled(!running);
      waitAudio.setEnabled(!running); autoStop.setEnabled(!running);
      long seconds=running?Math.max(0,SystemClock.elapsedRealtime()-CaptureService.startedAt)/1000:0;
      timer.setText(String.format(Locale.US,"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60));
      size.setText(running?pretty(CaptureService.bytes):"0 KB");
      meter.setProgress((int)(Math.min(1,CaptureService.level)*100));
      hint.setText(running?(CaptureService.waiting?"Waiting for audible playback…":"Recording allowed device audio. Leave this screen to use Chrome."):
        "Start recording, authorize Android, then switch to Chrome.");
      if(!CaptureService.error.isEmpty()&&!CaptureService.error.equals(shownError)){
        shownError=CaptureService.error;toast(shownError);
      }
      if(!running&&sawRecording)listRecordings();
      sawRecording=running;
      handler.postDelayed(this,running?250:1000);
    }
  };
  private static String pretty(long n) {
    if(n<1024)return n+" B";
    if(n<1024*1024)return String.format(Locale.US,"%.1f KB",n/1024.0);
    return String.format(Locale.US,"%.1f MB",n/1048576.0);
  }
  private void showRename(Uri uri,String oldFilename) {
    String defaultName=oldFilename;
    if(defaultName.toLowerCase(Locale.ROOT).endsWith(".m4a"))
      defaultName=defaultName.substring(0,defaultName.length()-4);
    EditText field=new EditText(this);
    field.setSingleLine(true);
    field.setText(defaultName);
    field.setSelectAllOnFocus(true);
    field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    LinearLayout holder=column();
    holder.setPadding(dp(24),dp(5),dp(24),0);
    holder.addView(field,new LinearLayout.LayoutParams(-1,-2));
    AlertDialog d=new AlertDialog.Builder(this)
      .setTitle("Rename recording")
      .setMessage("Change the filename without re-encoding the audio. The .m4a extension is preserved.")
      .setView(holder)
      .setPositiveButton("Rename",null)
      .setNegativeButton("Cancel",null).create();
    d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
      String text=field.getText().toString().trim();
      if(text.toLowerCase(Locale.ROOT).endsWith(".m4a"))
        text=text.substring(0,text.length()-4).trim();
      if(text.isEmpty()||text.length()>110){
        field.setError("Enter a name between 1 and 110 characters.");return;
      }
      if(text.matches(".*[\\\\/:*?\\\"<>|\\p{Cntrl}].*")||text.endsWith(".")){
        field.setError("The filename contains invalid characters.");return;
      }
      String newName=text+".m4a";
      if(newName.equals(oldFilename)){d.dismiss();return;}
      if(renameFile(uri,newName,true))d.dismiss();
    }));
    d.show();
    field.requestFocus();
  }

  private boolean filenameExists(Uri uri,String newName) {
    Uri audio=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    try(Cursor c=getContentResolver().query(audio,
        new String[]{MediaStore.Audio.Media._ID},
        MediaStore.Audio.Media.RELATIVE_PATH+" LIKE ? AND "+
          MediaStore.Audio.Media.DISPLAY_NAME+"=?",
        new String[]{"Music/StreamSaver/%",newName},null)){
      while(c!=null&&c.moveToNext()){
        Uri candidate=ContentUris.withAppendedId(audio,c.getLong(0));
        if(!candidate.equals(uri))return true;
      }
    }catch(Exception e){toast("Cannot check filenames: "+e.getMessage());return true;}
    return false;
  }

  private boolean renameFile(Uri uri,String newName,boolean validateDuplicate) {
    if(validateDuplicate&&filenameExists(uri,newName)){
      toast("A recording with that filename already exists.");return false;
    }
    try{
      ContentValues data=new ContentValues();
      data.put(MediaStore.Audio.Media.DISPLAY_NAME,newName);
      int affected=getContentResolver().update(uri,data,null,null);
      if(affected!=1){toast("File not found, or rename was refused.");return false;}
      toast("Renamed to "+newName);
      listRecordings();
      return true;
    }catch(SecurityException denied){
      // Android can require an explicit user grant for recordings owned by another app install.
      pendingRenameUri=uri;
      pendingRenameFilename=newName;
      try{
        IntentSender sender;
        if(Build.VERSION.SDK_INT>=30){
          sender=MediaStore.createWriteRequest(getContentResolver(),
            java.util.Collections.singletonList(uri)).getIntentSender();
        }else if(denied instanceof android.app.RecoverableSecurityException){
          sender=((android.app.RecoverableSecurityException)denied).getUserAction()
            .getActionIntent().getIntentSender();
        }else{
          toast("Android denied permission to rename this file.");return false;
        }
        startIntentSenderForResult(sender,REQ_RENAME,null,0,0,0);
      }catch(Exception e){toast("Cannot request rename access: "+e.getMessage());}
      return false;
    }catch(Exception e){toast("Rename failed: "+e.getMessage());return false;}
  }

  private void listRecordings() {
    if(library==null)return;
    library.removeAllViews();
    Uri all=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    String[] fields={MediaStore.Audio.Media._ID,MediaStore.Audio.Media.DISPLAY_NAME,MediaStore.Audio.Media.SIZE};
    int shown=0;
    try(Cursor c=getContentResolver().query(all,fields,
      MediaStore.Audio.Media.RELATIVE_PATH+" LIKE ?",new String[]{"Music/StreamSaver/%"},
      MediaStore.Audio.Media.DATE_ADDED+" DESC")){
      while(c!=null&&c.moveToNext()&&shown<30){
        Uri uri=ContentUris.withAppendedId(all,c.getLong(0));
        String name=c.getString(1);
        long length=c.getLong(2);
        LinearLayout item=card();append(library,item,shown==0?0:9);
        append(item,text(name,15,FORE,true),0);
        append(item,text(pretty(length)+" · M4A/AAC",12,MUTED,false),5);
        LinearLayout actions=new LinearLayout(this); actions.setOrientation(0); append(item,actions,10);
        Button play=button("▶ Play",Color.rgb(38,54,73),FORE);
        Button share=button("Share",Color.rgb(38,54,73),FORE);
        Button rename=button("Rename",Color.rgb(38,54,73),TEAL);
        Button del=button("Delete",Color.rgb(77,39,52),FORE);
        for(Button b:new Button[]{play,rename,share,del}){
          LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(43),1);
          lp.setMargins(dp(2),0,dp(2),0);actions.addView(b,lp); b.setTextSize(12);
        }
        play.setOnClickListener(v->{
          Intent open=new Intent(this,PlayerActivity.class).setData(uri);
          open.putExtra("name",name);
          startActivity(open);
        });
        rename.setOnClickListener(v->showRename(uri,name));
        share.setOnClickListener(v->{
          Intent out=new Intent(Intent.ACTION_SEND).setType("audio/mp4");
          out.putExtra(Intent.EXTRA_STREAM,uri);
          out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          startActivity(Intent.createChooser(out,"Share recording"));
        });
        del.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Delete recording?")
          .setMessage(name).setPositiveButton("Delete",(d,w)->{
            try{getContentResolver().delete(uri,null,null);listRecordings();}
            catch(Exception e){toast("Unable to delete recording.");}
          }).setNegativeButton("Cancel",null).show());
        shown++;
      }
    }catch(Exception e){append(library,text("Unable to read recordings: "+e.getMessage(),12,RED,false),0);return;}
    if(shown==0)append(library,text("No recordings yet.",14,MUTED,false),4);
  }

  public static class CaptureService extends Service {
    static final String ACTION_START="com.streamsaver.recorder.START";
    static final String ACTION_STOP="com.streamsaver.recorder.STOP";
    static final AtomicBoolean active=new AtomicBoolean(false);
    static volatile long startedAt=0,bytes=0;
    static volatile double level=0;
    static volatile boolean waiting=false;
    static volatile String error="";
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final int RATE=48000, CHANNELS=2, FRAME_BYTES=4;
    private static final String CHANNEL="streamsaver_recording";
    private MediaProjection projection;
    private AudioRecord capture;
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private ParcelFileDescriptor fd;
    private Uri file;
    private Thread thread;
    private volatile boolean ending=false;
    private boolean muxerStarted=false, writeStarted=false;
    private int track=-1;

    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){
      super.onCreate();
      NotificationManager nm=getSystemService(NotificationManager.class);
      nm.createNotificationChannel(new NotificationChannel(CHANNEL,"Stream Saver recording",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(){
      PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),
        PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
      PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CaptureService.class).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
      return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_wave)
        .setContentTitle("Stream Saver · Recording")
        .setContentText("Capturing permitted playback audio").setOngoing(true)
        .setContentIntent(open)
        .addAction(new Notification.Action.Builder(
          android.graphics.drawable.Icon.createWithResource(this,android.R.drawable.ic_media_pause),
          "Stop & save",stop).build())
        .build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
      if(intent==null)return START_NOT_STICKY;
      if(ACTION_STOP.equals(intent.getAction())){requestStop();return START_NOT_STICKY;}
      if(!ACTION_START.equals(intent.getAction())||!active.compareAndSet(false,true))return START_NOT_STICKY;
      ending=false;error="";bytes=0;level=0;waiting=false;startedAt=SystemClock.elapsedRealtime();
      try{
        startForeground(10,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        int result=intent.getIntExtra("result",Activity.RESULT_CANCELED);
        Intent token=intent.getParcelableExtra("token");
        if(result!=Activity.RESULT_OK||token==null)throw new IllegalStateException("No permission token");
        projection=((MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE)).getMediaProjection(result,token);
        if(projection==null)throw new IOException("Android rejected the capture request");
        projection.registerCallback(new MediaProjection.Callback(){
          @Override public void onStop(){requestStop();}
        },main);
        String name=intent.getStringExtra("name");
        int bitrate=intent.getIntExtra("bitrate",192000);
        boolean wait=intent.getBooleanExtra("wait",false);
        boolean auto=intent.getBooleanExtra("auto",false);
        thread=new Thread(()->record(name,bitrate,wait,auto),"StreamSaverAudio");
        thread.start();
      }catch(Exception e){
        error="Could not start: "+e.getMessage();
        active.set(false);
        try{if(projection!=null)projection.stop();}catch(Exception ignored){}
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
      }
      return START_NOT_STICKY;
    }
    private void requestStop(){
      ending=true;
      if(thread==null||!thread.isAlive()){
        active.set(false);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
      }
    }
    private static double rms(byte[] samples,int len){
      int count=len/2;double sum=0;
      for(int i=0;i+1<len;i+=2){
        int v=(short)((samples[i]&255)|(samples[i+1]<<8));
        double f=v/32768.0;sum+=f*f;
      }
      return count==0?0:Math.sqrt(sum/count);
    }
    private void record(String title,int bitrate,boolean wait,boolean auto){
      long frames=0,silentAt=0;boolean heard=false;waiting=wait;
      try{
        AudioPlaybackCaptureConfiguration conf=new AudioPlaybackCaptureConfiguration.Builder(projection)
          .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
          .addMatchingUsage(AudioAttributes.USAGE_GAME).build();
        AudioFormat pcm=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
        int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<1)throw new IOException("Device cannot capture 48 kHz stereo audio");
        capture=new AudioRecord.Builder().setAudioPlaybackCaptureConfig(conf).setAudioFormat(pcm)
          .setBufferSizeInBytes(Math.max(min*4,16384)).build();
        if(capture.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Could not initialize playback capture");
        MediaFormat format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,RATE,CHANNELS);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE,bitrate);
        encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);encoder.start();

        String safe=title==null?"Stream Saver":title.trim();
        safe=safe.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");
        if(safe.isEmpty())safe="Stream Saver";
        if(safe.length()>110)safe=safe.substring(0,110);
        String full=safe+"_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date())+".m4a";
        ContentValues values=new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME,full);
        values.put(MediaStore.Audio.Media.MIME_TYPE,"audio/mp4");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH,"Music/StreamSaver/");
        values.put(MediaStore.Audio.Media.IS_PENDING,1);
        file=getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,values);
        if(file==null)throw new IOException("Cannot create recording file");
        fd=getContentResolver().openFileDescriptor(file,"w");
        if(fd==null)throw new IOException("Cannot open recording file");
        muxer=new MediaMuxer(fd.getFileDescriptor(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        capture.startRecording();
        byte[] chunk=new byte[8192];
        while(!ending){
          int ix=encoder.dequeueInputBuffer(10000);
          if(ix>=0){
            ByteBuffer buf=encoder.getInputBuffer(ix);
            if(buf==null)throw new IOException("Codec input unavailable");
            int n=capture.read(chunk,0,Math.min(chunk.length,buf.capacity()),AudioRecord.READ_BLOCKING);
            if(n<0)throw new IOException("Audio capture failure "+n);
            n=n-(n%FRAME_BYTES);
            if(n>0){
              double amplitude=rms(chunk,n);level=Math.min(1,amplitude*12);
              long now=SystemClock.elapsedRealtime();
              if(amplitude>0.003){heard=true;silentAt=0;waiting=false;}
              else if(heard&&silentAt==0)silentAt=now;
              if(auto&&heard&&silentAt>0&&now-silentAt>=8000)ending=true;
            }
            if(n>0&&(!wait||heard)&&!ending){
              buf.clear();buf.put(chunk,0,n);
              encoder.queueInputBuffer(ix,0,n,frames*1000000L/RATE,0);
              frames+=n/FRAME_BYTES;writeStarted=true;
            }else encoder.queueInputBuffer(ix,0,0,frames*1000000L/RATE,0);
          }
          drain(false);
        }
        if(capture.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)capture.stop();
        int ix=-1;
        while(ix<0){ix=encoder.dequeueInputBuffer(10000);if(ix<0)drain(false);}
        encoder.queueInputBuffer(ix,0,0,frames*1000000L/RATE,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        drain(true);
        if(!heard)error="No audible sound detected. The source app may prohibit capture.";
      }catch(Exception e){error="Recording error: "+e.getMessage();}
      finally{
        releaseAll();
        if(file!=null){
          try{
            if(muxerStarted&&writeStarted&&bytes>0){
              ContentValues done=new ContentValues();done.put(MediaStore.Audio.Media.IS_PENDING,0);
              getContentResolver().update(file,done,null,null);
            }else getContentResolver().delete(file,null,null);
          }catch(Exception ignored){}
        }
        file=null;waiting=false;level=0;active.set(false);
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
      }
    }
    private void drain(boolean finishing)throws IOException{
      MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
      for(int attempts=0;;){
        int ix=encoder.dequeueOutputBuffer(info,finishing?10000:0);
        if(ix==MediaCodec.INFO_TRY_AGAIN_LATER){
          if(!finishing||attempts++>100)break;
          continue;
        }
        if(ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
          if(muxerStarted)throw new IOException("Repeated encoder format change");
          track=muxer.addTrack(encoder.getOutputFormat());
          muxer.start();muxerStarted=true;continue;
        }
        if(ix<0)continue;
        ByteBuffer buf=encoder.getOutputBuffer(ix);
        if(buf!=null&&info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
          if(!muxerStarted)throw new IOException("AAC track not initialized");
          buf.position(info.offset);buf.limit(info.offset+info.size);
          muxer.writeSampleData(track,buf,info);bytes+=info.size;
        }
        boolean eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
        encoder.releaseOutputBuffer(ix,false);
        if(eos)break;
      }
    }
    private void releaseAll(){
      try{if(capture!=null&&capture.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)capture.stop();}catch(Exception ignored){}
      try{if(capture!=null)capture.release();}catch(Exception ignored){}
      try{if(encoder!=null)encoder.stop();}catch(Exception ignored){}
      try{if(encoder!=null)encoder.release();}catch(Exception ignored){}
      try{if(muxer!=null&&muxerStarted)muxer.stop();}catch(Exception ignored){}
      try{if(muxer!=null)muxer.release();}catch(Exception ignored){}
      try{if(fd!=null)fd.close();}catch(Exception ignored){}
      try{if(projection!=null)projection.stop();}catch(Exception ignored){}
    }
    @Override public void onDestroy(){ending=true;super.onDestroy();}
  }
}
