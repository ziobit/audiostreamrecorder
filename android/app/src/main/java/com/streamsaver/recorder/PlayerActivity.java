package com.streamsaver.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.Visualizer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * Self-contained local audio player and real-time Winamp-inspired visualizer.
 * Captures the MediaPlayer audio session only; never accesses a microphone or network.
 * @author Marco Vezzale (marcovezzale@gmail.com)
 */
public class PlayerActivity extends Activity {
  private static final int BG=0xFF070C18, PANEL=0xFF111C30;
  private static final int TEXT=0xFFECF6FF, DIM=0xFF8FA6BD;
  private static final int CYAN=0xFF4FDECE, PINK=0xFFFF61B0, GOLD=0xFFFFC66C;
  private static final int REQUEST_VISUALIZER=211;
  private static final String[] MODES={"SPECTRUM","OSCILLOSCOPE","NEON TUNNEL"};
  private final Handler handler=new Handler(Looper.getMainLooper());
  private final ArrayList<Track> tracks=new ArrayList<>();
  private MediaPlayer media;
  private boolean playerReady=false;
  private Visualizer audioVisualizer;
  private VisualizerView graphics;
  private LinearLayout root, header, fullTop, fullBottom;
  private FrameLayout visualHolder, fullRoot;
  private TextView songLabel, metaLabel, clock, fullName, fullClock;
  private Button mainPlay, fullPlay, shuffleButton, repeatButton, modeButton, fullModeButton;
  private SeekBar seek, fullSeek;
  private boolean immersive=false, dragging=false, fullDragging=false, shuffle=false;
  private int repeat=0, mode=0, index=0;
  private long randomState=0x551922L;
  private String initialName="Recording";

  private static class Track {
    Uri uri;
    String name;
    Track(Uri u,String n) {uri=u;name=n;}
  }

