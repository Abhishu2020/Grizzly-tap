// ========================== // File: app/src/main/java/com/example/grizzlytap/MainActivity.java // ========================== package com.example.grizzlytap;

import android.app.Activity; import android.os.Bundle; import android.view.View; import android.view.WindowManager;

public class MainActivity extends Activity { private GameView gameView;

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // keep screen on while playing
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    gameView = new GameView(this);
    setContentView(gameView);
}

@Override
protected void onResume() {
    super.onResume();
    gameView.resume();
}

@Override
protected void onPause() {
    super.onPause();
    gameView.pause();
}

}

// ========================== // File: app/src/main/java/com/example/grizzlytap/GameView.java // ========================== package com.example.grizzlytap;

import android.content.Context; import android.content.SharedPreferences; import android.graphics.Canvas; import android.graphics.Paint; import android.graphics.RectF; import android.media.AudioManager; import android.media.ToneGenerator; import android.util.AttributeSet; import android.util.DisplayMetrics; import android.view.MotionEvent; import android.view.SurfaceHolder; import android.view.SurfaceView;

import java.util.ArrayList; import java.util.Iterator; import java.util.List;

public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable { private Thread thread; private volatile boolean running = false; private volatile boolean paused = false;

private final SurfaceHolder holder;
private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

private int canvasW = 720, canvasH = 780; // default logical size

// Game state
private long lastTime;
private long spawnTimer = 0;
private long spawnInterval = 1200; // ms
private final List<GameObject> objects = new ArrayList<>();
private final List<Particle> particles = new ArrayList<>();
private int score = 0;
private float speed = 240f; // px/sec base
private long difficultyTimer = 0;

// Player
private final Player player = new Player();

// UI state
private boolean showingCenter = true;
private String centerTitle = "Tap to Start";
private String centerSub = "Tap anywhere to jump. Avoid rocks and collect honey.";

// Highscore
private static final String PREFS = "grizzly_prefs";
private static final String HS_KEY = "grizzly_highscore_v1";
private final SharedPreferences prefs;

// Sound
private final ToneGenerator tone;
private boolean audioOn = true;

public GameView(Context context) {
    this(context, null);
}

public GameView(Context context, AttributeSet attrs) {
    super(context, attrs);
    holder = getHolder();
    holder.addCallback(this);

    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTextSize(64);

    smallPaint.setTextAlign(Paint.Align.CENTER);
    smallPaint.setTextSize(28);

    prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

    tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);

    setFocusable(true);
}

// Surface callbacks
@Override
public void surfaceCreated(SurfaceHolder holder) {
    resume();
}

@Override
public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    // adapt logical canvas size to view size while maintaining ratio
    canvasW = width;
    canvasH = height;
    // position player relative to canvas
    player.x = canvasW * 0.2f;
    player.y = canvasH * 0.65f;
    player.w = dp(68);
    player.h = dp(68);
}

@Override
public void surfaceDestroyed(SurfaceHolder holder) {
    pause();
}

public void resume() {
    if (running) return;
    running = true;
    paused = false;
    thread = new Thread(this);
    lastTime = System.nanoTime();
    thread.start();
}

public void pause() {
    running = false;
    // wait for thread
    try {
        if (thread != null) thread.join(1000);
    } catch (InterruptedException ignored) {}
    thread = null;
}

// Touch to jump / start
@Override
public boolean onTouchEvent(MotionEvent event) {
    switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
            if (!running) {
                resetGame();
                return true;
            }
            if (paused) {
                paused = false;
                lastTime = System.nanoTime();
                return true;
            }
            jump();
            return true;
    }
    return super.onTouchEvent(event);
}

private void jump() {
    // allow jump if on ground or small coyote time
    if (player.onGround || player.vy > -20) {
        player.vy = player.jumpStrength;
        player.onGround = false;
        playBeep();
    }
}

private void playBeep() {
    if (!audioOn) return;
    try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60); } catch (Exception ignored) {}
}

private void playCollect() {
    if (!audioOn) return;
    try { tone.startTone(ToneGenerator.TONE_PROP_ACK, 80); } catch (Exception ignored) {}
}

private void playCrash() {
    if (!audioOn) return;
    try { tone.startTone(ToneGenerator.TONE_PROP_NACK, 200); } catch (Exception ignored) {}
}

