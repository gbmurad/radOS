package com.zoro.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * The radOS startup screen: five seconds over the home screen while it loads behind.
 *   0.0 to 1.0 s  a deep violet glow opens up; the glass globe grows in and settles with a slow turn
 *   1.0 to 2.0 s  "rad" rises in letter by letter, then "OS"
 *   2.2 to 3.0 s  a band of light sweeps across the word
 *   whole time    the glow breathes and a thin line under the word fills
 *   4.4 to 5.0 s  everything lifts slightly and fades, showing the home screen
 */
final class Splash extends View {
    static final long LENGTH = 6500;
    static final long EXIT_AT = 4800;          // where the logo starts to step back
    long startAt = 0;                          // play from here (the home screen finishing a boot splash)
    long videoFrom = -1;                       // where the globe video starts, when it differs from startAt
    long stopAt = LENGTH;                      // stop here and call onStop instead of fading out
    Runnable onStop;
    boolean sound = true;
    private final float d;
    private android.graphics.Bitmap globe;
    private android.graphics.Bitmap[] frames;   // Astra's turntable frames, when present (assets/spin-00.png ...)
    private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint bg = new Paint(), glow = new Paint(Paint.ANTI_ALIAS_FLAG), word = new Paint(Paint.ANTI_ALIAS_FLAG),
            os = new Paint(Paint.ANTI_ALIAS_FLAG), sweep = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG),
            track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float t;   // milliseconds since start

    Splash(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        try (java.io.InputStream in = c.getAssets().open("rad_globe.png")) { globe = android.graphics.BitmapFactory.decodeStream(in); }
        catch (Exception ignored) { }
        try {
            String[] names = c.getAssets().list("");
            java.util.List<android.graphics.Bitmap> f = new java.util.ArrayList<>();
            for (int i = 0; i < 48; i++) {
                String n = String.format(java.util.Locale.US, "spin-%02d.png", i);
                if (names == null || !java.util.Arrays.asList(names).contains(n)) break;
                try (java.io.InputStream in = c.getAssets().open(n)) { f.add(android.graphics.BitmapFactory.decodeStream(in)); }
            }
            if (f.size() >= 6) frames = f.toArray(new android.graphics.Bitmap[0]);
        } catch (Exception ignored) { }
        // Rad's wordmark font: Nunito (open font licence) at its heaviest weight, for "rad" and "OS" alike.
        Typeface brand;
        try { brand = new Typeface.Builder(c.getAssets(), "Nunito.ttf").setFontVariationSettings("'wght' 900").build(); }
        catch (Exception e) { brand = Typeface.create("sans-serif-black", Typeface.NORMAL); }
        word.setColor(Color.WHITE);
        word.setTextSize(WORD_DP * d);
        word.setTypeface(brand);
        word.setLetterSpacing(-0.03f);
        os.setColor(Color.WHITE);
        os.setTextSize(43 * d);
        os.setTypeface(brand);
        os.setLetterSpacing(-0.03f);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(3 * d);
        track.setColor(0x26FFFFFF);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(3 * d);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Adds itself over everything, plays, then removes itself. */
    private android.view.TextureView video;
    private volatile boolean videoReady;
    private View backdrop;      // the black behind everything (so the video can sit under the words)
    private android.media.MediaPlayer videoPlayer;

    void play(ViewGroup parent) {
        home = parent.findViewById(android.R.id.content);
        if (home != null) { home.setScaleX(1.035f); home.setScaleY(1.035f); }
        backdrop = new View(getContext());
        backdrop.setBackgroundColor(Color.BLACK);
        parent.addView(backdrop, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addVideo(parent);
        parent.addView(this, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ValueAnimator a = ValueAnimator.ofFloat(startAt, stopAt);
        a.setDuration(stopAt - startAt);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); invalidate(); });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator x) {
                if (stopAt < LENGTH) { if (onStop != null) onStop.run(); return; }
                parent.removeView(Splash.this);
                if (video != null) parent.removeView(video);
                parent.removeView(backdrop);
                if (home != null) { home.setScaleX(1f); home.setScaleY(1f); }
                if (videoPlayer != null) { try { videoPlayer.release(); } catch (Exception ignored) { } }
            }
        });
        a.start();
        // the radOS chime, timed with the globe appearing
        if (sound) try {
            android.content.res.AssetFileDescriptor fd = getContext().getAssets().openFd("startup.wav");
            android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            mp.setVolume(0.7f, 0.7f);
            mp.setOnCompletionListener(android.media.MediaPlayer::release);
            mp.prepare();
            mp.start();
        } catch (Exception ignored) { }
    }

    /**
     * The spinning glass globe is a video (made in Google Flow): 1280x720, black background, the globe
     * centred and about 540 px tall with its glow. Played muted, sized so the globe matches the old logo size,
     * centred where the logo sits, and faded with the chime like the words.
     */
    private void addVideo(ViewGroup parent) {
        try {
            final android.content.res.AssetFileDescriptor fd = getContext().getAssets().openFd("globe.mp4");
            video = new android.view.TextureView(getContext());
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            float W = parent.getWidth() > 0 ? parent.getWidth() : dm.widthPixels;
            float H = parent.getHeight() > 0 ? parent.getHeight() : dm.heightPixels;
            int vw = Math.round(134 * d), vh = vw;          // the video is a 360x360 crop around the globe
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(vw, vh);
            lp.leftMargin = Math.round(globeX(W) - vw / 2f);
            lp.topMargin = Math.round(H / 2 - vh / 2f);
            video.setPivotX(W / 2 - lp.leftMargin);      // shrinks on exit about the screen's middle, with the word
            video.setPivotY(H / 2 - lp.topMargin);
            video.setAlpha(0f);
            // Screen blend: the video's black adds nothing, so no dark square shows while the home fades in behind it.
            android.graphics.Paint screen = new android.graphics.Paint();
            screen.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
            video.setLayerPaint(screen);
            parent.addView(video, lp);
            video.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                    try {
                        videoPlayer = new android.media.MediaPlayer();
                        videoPlayer.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                        videoPlayer.setSurface(new android.view.Surface(st));
                        videoPlayer.setVolume(0f, 0f);         // the video's own sound stays off
                        videoPlayer.setLooping(true);
                        videoPlayer.prepare();
                        if (videoFrom >= 0 || startAt > 0) videoPlayer.seekTo((int) (videoFrom >= 0 ? videoFrom : startAt));
                        videoPlayer.start();
                    } catch (Exception ignored) { }
                }
                @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) { }
                @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) { return true; }
                @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) { videoReady = true; }
            });
        } catch (Exception e) { video = null; }
    }

    private static float smooth(float v) { v = Math.max(0f, Math.min(1f, v)); return v * v * (3 - 2 * v); }

    private View home;   // the home screen under the splash, eased in as the black dissolves

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static float ease(float v) { v = clamp(v); return 1f - (1f - v) * (1f - v) * (1f - v); }
    private float phase(float from, float to) { return clamp((t - from) / (to - from)); }

    // The lockup, as on Rad's TV panel: the globe, and "radOS" to its right, its letters level with the globe's bottom edge,
    // a gap of about an eighth of the globe between them, the pair centred on the screen both ways.
    static final String WORD = "radOS";
    static final float WORD_DP = 72, WORD_BOTTOM_DP = 47.5f, GLOBE_DP = 122, GAP_DP = 16;   // GLOBE_DP: the globe itself inside the 134 dp video
    private float groupLeft(float W) { return W / 2 - (GLOBE_DP * d + GAP_DP * d + word.measureText(WORD)) / 2; }
    private float globeX(float W) { return groupLeft(W) + GLOBE_DP * d / 2; }

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight(), cx = W / 2, cy = H / 2 + 10 * d;
        float out = smooth(phase(EXIT_AT, EXIT_AT + 1000));           // the logo and word ease back and fade, slowly
        float bgOut = smooth(phase(EXIT_AT + 400, LENGTH));         // the black dissolves, overlapping, into the home screen
        // Fade in with the chime: Bloom's chord swells in over about 1.5 s on a squared curve, so the logo
        // and words follow the same curve (quiet start, fuller toward the end of the swell).
        float swell = smooth(t / 1900f);                 // eased in, matching the chime's swell
        float alpha = (1f - out);
        float shown = swell * alpha;

        // background: near black with a violet and pink bloom that opens and breathes
        bg.setColor(Color.argb((int) (255 * alpha), 0, 0, 0));
        if (backdrop != null) backdrop.setAlpha(1f - bgOut); else { bg.setAlpha((int) (255 * (1f - bgOut))); c.drawRect(0, 0, W, H, bg); }
        if (home != null) { float z = 1.035f - 0.035f * bgOut; home.setScaleX(z); home.setScaleY(z); }
        float open = 1f;                                 // no intro: everything is simply there
        float breathe = 1f + 0.06f * (float) Math.sin(t / 700.0);
        float gr = (260 + 160 * open) * d * breathe;
        glow.setShader(new RadialGradient(cx, cy, gr,
                new int[]{Color.argb((int) (150 * open * alpha), 124, 58, 237), Color.argb((int) (70 * open * alpha), 255, 61, 129), 0},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        // (no glow: a flat black background)

        // the globe: grows in with a little overshoot, a slow turn, lifts on exit
        float s = 1f - 0.06f * out;
        float size = 115 * d * s;
        float gy = cy - 44 * d - 20 * d * out;
        // The globe is the loader: it spins the whole time. With Astra's frames it turns for real;
        // without them it turns like a 3D coin (squeezed sideways as it goes round).
        img.setAlpha((int) (255 * shown));
        android.graphics.RectF box = new android.graphics.RectF(cx - size / 2, gy - size / 2, cx + size / 2, gy + size / 2);
        double turn = t / 1600.0 * 2 * Math.PI;                    // one turn every 1.6 s
        if (video != null) {
            video.setAlpha(videoReady ? shown : 0f);
            video.setScaleX(s); video.setScaleY(s);
            video.setTranslationY(0);
        } else if (frames != null) {
            int k = (int) Math.floor((t / 3000.0 * frames.length)) % frames.length;   // one turn every 3 s
            c.drawBitmap(frames[k], null, box, img);
        } else if (globe != null) {
            // no 3D frames yet: the globe floats gently instead of a flat flip
            c.save();
            c.translate(0, 6 * d * (float) Math.sin(t / 500.0));
            c.drawBitmap(globe, null, box, img);
            c.restore();
        }

        // the word, its bottom a little above the globe's bottom edge (61.5 dp below centre)
        android.graphics.Rect ink = new android.graphics.Rect();
        word.getTextBounds(WORD, 0, WORD.length(), ink);
        float x = groupLeft(W) + GLOBE_DP * d + GAP_DP * d, base = H / 2 + WORD_BOTTOM_DP * d - ink.bottom;
        c.save();
        c.scale(1f - 0.06f * out, 1f - 0.06f * out, cx, H / 2);
        word.setAlpha((int) (255 * shown));
        c.drawText(WORD, x - ink.left, base, word);
        c.restore();

    }
}