  private int dp(float n) {return Math.round(n*getResources().getDisplayMetrics().density);}
  private LinearLayout stack(boolean horizontal) {
    LinearLayout l=new LinearLayout(this);l.setOrientation(horizontal?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);return l;
  }
  private GradientDrawable surface(int color,int radius) {
    GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
  }
  private TextView text(String value,int size,int color,boolean bold) {
    TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);
    if(bold)t.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
    return t;
  }
  private Button button(String label,int color,int fg) {
    Button b=new Button(this);b.setText(label);b.setTextSize(12);b.setAllCaps(false);
    b.setTextColor(fg);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    b.setBackground(surface(color,12));b.setMinimumHeight(0);b.setMinHeight(dp(44));
    b.setPadding(dp(5),dp(5),dp(5),dp(5));return b;
  }
  private void add(LinearLayout parent,View child,int top) {
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
    lp.topMargin=dp(top);parent.addView(child,lp);
  }
  private void addWeighted(LinearLayout row,View child,float weight,int margin) {
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),weight);
    lp.setMargins(dp(margin),0,dp(margin),0);row.addView(child,lp);
  }
  private LinearLayout controls(boolean full) {
    LinearLayout line=stack(true);
    Button previous=button("◀◀",PANEL,TEXT);
    Button play=button("▶",CYAN,BG);
    Button next=button("▶▶",PANEL,TEXT);
    if(full)fullPlay=play;else mainPlay=play;
    previous.setContentDescription("Previous recording");
    play.setContentDescription("Play or pause");
    next.setContentDescription("Next recording");
    addWeighted(line,previous,1,3);addWeighted(line,play,1.45f,3);addWeighted(line,next,1,3);
    previous.setOnClickListener(v->navigate(-1,false));
    play.setOnClickListener(v->togglePlayback());
    next.setOnClickListener(v->navigate(1,false));
    return line;
  }
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    Uri first=getIntent().getData();
    if(first==null) {finish();return;}
    initialName=getIntent().getStringExtra("name");
    if(initialName==null||initialName.isEmpty())initialName="Recording";
    loadTracks(first,initialName);
    graphics=new VisualizerView();
    buildNormal();
    buildFullscreen();
    playTrack(index);
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
      // The permission is needed only for live spectrum measurements, not listening.
      requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_VISUALIZER);
    }
  }
  private void loadTracks(Uri selection,String name) {
    Uri collection=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    String[] columns={MediaStore.Audio.Media._ID,MediaStore.Audio.Media.DISPLAY_NAME};
    try(Cursor c=getContentResolver().query(collection,columns,
        MediaStore.Audio.Media.RELATIVE_PATH+" LIKE ? AND "+MediaStore.Audio.Media.IS_PENDING+" = 0",
        new String[]{"Music/StreamSaver/%"},
        MediaStore.Audio.Media.DATE_ADDED+" DESC")) {
      while(c!=null&&c.moveToNext()&&tracks.size()<100) {
        tracks.add(new Track(ContentUris.withAppendedId(collection,c.getLong(0)),c.getString(1)));
      }
    }catch(Exception ignored){}
    for(int i=0;i<tracks.size();i++) {
      if(tracks.get(i).uri.equals(selection)){index=i;return;}
    }
    tracks.add(0,new Track(selection,name));index=0;
  }
  private void buildNormal() {
    root=stack(false);
    root.setBackgroundColor(BG);
    root.setPadding(dp(13),dp(13),dp(13),dp(12));
    setContentView(root);
    header=stack(true);
    header.setGravity(Gravity.CENTER_VERTICAL);
    add(root,header,0);
    TextView brand=text("▥ STREAM SAVER  /  PLAYER",15,CYAN,true);
    header.addView(brand,new LinearLayout.LayoutParams(0,dp(45),1));
    brand.setGravity(Gravity.CENTER_VERTICAL);
    Button finish=button("✕",PANEL,TEXT);
    header.addView(finish,new LinearLayout.LayoutParams(dp(42),dp(42)));
    finish.setOnClickListener(v->finish());
    visualHolder=new FrameLayout(this);
    visualHolder.setBackground(surface(0xFF0A1526,16));
    visualHolder.setClipToOutline(true);
    LinearLayout.LayoutParams picture=new LinearLayout.LayoutParams(-1,0,1f);
    picture.topMargin=dp(8);root.addView(visualHolder,picture);
    visualHolder.addView(graphics,new FrameLayout.LayoutParams(-1,-1));
    LinearLayout bottom=stack(false);
    bottom.setPadding(dp(13),dp(12),dp(13),dp(11));
    bottom.setBackground(surface(PANEL,16));
    add(root,bottom,9);
    songLabel=text(initialName,18,TEXT,true);songLabel.setSingleLine(true);
    songLabel.setEllipsize(TextUtils.TruncateAt.MARQUEE);
    songLabel.setMarqueeRepeatLimit(-1);songLabel.setSelected(true);
    add(bottom,songLabel,0);
    metaLabel=text("LOCAL RECORDING",11,DIM,false);add(bottom,metaLabel,4);
    LinearLayout timeline=stack(true);timeline.setGravity(Gravity.CENTER_VERTICAL);
    add(bottom,timeline,8);
    clock=text("00:00 / 00:00",12,CYAN,true);
    timeline.addView(clock,new LinearLayout.LayoutParams(-2,dp(27)));
    clock.setGravity(Gravity.CENTER_VERTICAL);
    seek=new SeekBar(this);seek.setMax(1000);
    seek.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
    seek.setThumbTintList(android.content.res.ColorStateList.valueOf(CYAN));
    timeline.addView(seek,new LinearLayout.LayoutParams(0,dp(35),1));
    attachSeek(seek,false);
    add(bottom,controls(false),5);
    LinearLayout modeRow=stack(true);
    add(bottom,modeRow,11);
    shuffleButton=button("SHUFFLE OFF",0xFF23334B,DIM);
    repeatButton=button("REPEAT OFF",0xFF23334B,DIM);
    modeButton=button("SPECTRUM",0xFF23334B,CYAN);
    addWeighted(modeRow,shuffleButton,1,2);
    addWeighted(modeRow,repeatButton,1,2);
    addWeighted(modeRow,modeButton,1.1f,2);
    shuffleButton.setOnClickListener(v->{
      shuffle=!shuffle;shuffleButton.setText(shuffle?"SHUFFLE ON":"SHUFFLE OFF");
      shuffleButton.setTextColor(shuffle?CYAN:DIM);
    });
    repeatButton.setOnClickListener(v->{
      repeat=(repeat+1)%3;
      repeatButton.setText(new String[]{"REPEAT OFF","REPEAT ONE","REPEAT ALL"}[repeat]);
      repeatButton.setTextColor(repeat==0?DIM:PINK);
    });
    modeButton.setOnClickListener(v->cycleMode());
    Button fullscreen=button("⛶   FULLSCREEN VISUAL EFFECTS",0xFF263F57,CYAN);
    add(bottom,fullscreen,9);
    fullscreen.setOnClickListener(v->toggleFullscreenEffects(true));
    TextView note=text("Logarithmic bands · automatic levels · tap the display to change effects.",11,DIM,false);
    note.setGravity(Gravity.CENTER);add(root,note,8);
  }
  private void buildFullscreen() {
    fullRoot=new FrameLayout(this);
    fullRoot.setBackgroundColor(BG);
    fullTop=stack(true);
    fullTop.setGravity(Gravity.CENTER_VERTICAL);
    fullTop.setPadding(dp(18),dp(11),dp(18),dp(9));
    fullTop.setBackgroundColor(0xBD07101E);
    fullName=text(initialName,16,TEXT,true);
    fullName.setSingleLine(true);fullName.setEllipsize(TextUtils.TruncateAt.MARQUEE);
    fullName.setSelected(true);
    fullTop.addView(fullName,new LinearLayout.LayoutParams(0,dp(44),1));
    fullName.setGravity(Gravity.CENTER_VERTICAL);
    Button exit=button("✕ EXIT",0xFF253349,TEXT);
    fullTop.addView(exit,new LinearLayout.LayoutParams(dp(82),dp(44)));
    exit.setOnClickListener(v->toggleFullscreenEffects(false));
    FrameLayout.LayoutParams top=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
    fullRoot.addView(fullTop,top);
    fullBottom=stack(false);
    fullBottom.setPadding(dp(14),dp(10),dp(14),dp(15));
    fullBottom.setBackgroundColor(0xCB081321);
    LinearLayout row=stack(true);row.setGravity(Gravity.CENTER_VERTICAL);
    add(fullBottom,row,0);
    fullClock=text("00:00 / 00:00",12,CYAN,true);
    row.addView(fullClock,new LinearLayout.LayoutParams(-2,dp(28)));
    fullClock.setGravity(Gravity.CENTER_VERTICAL);
    fullSeek=new SeekBar(this);fullSeek.setMax(1000);
    fullSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(CYAN));
    fullSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(CYAN));
    row.addView(fullSeek,new LinearLayout.LayoutParams(0,dp(32),1));
    attachSeek(fullSeek,true);
    LinearLayout bar=stack(true);add(fullBottom,bar,7);
    LinearLayout transport=controls(true);
    bar.addView(transport,new LinearLayout.LayoutParams(0,dp(46),2.1f));
    fullModeButton=button("CHANGE FX",0xFF243D56,CYAN);
    bar.addView(fullModeButton,new LinearLayout.LayoutParams(0,dp(46),1f));
    fullModeButton.setOnClickListener(v->{cycleMode();showFullControls();});
    FrameLayout.LayoutParams bottom=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
    fullRoot.addView(fullBottom,bottom);
  }
  private void attachSeek(SeekBar bar,boolean full) {
    bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onStartTrackingTouch(SeekBar s) {
        if(full)fullDragging=true;else dragging=true;
        if(immersive)showFullControls();
      }
      @Override public void onProgressChanged(SeekBar s,int value,boolean user) {
        if(user&&media!=null&&playerReady) {
          try {
            int duration=Math.max(0,media.getDuration());
            int position=(int)((long)duration*value/1000);
            if(full)fullClock.setText(formatTime(position)+ " / "+formatTime(duration));
            else clock.setText(formatTime(position)+ " / "+formatTime(duration));
          }catch(IllegalStateException ignored){}
        }
      }
      @Override public void onStopTrackingTouch(SeekBar s) {
        if(full)fullDragging=false;else dragging=false;
        if(media!=null&&playerReady) {
          try{media.seekTo((int)((long)media.getDuration()*s.getProgress()/1000));}
          catch(Exception ignored){}
        }
      }
    });
  }
  private void playTrack(int next) {
    if(next<0||next>=tracks.size())return;
    releasePlayer();
    index=next;
    Track t=tracks.get(index);
    songLabel.setText(t.name);fullName.setText(t.name);
    String lower=t.name.toLowerCase(Locale.ROOT);
    String format=lower.endsWith(".mp3")?"MP3":lower.endsWith(".m4a")?"AAC / M4A":"AUDIO";
    metaLabel.setText("TRACK "+(index+1)+" / "+tracks.size()+" · "+format+" · MUSIC/STREAMSAVER");
    try {
      media=new MediaPlayer();
      media.setAudioAttributes(new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
      media.setDataSource(this,t.uri);
      media.setOnPreparedListener(p->{
        if(p!=media)return;
        playerReady=true;
        p.start();
        setupAudioVisualizer();
        updateClock();
      });
      media.setOnCompletionListener(p->{
        if(p!=media)return;
        if(repeat==1) {
          p.seekTo(0);p.start();
        } else if(index+1<tracks.size()||repeat==2||shuffle) {
          navigate(1,true);
        } else updatePlayButtons();
      });
      media.setOnErrorListener((mp,what,extra)->{
        if(mp!=media)return true;
        Toast.makeText(this,"Unable to play this recording.",Toast.LENGTH_LONG).show();
        releasePlayer();updatePlayButtons();return true;
      });
      media.prepareAsync();
    }catch(Exception e) {
      Toast.makeText(this,"Unable to open audio: "+e.getMessage(),Toast.LENGTH_LONG).show();
      releasePlayer();
    }
    updatePlayButtons();
  }
  private void releaseVisualizer() {
    if(audioVisualizer!=null) {
      try{audioVisualizer.setEnabled(false);}catch(Exception ignored){}
      try{audioVisualizer.release();}catch(Exception ignored){}
      audioVisualizer=null;
    }
    if(graphics!=null)graphics.resetInput();
  }
  private void releasePlayer() {
    playerReady=false;
    releaseVisualizer();
    if(media!=null) {
      try{media.reset();media.release();}catch(Exception ignored){}
      media=null;
    }
  }
  private void setupAudioVisualizer() {
    if(media==null||!playerReady)return;
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return;
    releaseVisualizer();
    try {
      audioVisualizer=new Visualizer(media.getAudioSessionId());
      int[] range=Visualizer.getCaptureSizeRange();
      int captureSize=Integer.highestOneBit(range[1]);
      if(captureSize<range[0])throw new IllegalStateException("Unsupported FFT capture size");
      if(audioVisualizer.setCaptureSize(captureSize)!=Visualizer.SUCCESS)
        throw new IllegalStateException("Unable to set FFT capture size");
      // Normalization prevents the phone's volume slider from shrinking the picture.
      audioVisualizer.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
      int rate=Math.min(30000,Visualizer.getMaxCaptureRate());
      int listenerResult=audioVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
        @Override public void onWaveFormDataCapture(Visualizer source,byte[] data,int sampleRate) {
          if(source==audioVisualizer)graphics.setWave(data);
        }
        @Override public void onFftDataCapture(Visualizer source,byte[] data,int sampleRate) {
          if(source==audioVisualizer)graphics.setSpectrum(data,sampleRate);
        }
      },rate,true,true);
      if(listenerResult!=Visualizer.SUCCESS||audioVisualizer.setEnabled(true)!=Visualizer.SUCCESS)
        throw new IllegalStateException("Live audio analysis unavailable");
    }catch(Exception e) {
      releaseVisualizer();
    }
  }
  @Override public void onRequestPermissionsResult(int code,String[] p,int[] grants) {
    super.onRequestPermissionsResult(code,p,grants);
    if(code==REQUEST_VISUALIZER&&grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)
      setupAudioVisualizer();
  }
  private void togglePlayback() {
    if(media==null||!playerReady)return;
    try{
      if(media.isPlaying())media.pause();else media.start();
      updatePlayButtons();
    }catch(Exception ignored){}
    if(immersive)showFullControls();
  }
  private void navigate(int direction,boolean autoplay) {
    if(tracks.isEmpty())return;
    int position=index;
    if(shuffle&&tracks.size()>1) {
      randomState=(randomState*1664525L+1013904223L)&0xFFFFFFFFL;
      int change=1+(int)(randomState%(tracks.size()-1));
      position=(index+change)%tracks.size();
    } else {
      position=index+direction;
      if(position<0)position=tracks.size()-1;
      if(position>=tracks.size())position=0;
    }
    playTrack(position);
    if(immersive)showFullControls();
  }
  private boolean isPlaying() {
    try{return playerReady&&media!=null&&media.isPlaying();}
    catch(IllegalStateException ignored){return false;}
  }
  private void updatePlayButtons() {
    boolean playing=isPlaying();
    if(mainPlay!=null){mainPlay.setText(playing?"❚❚":"▶");mainPlay.setEnabled(playerReady);}
    if(fullPlay!=null){fullPlay.setText(playing?"❚❚":"▶");fullPlay.setEnabled(playerReady);}
    if(seek!=null)seek.setEnabled(playerReady);
    if(fullSeek!=null)fullSeek.setEnabled(playerReady);
  }
  private static String formatTime(int millis) {
    int sec=Math.max(0,millis)/1000;
    return String.format(Locale.US,"%02d:%02d",sec/60,sec%60);
  }
  private final Runnable progress=new Runnable() {
    @Override public void run(){updateClock();handler.postDelayed(this,300);}
  };
  private void updateClock() {
    if(media==null||!playerReady)return;
    try {
      int duration=Math.max(0,media.getDuration());
      int position=Math.max(0,media.getCurrentPosition());
      String label=formatTime(position)+" / "+formatTime(duration);
      if(!dragging){clock.setText(label);seek.setProgress(duration>0?(int)(position*1000L/duration):0);}
      if(!fullDragging){fullClock.setText(label);fullSeek.setProgress(duration>0?(int)(position*1000L/duration):0);}
      updatePlayButtons();
    }catch(Exception ignored){}
  }
  private void cycleMode() {
    mode=(mode+1)%MODES.length;
    graphics.setMode(mode);
    modeButton.setText(MODES[mode]);
    fullModeButton.setText("FX: "+MODES[mode]);
  }
  private final Runnable hideFullControls=()->{
    if(immersive){fullTop.setVisibility(View.GONE);fullBottom.setVisibility(View.GONE);}
  };
  private void showFullControls() {
    if(!immersive)return;
    fullTop.setVisibility(View.VISIBLE);fullBottom.setVisibility(View.VISIBLE);
    handler.removeCallbacks(hideFullControls);
    handler.postDelayed(hideFullControls,5200);
  }
  private void toggleFullscreenEffects(boolean enable) {
    if(immersive==enable)return;
    immersive=enable;
    ((ViewGroup)graphics.getParent()).removeView(graphics);
    if(enable){
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      fullRoot.addView(graphics,0,new FrameLayout.LayoutParams(-1,-1));
      setContentView(fullRoot);
      getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
      showFullControls();
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      handler.removeCallbacks(hideFullControls);
      fullRoot.removeView(graphics);
      visualHolder.addView(graphics,new FrameLayout.LayoutParams(-1,-1));
      setContentView(root);
      getWindow().getDecorView().setSystemUiVisibility(0);
    }
  }
  @Override public void onBackPressed() {
    if(immersive)toggleFullscreenEffects(false);else super.onBackPressed();
  }
  @Override protected void onResume() {
    super.onResume();handler.removeCallbacks(progress);handler.post(progress);
  }
  @Override protected void onPause() {
    handler.removeCallbacks(progress);handler.removeCallbacks(hideFullControls);super.onPause();
  }
  @Override protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);releasePlayer();super.onDestroy();
  }

  private static final class AudioFrame {
    final byte[] data;
    final int samplingRateMilliHz;
    final long timeMs;
    AudioFrame(byte[] source,int sampleRate) {
      data=Arrays.copyOf(source,source.length);
      samplingRateMilliHz=sampleRate;
      timeMs=SystemClock.uptimeMillis();
    }
  }

  private class VisualizerView extends View {
    private final Paint p=new Paint(3);
    private final Path path=new Path();
    private final SpectrumAnalyzer analyzer=new SpectrumAnalyzer();
    private final float[] heights=new float[64],peaks=new float[64],targets=new float[64];
    private volatile AudioFrame spectrumFrame,waveFrame;
    private AudioFrame analyzedSpectrum,analyzedWave;
    private int effect=0;
    private float phase=0,waveReference=8f;
    private long lastDrawMs=0;
    private boolean liveSpectrum=false,liveWave=false,waveHasSignal=false,playing=false;
    private Shader gradient;
    private int w,h;
    VisualizerView() {
      super(PlayerActivity.this);
      setLayerType(View.LAYER_TYPE_SOFTWARE,null);
      setOnClickListener(v->{
        if(immersive) {
          if(fullTop.getVisibility()==View.VISIBLE){
            fullTop.setVisibility(View.GONE);fullBottom.setVisibility(View.GONE);
            handler.removeCallbacks(hideFullControls);
          }else showFullControls();
        }else cycleMode();
      });
    }
    void setMode(int n){effect=n;invalidate();}
    void setWave(byte[] data){waveFrame=data==null?null:new AudioFrame(data,0);}
    void setSpectrum(byte[] data,int sampleRate){
      spectrumFrame=data==null?null:new AudioFrame(data,sampleRate);
    }
    void resetInput(){
      spectrumFrame=null;waveFrame=null;analyzedSpectrum=null;analyzedWave=null;
      liveSpectrum=false;liveWave=false;waveHasSignal=false;waveReference=8f;
      analyzer.reset();
      Arrays.fill(targets,0);Arrays.fill(heights,0);Arrays.fill(peaks,0);
    }
    @Override protected void onAttachedToWindow() {
      super.onAttachedToWindow();lastDrawMs=0;postInvalidateOnAnimation();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
      super.onWindowVisibilityChanged(visibility);
      if(visibility==View.VISIBLE){lastDrawMs=0;postInvalidateOnAnimation();}
    }
    @Override protected void onSizeChanged(int width,int height,int oldW,int oldH) {
      super.onSizeChanged(width,height,oldW,oldH);
      w=width;h=height;
      if(width>0&&height>0)gradient=new RadialGradient(width*0.52f,height*0.56f,Math.max(width,height)*0.8f,
        new int[]{0xFF1A3451,0xFF0D1B32,0xFF070B17},null,Shader.TileMode.CLAMP);
    }
    private void color(int c,int alpha) {
      p.setShader(null);
      p.setStyle(Paint.Style.FILL);
      p.setStrokeWidth(dp(1));
      p.setStrokeCap(Paint.Cap.BUTT);
      p.setColor((c&0x00FFFFFF)|(Math.max(0,Math.min(255,alpha))<<24));
    }
    private int spectral(int i) {
      float[] hsv={170f+146f*i/63f,0.72f,1f};
      return Color.HSVToColor(hsv);
    }
    private void background(Canvas c) {
      c.drawColor(BG);
      if(gradient!=null){p.setShader(gradient);c.drawRect(0,0,w,h,p);p.setShader(null);}
      for(int i=0;i<=11;i++) {
        color(CYAN,13);p.setStrokeWidth(dp(0.5f));
        float y=h*i/11f;c.drawLine(0,y,w,y,p);
      }
      for(int i=0;i<=16;i++) {
        color(PINK,10);
        float x=w*i/16f;c.drawLine(x,0,x,h,p);
      }
      for(int i=0;i<43;i++) {
        float xx=(float)((i*139.18+phase*(10+i%5))%Math.max(1,w));
        float yy=(float)((i*103.41+Math.sin(phase*.6+i)*9+h*4)%Math.max(1,h));
        color(i%3==0?PINK:CYAN,40+(i*11)%90);
        c.drawCircle(xx,yy,dp(i%7==0?1.7f:.8f),p);
      }
    }
    private void label(Canvas c,String title) {
      color(TEXT,205);
      p.setTypeface(Typeface.create("monospace",Typeface.BOLD));
      p.setTextSize(dp(11));
      c.drawText("STREAM SAVER  /  "+title,dp(16),dp(25),p);
      p.setTypeface(null);p.setTextSize(dp(10));
      boolean live=effect==1?liveWave:liveSpectrum;
      color(live?CYAN:GOLD,200);
      String status;
      if(live)status=effect==0?"● LIVE · AUTO LEVEL · LOG BANDS":"● LIVE AUDIO · AUTO LEVEL";
      else if(!playerReady)status="● WAITING FOR AUDIO";
      else if(!playing)status="Ⅱ PAUSED";
      else status="● LIVE ANALYSIS UNAVAILABLE";
      c.drawText(status,dp(16),h-dp(15),p);
    }
    private String frequencyLabel(float hz) {
      if(hz>=1000)return Math.round(hz/1000f)+"k";
      return String.valueOf(Math.round(hz));
    }
    private void spectrum(Canvas c) {
      float top=Math.min(dp(41),h*.25f),bottom=Math.max(top,h-dp(59));
      float available=Math.max(0,bottom-top);
      float padding=dp(19),step=Math.max(1,w-2*padding)/64f;
      for(int i=0;i<64;i++) {
        float amount=heights[i];
        float x=padding+i*step+step*.07f;
        float width=Math.max(1,step*.79f);
        float barHeight=Math.min(available,Math.max(dp(1),available*amount));
        int base=spectral(i);
        p.setShader(new LinearGradient(x,bottom-barHeight-dp(1),x,bottom,
          new int[]{0xFFF8D4FA,base,(base&0x00FFFFFF)|0x66000000},null,Shader.TileMode.CLAMP));
        c.drawRoundRect(x,bottom-barHeight,x+width,bottom,Math.min(dp(3),width*.4f),Math.min(dp(3),width*.4f),p);
        p.setShader(null);
        if(peaks[i]>.015f){
          color(CYAN,200);
          float peakY=Math.max(top,bottom-available*peaks[i]);
          c.drawRect(x,peakY,x+width,peakY+dp(2),p);
        }
      }
      color(CYAN,75);p.setStrokeWidth(dp(1));
      c.drawLine(padding,bottom+dp(3),w-padding,bottom+dp(3),p);
      float low=analyzer.getLowHz(),high=analyzer.getHighHz();
      float[] ticks={low,250f,1000f,4000f,high};
      p.setTextSize(dp(10));color(DIM,220);
      for(int i=0;i<ticks.length;i++) {
        if(ticks[i]<low||ticks[i]>high)continue;
        float fraction=(float)(Math.log(ticks[i]/low)/Math.log(high/low));
        p.setTextAlign(i==0?Paint.Align.LEFT:i==ticks.length-1?Paint.Align.RIGHT:Paint.Align.CENTER);
        c.drawText(frequencyLabel(ticks[i])+(i==ticks.length-1?" Hz":""),
          padding+fraction*(w-2*padding),bottom+dp(19),p);
      }
      p.setTextAlign(Paint.Align.LEFT);
    }
    private void oscilloscope(Canvas c) {
      float top=Math.min(dp(41),h*.25f),bottom=Math.max(top,h-dp(37));
      float mid=(top+bottom)*.5f,amplitude=Math.max(0,(bottom-top)*.5f-dp(3));
      float left=dp(18),right=w-dp(18);
      color(PINK,130);p.setStrokeWidth(dp(1));
      c.drawLine(left,mid,right,mid,p);
      AudioFrame frame=waveFrame;
      byte[] data=liveWave&&waveHasSignal&&frame!=null?frame.data:null;
      for(int layer=4;layer>=0;layer--) {
        path.rewind();
        int samples=Math.max(1,(int)((right-left)/2));
        for(int i=0;i<=samples;i++) {
          float t=i/(float)samples,signal=0f;
          if(data!=null&&data.length>0) {
            int j=Math.min(data.length-1,(int)(t*(data.length-1)));
            signal=Math.max(-1f,Math.min(1f,((data[j]&255)-128)/waveReference));
          }
          float x=left+t*(right-left),y=mid-signal*amplitude;
          if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        color(layer==0?CYAN:(layer%2==0?PINK:CYAN),layer==0?255:26+(4-layer)*10);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(layer==0?1.7f:(layer*2+1)));
        c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
      }
      for(int k=0;k<5;k++) {
        color(CYAN,42);
        float y=mid+(k-2)*amplitude*.45f;
        c.drawLine(left,y,right,y,p);
      }
    }
    private void tunnel(Canvas c) {
      float top=Math.min(dp(41),h*.25f),bottom=Math.max(top,h-dp(37));
      float cx=w*.5f,cy=(top+bottom)*.5f;
      float radius=Math.max(1,Math.min(w,bottom-top)*.47f);
      for(int ring=12;ring>=1;ring--) {
        float r=radius*ring/12f;
        color(ring%3==0?PINK:CYAN,15+(12-ring)*5);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(ring%3==0?2:1));
        c.drawCircle(cx,cy,r,p);
      }
      for(int i=0;i<64;i++) {
        float angle=(float)(i*Math.PI*2/64+phase*.14);
        float value=heights[i],inner=radius*.25f;
        float outer=inner+(radius-inner)*value;
        float sx=cx+(float)Math.cos(angle)*inner,sy=cy+(float)Math.sin(angle)*inner;
        float ex=cx+(float)Math.cos(angle)*outer,ey=cy+(float)Math.sin(angle)*outer;
        color(spectral(i),160);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2.3f+value*4));
        p.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(sx,sy,ex,ey,p);
      }
      p.setStyle(Paint.Style.FILL);
      float innerGlow=radius*.19f;
      color(PINK,60);c.drawCircle(cx,cy,innerGlow*1.2f,p);
      color(CYAN,200);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2.5f));
      c.drawCircle(cx,cy,innerGlow,p);
      color(TEXT,225);p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.CENTER);
      p.setTextSize(dp(11));p.setTypeface(Typeface.create("monospace",Typeface.BOLD));
      c.drawText(liveSpectrum?"LIVE":playing?"WAIT":"PAUSED",cx,cy+dp(4),p);
      p.setTextAlign(Paint.Align.LEFT);p.setTypeface(null);
    }
    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      long now=SystemClock.uptimeMillis();
      float seconds=lastDrawMs==0?1f/30f:Math.max(.001f,Math.min(.1f,(now-lastDrawMs)/1000f));
      lastDrawMs=now;phase+=seconds*1.5f;
      playing=isPlaying();
      AudioFrame spectrum=spectrumFrame,wave=waveFrame;
      liveSpectrum=playing&&spectrum!=null&&now-spectrum.timeMs<750;
      liveWave=playing&&wave!=null&&now-wave.timeMs<750;
      if(liveSpectrum&&spectrum!=analyzedSpectrum) {
        float elapsed=analyzedSpectrum==null?1f/30f:(spectrum.timeMs-analyzedSpectrum.timeMs)/1000f;
        analyzer.analyze(spectrum.data,spectrum.samplingRateMilliHz,elapsed,targets);
        analyzedSpectrum=spectrum;
      }else if(!liveSpectrum)Arrays.fill(targets,0f);
      if(liveWave&&wave!=analyzedWave) {
        int peak=0;
        for(byte value:wave.data)peak=Math.max(peak,Math.abs((value&255)-128));
        float elapsed=analyzedWave==null?1f/30f:Math.min(.25f,(wave.timeMs-analyzedWave.timeMs)/1000f);
        waveReference=Math.max(8f,Math.max(peak,waveReference*(float)Math.exp(-elapsed/1.2f)));
        waveHasSignal=peak>=2;analyzedWave=wave;
      }
      for(int i=0;i<64;i++) {
        float timeConstant=targets[i]>heights[i]?.025f:.22f;
        float amount=1f-(float)Math.exp(-seconds/timeConstant);
        heights[i]+=(targets[i]-heights[i])*amount;
        if(targets[i]>=.999f&&heights[i]>.99f)heights[i]=1f;
        peaks[i]=Math.max(heights[i],peaks[i]-seconds*.35f);
      }
      background(canvas);
      if(effect==0)spectrum(canvas);
      else if(effect==1)oscilloscope(canvas);
      else tunnel(canvas);
      label(canvas,MODES[effect]);
      if(getWindowVisibility()==View.VISIBLE&&isShown())postInvalidateDelayed(33);
    }
  }
}