// Main loop
@Override
public void run() {
    while (running) {
        if (paused) {
            lastTime = System.nanoTime();
            sleep(16);
            continue;
        }

        long now = System.nanoTime();
        float dt = Math.min(40_000_000L, now - lastTime) / 1_000_000f; // ms
        lastTime = now;

        update((int)dt);
        drawFrame();

        // aim for ~60fps
        sleep(16);
    }
}

private void update(int dtMs) {
    if (!running) return;

    // update difficulty
    difficultyTimer += dtMs;
    if (difficultyTimer > 8000) {
        difficultyTimer = 0;
        speed *= 1.06f;
        if (spawnInterval > 700) spawnInterval -= 50;
    }

    // spawn
    spawnTimer += dtMs;
    if (spawnTimer > spawnInterval) {
        spawnTimer = 0;
        spawnObject();
    }

    float dt = dtMs / 1000f; // seconds

    // physics
    player.vy += player.gravity * dt;
    player.y += player.vy * dt;
    if (player.y + player.h/2f >= canvasH * 0.72f) {
        player.y = canvasH * 0.72f - player.h/2f;
        player.vy = 0;
        player.onGround = true;
    } else {
        player.onGround = false;
    }

    // update objects
    Iterator<GameObject> it = objects.iterator();
    while (it.hasNext()) {
        GameObject o = it.next();
        o.x += o.vx * dt;

        // hitbox
        RectF pbox = new RectF(player.x - player.w/2f, player.y - player.h/2f, player.x + player.w/2f, player.y + player.h/2f);
        RectF obox = new RectF(o.x - o.w/2f, o.y - o.h/2f, o.x + o.w/2f, o.y + o.h/2f);
        if (!o.collected && RectF.intersects(pbox, obox)) {
            if (o.type == GameObject.Type.HONEY) {
                o.collected = true;
                score += 10;
                addParticles(o.x, o.y - 10, 10);
                playCollect();
            } else if (o.type == GameObject.Type.ROCK) {
                // game over
                running = false;
                playCrash();
                showingCenter = true;
                centerTitle = "Game Over";
                centerSub = "Tap to restart";
                // save high
                int old = loadHigh();
                if (score > old) {
                    saveHigh(score);
                }
                return;
            }
        }

        if (o.x + o.w/2f < -60 || (o.collected && o.x < -10)) {
            it.remove();
        }
    }

    // update particles
    Iterator<Particle> pit = particles.iterator();
    long now = System.nanoTime()/1_000_000;
    while (pit.hasNext()) {
        Particle p = pit.next();
        if (now - p.started > p.t) { pit.remove(); continue; }
        p.vy += 1000f * dt;
        p.x += p.vx * dt;
        p.y += p.vy * dt;
    }

    // score slowly increases
    score += Math.max(0, Math.floor((2 * dt * (speed / 240f))));
}

private void drawFrame() {
    if (!holder.getSurface().isValid()) return;
    Canvas c = holder.lockCanvas();
    if (c == null) return;

    // clear
    c.drawRGB(255, 255, 255);

    // draw sky / ground
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xFFEAF7FF); // ground band
    c.drawRect(0, canvasH * 0.72f, canvasW, canvasH, paint);
    paint.setColor(0xFFFED1A4);
    c.drawRect(0, canvasH * 0.78f, canvasW, canvasH, paint);

    // draw objects - using emoji
    paint.setTextAlign(Paint.Align.CENTER);
    for (GameObject o : objects) {
        paint.setTextSize(o.w);
        if (o.type == GameObject.Type.HONEY) {
            c.drawText("🍯", o.x, o.y, paint);
        } else {
            c.drawText("🪨", o.x, o.y + (o.h * 0.06f), paint);
        }
    }

    // draw player
    paint.setTextSize(player.h);
    c.drawText("🐻", player.x, player.y, paint);

    // draw particles
    for (Particle p : particles) {
        float alpha = Math.max(0f, 1f - ((System.nanoTime()/1_000_000f - p.started) / p.t));
        int a = (int)(alpha * 255) & 0xff;
        paint.setColor((a << 24) | 0x00FFD36B);
        c.drawCircle(p.x, p.y, p.size, paint);
    }

    // HUD text
    paint.setColor(0xFF0B2B45);
    paint.setTextSize(dp(18));
    c.drawText("Score: " + score, dp(80), dp(34), paint);
    int hs = loadHigh();
    c.drawText("High: " + hs, canvasW - dp(80), dp(34), paint);

    // center message
    if (showingCenter) {
        paint.setColor(0xFF223344);
        paint.setTextSize(dp(32));
        c.drawText(centerTitle, canvasW/2f, canvasH/2f - dp(12), paint);
        smallPaint.setColor(0xFF666666);
        smallPaint.setTextSize(dp(16));
        c.drawText(centerSub, canvasW/2f, canvasH/2f + dp(18), smallPaint);
    }

    holder.unlockCanvasAndPost(c);
}

