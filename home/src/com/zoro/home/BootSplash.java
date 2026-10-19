package com.zoro.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Plays the radOS screen in the very first seconds of a restart, while Android is still unlocking storage
 * and would otherwise show its blank "fallback" home. It is direct boot aware, so Android may run it then;
 * it uses only things that are readable before unlock (the app's own assets).
 *
 * The logo moment is one prerendered video (boot.mp4: the globe, the word and the fade in) on its own
 * surface, so the hardware decoder keeps it smooth while the rest of the system is busy unlocking; nothing
 * is drawn frame by frame on the main thread. It starts only once this window is really on screen (the
 * system's own boot logo hides it until then), and the chime starts on the video's first frame.
 *
 * At the point where the logo steps back it waits for the unlock if needed, then hands over to the real
 * home screen, which plays the same exit over itself, so the two look like one.
 */
public class BootSplash extends Activity {
    static volatile boolean showing, homeTookOver;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaPlayer video, chime;
    private boolean surfaceUp, visible, started;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        showing = true;
        homeTookOver = false;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView sv = new SurfaceView(this);
        root.addView(sv, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // Both players are prepared now, while the system logo is still up, so starting them later is instant.
        try {
            chime = new MediaPlayer();
            android.content.res.AssetFileDescriptor a = getAssets().openFd("startup.wav");
            chime.setDataSource(a.getFileDescriptor(), a.getStartOffset(), a.getLength());
            a.close();
            chime.setVolume(0.7f, 0.7f);
            chime.prepare();
        } catch (Exception e) { chime = null; }
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                try {
                    video = new MediaPlayer();
                    android.content.res.AssetFileDescriptor v = getAssets().openFd("boot.mp4");
                    video.setDataSource(v.getFileDescriptor(), v.getStartOffset(), v.getLength());
                    v.close();
                    video.setDisplay(h);
                    video.setVolume(0f, 0f);
                    video.setOnInfoListener((mp, what, extra) -> {
                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onFirstFrame();
                        return false;
                    });
                    video.prepare();
                    surfaceUp = true;
                    maybeStart();
                } catch (Exception e) { handOverSoon(); }
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) { }
        });
        // The system's boot logo covers this window for its first half second or so. Start the moment the
        // system says its logo is done; if that flag cannot be read, a short timer stands in.
        created = SystemClock.uptimeMillis();
        main.post(watchBootLogo);
    }

    private long created;
    private final Runnable watchBootLogo = new Runnable() {
        @Override public void run() {
            String exit = prop("service.bootanim.exit"), svc = prop("init.svc.bootanim");
            long waited = SystemClock.uptimeMillis() - created;
            boolean done = "1".equals(exit) || "stopped".equals(svc);
            boolean readable = !exit.isEmpty() || !svc.isEmpty();
            if (done || waited > (readable ? 4000 : 1300)) {
                android.util.Log.i("radOS", "boot logo done=" + done + " exit=" + exit + " svc=" + svc + " after " + waited + " ms");
                visible = true;
                maybeStart();
            } else main.postDelayed(this, 40);
        }
    };

    private static String prop(String k) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, k);
            return v == null ? "" : (String) v;
        } catch (Throwable t) { return ""; }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Logged only: focus can arrive seconds late at boot (2026-10-19 it came after the unlock), so it is no start signal.
        if (hasFocus) android.util.Log.i("radOS", "boot splash focus after " + (SystemClock.uptimeMillis() - created) + " ms");
    }

    private void maybeStart() {
        if (started || !surfaceUp || !visible || video == null) return;
        started = true;
        video.start();
    }

    private long firstFrameAt;
    private void onFirstFrame() {
        if (firstFrameAt != 0) return;
        firstFrameAt = SystemClock.uptimeMillis();
        android.util.Log.i("radOS", "boot video first frame after " + (firstFrameAt - created) + " ms");
        if (chime != null) try { chime.start(); chime.setOnCompletionListener(MediaPlayer::release); } catch (Exception ignored) { }
        // When the animation started, in storage readable before unlock: the home screen uses it to carry on.
        try {
            createDeviceProtectedStorageContext().getSharedPreferences("boot", MODE_PRIVATE).edit()
                    .putLong("bootSplashAt", System.currentTimeMillis()).commit();
        } catch (Exception ignored) { }
        main.postDelayed(this::handOver, Splash.EXIT_AT);
    }

    private void handOverSoon() { main.postDelayed(this::handOver, 500); }

    private void handOver() {
        if (homeTookOver) { finishQuietly(); return; }
        UserManager um = getSystemService(UserManager.class);
        if (um != null && !um.isUserUnlocked()) { main.postDelayed(this::handOver, 150); return; }
        Intent i = new Intent(this, HomeActivity.class)
                .putExtra("reveal", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
        overridePendingTransition(0, 0);
        main.postDelayed(this::finishQuietly, 400);
    }

    private void finishQuietly() {
        showing = false;
        if (video != null) try { video.release(); } catch (Exception ignored) { }
        video = null;
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() { }
}