// helpers
private int dp(int px) {
    DisplayMetrics dm = getResources().getDisplayMetrics();
    return Math.round(px * (dm.densityDpi / 160f));
}

private void sleep(long ms) {
    try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
}

private void resetGame() {
    objects.clear();
    particles.clear();
    score = 0;
    speed = 240f;
    difficultyTimer = 0;
    spawnTimer = 0;
    player.y = canvasH * 0.65f;
    player.vy = 0;
    player.onGround = false;
    player.scale = 1f;
    showingCenter = false;
    lastTime = System.nanoTime();
}

private void spawnObject() {
    boolean isHoney = Math.random() < 0.45;
    float size = isHoney ? dp(44) : Math.round(rand(dp(48), dp(88)));
    float yPos = isHoney ? rand(canvasH*0.35f, canvasH*0.6f) : (canvasH*0.72f - size);
    GameObject o = new GameObject();
    o.type = isHoney ? GameObject.Type.HONEY : GameObject.Type.ROCK;
    o.x = canvasW + 60;
    o.y = yPos;
    o.w = size; o.h = size;
    o.vx = -speed * (1f + (float)Math.random()*0.15f);
    o.collected = false;
    objects.add(o);
}

private void addParticles(float x, float y, int count) {
    for (int i=0;i<count;i++) {
        Particle p = new Particle();
        p.x = x; p.y = y;
        p.vx = rand(-160,160);
        p.vy = rand(-340,-80);
        p.t = (long)rand(400,900);
        p.size = rand(4,9);
        p.started = System.nanoTime()/1_000_000;
        particles.add(p);
    }
}

private int loadHigh() {
    return prefs.getInt(HS_KEY, 0);
}

private void saveHigh(int v) {
    prefs.edit().putInt(HS_KEY, v).apply();
}

private float rand(float a, float b) { return (float)(Math.random()*(b-a)+a); }

// small classes
private static class Player {
    float x=140, y=520, w=68, h=68;
    float vy=0;
    boolean onGround=false;
    float gravity = 1400f;
    float jumpStrength = -520f;
    float scale = 1f;
}

private static class GameObject {
    enum Type { HONEY, ROCK }
    Type type;
    float x,y,w,h,vx;
    boolean collected=false;
}

private static class Particle {
    float x,y,vx,vy,size;
    long t; // ms
    long started; // ms
}

}

// ========================== // File: app/src/main/AndroidManifest.xml // ==========================

<?xml version="1.0" encoding="utf-8"?><manifest xmlns:android="http://schemas.android.com/apk/res/android"
package="com.example.grizzlytap">

<uses-permission android:name="android.permission.WAKE_LOCK" />

<application
    android:allowBackup="true"
    android:label="Grizzly Tap"
    android:supportsRtl="true">
    <activity android:name=".MainActivity"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>

</manifest>// ========================== // File: app/build.gradle (module) // ========================== // Minimal module gradle for Android app (paste into your Android Studio project) // apply plugin: 'com.android.application'

/* android { compileSdkVersion 34 defaultConfig { applicationId "com.example.grizzlytap" minSdkVersion 21 targetSdkVersion 34 versionCode 1 versionName "1.0" } buildTypes { release { minifyEnabled false proguardFiles getDefaultProguardFile('proguard-android.txt'), 'proguard-rules.pro' } } }

dependencies { implementation 'androidx.appcompat:appcompat:1.6.1' } */

// ========================== // Notes // ========================== // • Copy the Java files into the package folder 'com.example.grizzlytap' inside app/src/main/java. // • Paste the AndroidManifest.xml into app/src/main/AndroidManifest.xml // • Add the Gradle snippet into your module build.gradle and sync. // • This version uses emoji characters for bear/honey/rock drawn via Canvas.drawText(). On some devices/emulators //   certain emoji fonts may render slightly different — you can replace with PNGs and draw them with Bitmap if preferred. // • Sound uses ToneGenerator for simple beeps; replace with SoundPool and short sound files if you need more control. // • Let me know if you want a version that uses SurfaceView + separate GameThread file, or a LibGDX port.