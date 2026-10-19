package com.zoro.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV home screen, Apple TV style: greeting, clock and weather on top; a scores wheel, an Up Next
 * calendar card and a rotating scenic photo; one row of wide app tiles. The wallpaper is the current
 * photo, blurred. Everything is fetched by the TV itself, so it works with the Mac off.
 *
 * Optional files in getExternalFilesDir(null), pushed once over adb:
 *   config.json   {"place":"Denver","lat":39.74,"lon":-104.99,"ics":["https://...ics"]}
 *   photos/*.jpg  own photos, shown instead of the scenic set
 *   calendar.json events pushed from the Mac (used alongside any ics links)
 */
public class HomeActivity extends Activity {

    private static final int TEXT = Color.WHITE;
    private static final int DIM = 0x99FFFFFF;
    private static final int FAINT = 0x66FFFFFF;
    private static final int ACCENT = 0xFF0A84FF;
    private static final int LIVE = 0xFFFF453A;

    private static final List<String> PINNED = Arrays.asList(
            "com.zoro.rad",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.google.android.youtube.tv",
            "com.apple.atve.androidtv.appletv",
            "com.crunchyroll.crunchyroid",
            "org.mozilla.firefox",
            "com.phlox.tvwebbrowser");
    private static final List<String> TRAILING = Arrays.asList(
            "com.android.vending",
            "com.android.tv.settings");

    private static final String[][] LEAGUES = {
            {"NFL", "football/nfl"},
            {"NBA", "basketball/nba"},
            {"MLB", "baseball/mlb"},
            {"NHL", "hockey/nhl"},
            {"NCAAF", "football/college-football"},
    };

    private static final long PHOTO_MS = 10000;
    private static final long SCORE_REFRESH_MS = 60000;
    private static final long SLOW_REFRESH_MS = 15 * 60000;

    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newFixedThreadPool(3);
    private final ExecutorService net = Executors.newFixedThreadPool(8);
    private final Map<String, Bitmap> logoCache = Collections.synchronizedMap(new HashMap<>());

    Typeface sans, sansMedium, sansLight, mono;

    private Crossfade wallpaper, photoView;
    private TextView greeting, dateLine, weatherNow, weatherDays, photoCaption, agendaEmpty;
    private LinearLayout agenda, appRow;
    private ScoreWheel wheel;

    private List<File> photos = new ArrayList<>();
    private int photoIndex = -1;
    private String appSignature = "";
    private View homeRoot, gear;
    private TextClock clock;
    private SettingsPanel settings;
    final List<String[]> allApps = new ArrayList<>();   // {package, label} of every app, hidden or not
    private final Map<String, Integer> widgetSlots = new HashMap<>();

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        BootLog.mark("home create");
        sans = Typeface.create("sans-serif", Typeface.NORMAL);
        sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        sansLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        mono = Typeface.MONOSPACE;
        View content = build();
        BootLog.mark("home built views");
        setContentView(content);
        BootLog.mark("home content set");
        // radOS startup screen: once per power on, while the home screen loads behind it.
        int boot = android.provider.Settings.Global.getInt(getContentResolver(), "boot_count", -1);
        SharedPreferences sp = getSharedPreferences("home", MODE_PRIVATE);
        boolean fresh = android.os.SystemClock.elapsedRealtime() < 3 * 60 * 1000L && sp.getInt("splashBoot", -2) != boot;
        long bootAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        long bootSplashAt = 0;
        try { bootSplashAt = createDeviceProtectedStorageContext().getSharedPreferences("boot", MODE_PRIVATE).getLong("bootSplashAt", 0); }
        catch (Exception ignored) { }
        boolean splashing = true;
        long splashFrom = 0;
        boolean splashOn = createDeviceProtectedStorageContext().getSharedPreferences("boot", MODE_PRIVATE).getBoolean("splashOn", true);
        long sinceBoot = System.currentTimeMillis() - bootSplashAt;
        if (!splashOn) {
            // startup animation switched off in Settings
            sp.edit().putInt("splashBoot", boot).apply();
            splashing = false;
        } else if (BootSplash.showing || (!getIntent().getBooleanExtra("reveal", false) && bootSplashAt >= bootAt && sinceBoot < Splash.LENGTH)) {
            // Android brought the home screen up while the startup animation was still playing: carry on
            // from the same moment, so the switch cannot be seen.
            BootSplash.homeTookOver = true;
            sp.edit().putInt("splashBoot", boot).apply();
            Splash s3 = new Splash(this);
            s3.startAt = Math.max(0, Math.min(Splash.LENGTH - 100, sinceBoot));
            splashFrom = s3.startAt;
            s3.sound = false;
            BootLog.mark("home splash made");
            s3.play((ViewGroup) getWindow().getDecorView());
            BootLog.mark("home splash playing");
        } else if (getIntent().getBooleanExtra("reveal", false)) {
            // the boot splash played during startup: finish its exit over the real home screen
            sp.edit().putInt("splashBoot", boot).apply();
            Splash s2 = new Splash(this);
            s2.startAt = Splash.EXIT_AT;
            // the boot video may have run on past the handover while the unlock finished: carry on from its frame
            if (sinceBoot > Splash.EXIT_AT && sinceBoot < Splash.LENGTH) s2.videoFrom = Math.min(5700, sinceBoot);
            s2.sound = false;
            splashFrom = s2.startAt;
            s2.play((ViewGroup) getWindow().getDecorView());
        } else if ((fresh && bootSplashAt < bootAt) || getIntent().getBooleanExtra("splash", false)) {
            sp.edit().putInt("splashBoot", boot).apply();
            new Splash(this).play((ViewGroup) getWindow().getDecorView());
        } else splashing = false;
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        // Building the app tiles is the heaviest step: while the startup animation plays, do it just before the
        // reveal instead of at once, so the spinning globe never stutters.
        long tilesAt = splashing ? Math.max(0, Splash.EXIT_AT + 200 - splashFrom) : 0;
        tilesNotBefore = android.os.SystemClock.uptimeMillis() + tilesAt;
        main.postDelayed(() -> { loadApps(); if (getCurrentFocus() == null) focusFirstTile(); }, tilesAt);
        main.postDelayed(() -> io.execute(this::showCached), tilesAt);
        // While the radOS screen plays, keep the network and parsing work for afterwards, so it stays smooth.
        long wait = splashing ? 5500 : 0;
        BootLog.mark("home create done");
        main.postDelayed(BootLog::dumpOnce, 9000);
        main.postDelayed(scoreTick, wait);
        main.postDelayed(slowTick, wait);
    }

    /** Lets the radOS screen be replayed on a running home screen (for previews), no restart needed. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("splash", false)) new Splash(this).play((ViewGroup) getWindow().getDecorView());
        if (intent != null && intent.getBooleanExtra("reveal", false) && !BootSplash.homeTookOver) {
            Splash s2 = new Splash(this); s2.startAt = Splash.EXIT_AT; s2.sound = false; s2.play((ViewGroup) getWindow().getDecorView());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Google's own home screen stays loaded in the background (about 300 MB) though ours is the one shown.
        // Once ours is up, let Android reclaim it; it comes back by itself if anything ever needs it.
        main.postDelayed(() -> {
            try {
                getSystemService(android.app.ActivityManager.class)
                        .killBackgroundProcesses("com.google.android.apps.tv.launcherx");
            } catch (Exception ignored) { }
        }, 4000);
        updateGreeting();
        if (android.os.SystemClock.uptimeMillis() >= tilesNotBefore) {
            loadApps();
            if (getCurrentFocus() == null) focusFirstTile();
        }
    }
    private long tilesNotBefore;      // while the startup animation plays, tiles wait (see onCreate)

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { }

    // ---------------------------------------------------------------- movable widgets

    private FrameLayout widgetLayer;
    private View moveDim;
    private TextView moveHint;
    private final List<View> widgets = new ArrayList<>();
    private View moving;
    private float moveFromX, moveFromY;
    private static final float W_SCALE = 0.74f;

    /** A widget: drawn at full size and shown at 74%, so the text stays crisp; position saved per widget. */
    private void addWidget(String id, View v, int slot) {
        widgetSlots.put(id, slot);
        v.setTag(id);
        v.setFocusable(true);
        v.setPivotX(0);
        v.setPivotY(0);
        v.setScaleX(W_SCALE);
        v.setScaleY(W_SCALE);
        int w = dp(300), h = dp(150);
        float sw = getResources().getDisplayMetrics().widthPixels;
        float dx = sw - dp(48) - w * W_SCALE, dy = dp(116) + slot * (h * W_SCALE + dp(10));
        SharedPreferences p = getSharedPreferences("home", MODE_PRIVATE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w * W_SCALE), Math.round(h * W_SCALE), Gravity.TOP | Gravity.START);
        lp.leftMargin = Math.round(p.contains("w." + id + ".x") ? dp(p.getFloat("w." + id + ".x", 0)) : dx);
        lp.topMargin = Math.round(p.contains("w." + id + ".y") ? dp(p.getFloat("w." + id + ".y", 0)) : dy);
        // The view is laid out at its shown size, and its full size content is scaled into it.
        FrameLayout holder = new FrameLayout(this);
        holder.setClipChildren(false);
        v.setLayoutParams(new FrameLayout.LayoutParams(w, h));
        holder.addView(v);
        widgetLayer.addView(holder, lp);
        v.setFocusable(false);
        holder.setFocusable(true);
        holder.setTag(id);
        final GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(dp(22));
        ring.setStroke(dp(3), Color.WHITE);
        ring.setAlpha(0);
        v.setForeground(ring);
        holder.setOnFocusChangeListener((x, has) -> ring.setAlpha(has || x == moving ? 255 : 0));
        widgets.add(holder);
        holder.setVisibility(p.getBoolean("w." + id + ".hidden", false) ? View.GONE : View.VISIBLE);
    }

    void applyWidgetVisibility() {
        SharedPreferences p = getSharedPreferences("home", MODE_PRIVATE);
        for (View h : widgets) h.setVisibility(p.getBoolean("w." + h.getTag() + ".hidden", false) ? View.GONE : View.VISIBLE);
    }

    /** Settings > Reset layout: widgets back to their default places, app row back to the usual order. */
    void resetLayout() {
        SharedPreferences.Editor ed = getSharedPreferences("home", MODE_PRIVATE).edit();
        ed.remove("appOrder");
        float sw = getResources().getDisplayMetrics().widthPixels;
        for (View h : widgets) {
            String id = (String) h.getTag();
            ed.remove("w." + id + ".x").remove("w." + id + ".y");
            int slot = widgetSlots.containsKey(id) ? widgetSlots.get(id) : 0;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.getLayoutParams();
            lp.leftMargin = Math.round(sw - dp(48) - dp(300) * W_SCALE);
            lp.topMargin = Math.round(dp(116) + slot * (dp(150) * W_SCALE + dp(10)));
            h.setLayoutParams(lp);
        }
        ed.apply();
        appSignature = "";
        loadApps();
    }

    private final Runnable holdToMove = () -> {
        View f = getCurrentFocus();
        if (f != null && widgets.contains(f)) startMove(f);
        else if (f != null && f.getParent() instanceof View && ((View) f.getParent()).getParent() == appRow) startAppMove((View) f.getParent());
    };

    // ---- moving an app along the row: hold OK on it, Left and Right to slide, OK to drop, Back to cancel
    private View movingApp;
    private int appFrom;
    private boolean releaseAfterHold;   // the OK release that ends the hold must not also drop the item

    private void startAppMove(View col) {
        movingApp = col;
        releaseAfterHold = true;
        ((ViewGroup) col).getChildAt(0).setPressed(false);
        appFrom = appRow.indexOfChild(col);
        col.animate().translationY(-dp(10)).setDuration(150).start();
        moveHint.setText("Slide it with Left and Right  \u00B7  OK to drop  \u00B7  Back to cancel");
        moveHint.setVisibility(View.VISIBLE);
        moveHint.bringToFront();
    }

    private void shiftApp(int dir) {
        int i = appRow.indexOfChild(movingApp), j = i + dir;
        if (j < 0 || j >= appRow.getChildCount()) return;
        appRow.removeView(movingApp);
        appRow.addView(movingApp, j);
        ((ViewGroup) movingApp).getChildAt(0).requestFocus();
    }

    private void endAppMove(boolean keep) {
        View col = movingApp;
        movingApp = null;
        moveHint.setVisibility(View.GONE);
        moveHint.setText("Move it with the arrows  \u00B7  OK to place  \u00B7  Back to cancel");
        if (col == null) return;
        if (!keep) { appRow.removeView(col); appRow.addView(col, appFrom); }
        col.animate().translationY(0).setDuration(150).start();
        ((ViewGroup) col).getChildAt(0).requestFocus();
        if (keep) {
            StringBuilder order = new StringBuilder();
            for (int i = 0; i < appRow.getChildCount(); i++) {
                Object pkg = appRow.getChildAt(i).getTag();
                if (pkg != null) order.append(pkg).append(',');
            }
            getSharedPreferences("home", MODE_PRIVATE).edit().putString("appOrder", order.toString()).apply();
        }
    }

    private void startMove(View v) {
        moving = v;
        releaseAfterHold = true;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        moveFromX = lp.leftMargin;
        moveFromY = lp.topMargin;
        moveDim.setVisibility(View.VISIBLE);
        moveDim.setAlpha(0f);
        moveDim.animate().alpha(1f).setDuration(180).start();
        moveHint.setVisibility(View.VISIBLE);
        v.bringToFront();
        moveHint.bringToFront();
        v.animate().scaleX(1.06f).scaleY(1.06f).translationZ(dp(20)).setDuration(160).start();
    }

    private void endMove(boolean keep) {
        View v = moving;
        moving = null;
        if (v == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        if (!keep) { lp.leftMargin = Math.round(moveFromX); lp.topMargin = Math.round(moveFromY); v.setLayoutParams(lp); }
        else {
            float d = getResources().getDisplayMetrics().density;
            getSharedPreferences("home", MODE_PRIVATE).edit()
                    .putFloat("w." + v.getTag() + ".x", lp.leftMargin / d)
                    .putFloat("w." + v.getTag() + ".y", lp.topMargin / d).apply();
        }
        moveDim.animate().alpha(0f).setDuration(160).withEndAction(() -> moveDim.setVisibility(View.GONE)).start();
        moveHint.setVisibility(View.GONE);
        v.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(160).start();
        v.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (settings != null && settings.isOpen()) return settings.key(e) || super.dispatchKeyEvent(e);
        if ((moving != null || movingApp != null) && ok && !down && releaseAfterHold) {
            releaseAfterHold = false;
            return true;
        }
        if (moving != null) {
            if (down && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
                float step = dp(10 + Math.min(e.getRepeatCount(), 12) * 3);
                FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) moving.getLayoutParams();
                float x = mlp.leftMargin, y = mlp.topMargin;
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) x -= step;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) x += step;
                if (code == KeyEvent.KEYCODE_DPAD_UP) y -= step;
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) y += step;
                float maxX = widgetLayer.getWidth() - moving.getWidth() - dp(12);
                float maxY = widgetLayer.getHeight() - moving.getHeight() - dp(12);
                mlp.leftMargin = Math.round(Math.max(dp(12), Math.min(maxX, x)));
                mlp.topMargin = Math.round(Math.max(dp(12), Math.min(maxY, y)));
                moving.setLayoutParams(mlp);
            } else if (!down && ok) endMove(true);
            else if (!down && code == KeyEvent.KEYCODE_BACK) endMove(false);
            return true;
        }
        if (movingApp != null) {
            if (down && code == KeyEvent.KEYCODE_DPAD_LEFT) shiftApp(-1);
            else if (down && code == KeyEvent.KEYCODE_DPAD_RIGHT) shiftApp(1);
            else if (!down && ok) endAppMove(true);
            else if (!down && code == KeyEvent.KEYCODE_BACK) endAppMove(false);
            return true;
        }
        View f = getCurrentFocus();
        // Up with nothing above reaches the settings gear
        if (down && code == KeyEvent.KEYCODE_DPAD_UP && f != null && f != gear && f.focusSearch(View.FOCUS_UP) == null) {
            gear.requestFocus();
            return true;
        }
        boolean onApp = f != null && f.getParent() instanceof View && ((View) f.getParent()).getParent() == appRow;
        if (ok && f != null && onApp) {
            // a quick press opens the app as before; holding starts a move
            if (down && e.getRepeatCount() == 0) { main.postDelayed(holdToMove, 550); return super.dispatchKeyEvent(e) || true; }
            if (down) return true;
            boolean wasMove = movingApp != null;
            main.removeCallbacks(holdToMove);
            if (wasMove) return true;
            return super.dispatchKeyEvent(e);
        }
        if (ok && f != null && widgets.contains(f)) {
            if (down && e.getRepeatCount() == 0) main.postDelayed(holdToMove, 550);
            else if (!down) main.removeCallbacks(holdToMove);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- layout

    private View build() {
        FrameLayout root = new FrameLayout(this);
        homeRoot = root;
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        View base = new View(this);
        base.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B1F3A, 0xFF0E0F1A, 0xFF241430}));
        root.addView(base, match());
        root.addView(aurora(), match());

        // Wallpaper: the current photo, heavily blurred and dimmed.
        wallpaper = new Crossfade(this, false);
        // No blur: the photo is shown sharp, like a real wallpaper.
        root.addView(wallpaper, match());
        View dim = new View(this);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x14000000, 0x00000000, 0x40000000, 0xC7000000}));
        root.addView(dim, match());

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(28), dp(48), dp(16));
        page.setClipChildren(false);
        page.setClipToPadding(false);
        root.addView(page, match());

        // Top bar: greeting and date left; clock and weather right.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        greeting = text(30, sansMedium, TEXT);
        dateLine = text(14, sans, DIM);
        dateLine.setPadding(0, dp(5), 0, 0);
        titles.addView(greeting);
        titles.addView(dateLine);
        top.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        LinearLayout clockLine = new LinearLayout(this);
        clockLine.setOrientation(LinearLayout.HORIZONTAL);
        clockLine.setGravity(Gravity.CENTER_VERTICAL);
        SettingsPanel.Gear g = new SettingsPanel.Gear(this);
        gear = g;
        g.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(40), dp(40));
        gl.rightMargin = dp(20);
        clockLine.addView(g, gl);
        weatherNow = text(22, sansLight, TEXT);
        LinearLayout.LayoutParams wnl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wnl.rightMargin = dp(16);
        clockLine.addView(weatherNow, wnl);
        View sep = new View(this);
        sep.setBackgroundColor(0x40FFFFFF);
        LinearLayout.LayoutParams sepl = new LinearLayout.LayoutParams(dp(1), dp(26));
        sepl.rightMargin = dp(16);
        clockLine.addView(sep, sepl);
        clock = new TextClock(this);
        applyClock();
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        clock.setTypeface(sansLight);
        clock.setTextColor(TEXT);
        clock.setIncludeFontPadding(false);
        clockLine.addView(clock);
        right.addView(clockLine);
        weatherDays = text(13, sans, DIM);
        weatherDays.setGravity(Gravity.END);
        weatherDays.setPadding(0, dp(6), 0, 0);
        right.addView(weatherDays);
        top.addView(right);
        page.addView(top);
        updateGreeting();

        // Cards.
        // Widgets float on their own layer: small, top right by default, and movable (hold OK on one).
        widgetLayer = new FrameLayout(this);
        widgetLayer.setClipChildren(false);
        moveDim = new View(this);
        moveDim.setBackgroundColor(0x8C000000);
        moveDim.setVisibility(View.GONE);
        widgetLayer.addView(moveDim, match());

        LinearLayout scores = card();
        scores.addView(cardTitle("SCORES"));
        wheel = new ScoreWheel(this);
        scores.addView(wheel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        addWidget("scores", scores, 0);

        LinearLayout up = card();
        up.addView(cardTitle("UP NEXT"));
        agenda = new LinearLayout(this);
        agenda.setOrientation(LinearLayout.VERTICAL);
        up.addView(agenda, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        agendaEmpty = text(14, sans, FAINT);
        agendaEmpty.setText("Loading");
        agenda.addView(agendaEmpty);
        addWidget("agenda", up, 1);

        FrameLayout photo = new FrameLayout(this);
        photo.setBackground(cardBg());
        rounded(photo, 22);
        photoView = new Crossfade(this, true);
        photo.addView(photoView, match());
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, 0x99000000}));
        photo.addView(scrim, match());
        photoCaption = text(12, sansMedium, 0xE6FFFFFF);
        photoCaption.setSingleLine(true);
        photoCaption.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams cap = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cap.setMargins(dp(18), 0, dp(18), dp(14));
        photo.addView(photoCaption, cap);
        // No photo card: the wallpaper is the photo. (photoView stays, unattached, so the photo code is unchanged.)

        moveHint = text(15, sansMedium, TEXT);
        moveHint.setText("Move it with the arrows  \u00B7  OK to place  \u00B7  Back to cancel");
        moveHint.setPadding(dp(22), dp(10), dp(22), dp(10));
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xCC1C1C22);
        hb.setStroke(dp(1), 0x40FFFFFF);
        hb.setCornerRadius(dp(22));
        moveHint.setBackground(hb);
        moveHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams mhl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        mhl.bottomMargin = dp(30);
        widgetLayer.addView(moveHint, mhl);

        page.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));

        // App row.
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setClipChildren(false);
        appRow.setClipToPadding(false);
        appRow.setPadding(dp(4), dp(12), dp(24), dp(4));
        scroller.addView(appRow);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(146));
        sl.leftMargin = -dp(4);
        page.addView(scroller, sl);
        root.addView(widgetLayer, match());
        return root;
    }

    void updateGreeting() {
        if (greeting == null) return;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String name = getSharedPreferences("home", MODE_PRIVATE).getString("name", "");
        greeting.setText((h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening")
                + (name.isEmpty() ? "" : ", " + name));
        dateLine.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date()));
    }

    // ---------------------------------------------------------------- aurora

    /** Four big soft color glows drifting slowly behind everything. */
    private View aurora() {
        FrameLayout f = new FrameLayout(this);
        int[][] blobs = {
                {0xFF4B3BD6, -120, -160, 60, 40, 21000},
                {0xFF0F8F9C, 520, 220, 70, -50, 26000},
                {0xFFB0307A, 640, -220, -60, 60, 23000},
                {0xFF1E5BD8, -60, 300, 80, -40, 29000},
        };
        for (int[] b : blobs) {
            View v = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            g.setGradientRadius(dp(330));
            g.setColors(new int[]{(b[0] & 0x00FFFFFF) | 0xCC000000, b[0] & 0x00FFFFFF});
            v.setBackground(g);
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            f.addView(v, new FrameLayout.LayoutParams(dp(660), dp(660)));
            android.animation.ObjectAnimator ax = android.animation.ObjectAnimator.ofFloat(v, "translationX", dp(b[1]), dp(b[1] + b[3] * 3));
            android.animation.ObjectAnimator ay = android.animation.ObjectAnimator.ofFloat(v, "translationY", dp(b[2]), dp(b[2] + b[4] * 3));
            for (android.animation.ObjectAnimator a : new android.animation.ObjectAnimator[]{ax, ay}) {
                a.setDuration(b[5]);
                a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                a.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                a.start();
            }
        }
        return f;
    }

    // ---------------------------------------------------------------- apps

    void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> byPkg = new HashMap<>();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            String p = r.activityInfo.packageName;
            if (p.equals(getPackageName()) || byPkg.containsKey(p)) continue;
            byPkg.put(p, r);
        }
        // Phone apps installed on purpose (Firefox) have no TV entry; include the pinned ones.
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            String p = r.activityInfo.packageName;
            if (PINNED.contains(p) && !byPkg.containsKey(p)) byPkg.put(p, r);
        }
        LinkedHashSet<String> order = new LinkedHashSet<>();
        // The user's own order (hold OK on an app to move it) comes first; new apps follow the usual order.
        for (String p : getSharedPreferences("home", MODE_PRIVATE).getString("appOrder", "").split(","))
            if (!p.isEmpty() && byPkg.containsKey(p)) order.add(p);
        for (String p : PINNED) if (byPkg.containsKey(p)) order.add(p);
        List<String> rest = new ArrayList<>();
        for (String p : byPkg.keySet()) if (!PINNED.contains(p) && !TRAILING.contains(p)) rest.add(p);
        Collections.sort(rest);
        order.addAll(rest);
        for (String p : TRAILING) if (byPkg.containsKey(p)) order.add(p);

        allApps.clear();
        for (String p : order) allApps.add(new String[]{p, appLabel(pm, byPkg.get(p))});
        java.util.Set<String> hidden = new java.util.HashSet<>(Arrays.asList(
                getSharedPreferences("home", MODE_PRIVATE).getString("hiddenApps", "").split(",")));
        order.removeAll(hidden);

        // Include each app's update time, so a new icon or banner shows up after an app updates.
        StringBuilder sb = new StringBuilder(TextUtils.join(",", order));
        for (String p : order) {
            try { sb.append(pm.getPackageInfo(p, 0).lastUpdateTime); } catch (Exception ignored) { }
        }
        String sig = sb.toString();
        if (sig.equals(appSignature)) return;
        appSignature = sig;
        appRow.removeAllViews();
        for (String p : order) appRow.addView(tile(pm, byPkg.get(p)));
        focusFirstTile();
    }

    private static String appLabel(PackageManager pm, ResolveInfo r) {
        String p = r.activityInfo.packageName;
        if (p.equals("com.android.vending")) return "Get Apps";
        if (p.equals("com.android.tv.settings")) return "Settings";
        return String.valueOf(r.loadLabel(pm));
    }

    private void focusFirstTile() {
        if (appRow.getChildCount() == 0) return;
        ((ViewGroup) appRow.getChildAt(0)).getChildAt(0).requestFocus();
    }

    private View tile(PackageManager pm, ResolveInfo r) {
        final ActivityInfo ai = r.activityInfo;
        String label = String.valueOf(r.loadLabel(pm));
        if (ai.packageName.equals("com.android.vending")) label = "Get Apps";
        if (ai.packageName.equals("com.android.tv.settings")) label = "Settings";

        LinearLayout col = new LinearLayout(this);
        col.setTag(ai.packageName);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(dp(156), ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.rightMargin = dp(20);
        col.setLayoutParams(colLp);

        final FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2B33);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        rounded(card, 14);

        Drawable banner = ai.loadBanner(pm);
        if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
        ImageView img = new ImageView(this);
        if (banner != null) {
            img.setImageDrawable(banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, match());
        } else {
            img.setImageDrawable(ai.loadIcon(pm));
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        }
        // A soft band of light that sweeps across the tile when it gains focus.
        final View shine = new View(this);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}));
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(card, new LinearLayout.LayoutParams(dp(156), dp(88)));

        final TextView name = text(13, sansMedium, TEXT);
        name.setText(label);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setAlpha(0f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(14);
        col.addView(name, nlp);

        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.1f : 1f;
            if (has) {
                shine.setTranslationX(-dp(70));
                shine.setAlpha(1f);
                shine.animate().translationX(dp(156)).setStartDelay(120).setDuration(650)
                        .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> shine.setAlpha(0f)).start();
            }
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(18) : 0)
                    .setDuration(220).setInterpolator(new DecelerateInterpolator(2f)).start();
            name.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
        });
        card.setOnClickListener(v -> {
            // instant feedback: the tile presses in the moment OK is pressed, while the app starts
            v.animate().cancel();
            v.setScaleX(1.02f); v.setScaleY(1.02f);
            v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(160).start();
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(ai.packageName);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(ai.packageName);
            if (launch == null) launch = new Intent(Intent.ACTION_MAIN).setClassName(ai.packageName, ai.name);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(launch); } catch (Exception ignored) { }
        });
        return col;
    }

    // ---------------------------------------------------------------- scores

    private static class Game {
        String league, state, status;
        long time;
        String awayAbbr, homeAbbr, awayScore, homeScore, awayLogo, homeLogo;
    }

    private final Runnable scoreTick = new Runnable() {
        @Override public void run() {
            io.execute(HomeActivity.this::fetchScores);
            main.postDelayed(this, SCORE_REFRESH_MS);
        }
    };

    private void fetchScores() {
        List<Game> all = new ArrayList<>();
        Calendar today = Calendar.getInstance();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmX", Locale.US);
        // Fetch every league at once rather than one after another.
        List<java.util.concurrent.Future<String>> bodies = new ArrayList<>();
        for (String[] lg : LEAGUES) {
            bodies.add(net.submit(() -> get("https://site.api.espn.com/apis/site/v2/sports/" + lg[1] + "/scoreboard")));
        }
        for (int li = 0; li < LEAGUES.length; li++) {
            String[] lg = LEAGUES[li];
            try {
                String body = bodies.get(li).get();
                JSONArray events = new JSONObject(body).optJSONArray("events");
                if (events == null) continue;
                for (int i = 0; i < events.length(); i++) {
                    JSONObject ev = events.getJSONObject(i);
                    JSONObject comp = ev.getJSONArray("competitions").getJSONObject(0);
                    JSONObject st = comp.optJSONObject("status");
                    if (st == null) st = ev.getJSONObject("status");
                    JSONObject type = st.getJSONObject("type");
                    Game g = new Game();
                    g.league = lg[0];
                    g.state = type.optString("state");
                    g.status = type.optString("shortDetail");
                    try { g.time = iso.parse(ev.getString("date")).getTime(); } catch (Exception e) { g.time = 0; }
                    Calendar c = Calendar.getInstance();
                    c.setTimeInMillis(g.time);
                    boolean isToday = c.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                            && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
                    if (!"in".equals(g.state) && !isToday) continue;
                    JSONArray teams = comp.getJSONArray("competitors");
                    boolean ranked = false;
                    for (int t = 0; t < teams.length(); t++) {
                        JSONObject tm = teams.getJSONObject(t);
                        JSONObject team = tm.getJSONObject("team");
                        String abbr = team.optString("abbreviation", team.optString("shortDisplayName"));
                        String logo = team.optString("logo", "");
                        String score = tm.optString("score", "");
                        if ("home".equals(tm.optString("homeAway"))) {
                            g.homeAbbr = abbr; g.homeLogo = logo; g.homeScore = score;
                        } else {
                            g.awayAbbr = abbr; g.awayLogo = logo; g.awayScore = score;
                        }
                        JSONObject cr = tm.optJSONObject("curatedRank");
                        if (cr != null && cr.optInt("current", 99) <= 25) ranked = true;
                    }
                    // College football has dozens of games a day: only ranked or live ones.
                    if (lg[0].equals("NCAAF") && !"in".equals(g.state) && !ranked) continue;
                    all.add(g);
                }
            } catch (Exception ignored) { }
        }
        Collections.sort(all, (a, b) -> {
            int ra = rank(a.state), rb = rank(b.state);
            if (ra != rb) return ra - rb;
            return Long.compare(a.time, b.time);
        });
        List<java.util.concurrent.Future<?>> logos = new ArrayList<>();
        for (Game g : all) {
            logos.add(net.submit(() -> prefetchLogo(g.awayLogo)));
            logos.add(net.submit(() -> prefetchLogo(g.homeLogo)));
        }
        for (java.util.concurrent.Future<?> f : logos) { try { f.get(); } catch (Exception ignored) { } }
        saveGames(all);
        main.post(() -> wheel.setGames(all));
    }

    private static int rank(String state) {
        return "in".equals(state) ? 0 : "pre".equals(state) ? 1 : 2;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    /** One game: two stacked team lines on the left, status and league on the right. */
    private View gameRow(Game g) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout teams = new LinearLayout(this);
        teams.setOrientation(LinearLayout.VERTICAL);
        boolean pre = "pre".equals(g.state), post = "post".equals(g.state);
        int a = num(g.awayScore), h = num(g.homeScore);
        teams.addView(teamLine(g.awayLogo, g.awayAbbr, pre ? "" : g.awayScore, post && a < h));
        teams.addView(teamLine(g.homeLogo, g.homeAbbr, pre ? "" : g.homeScore, post && h < a));
        row.addView(teams, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        LinearLayout statusLine = new LinearLayout(this);
        statusLine.setOrientation(LinearLayout.HORIZONTAL);
        statusLine.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        boolean live = "in".equals(g.state);
        if (live) {
            View dot = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(LIVE);
            dot.setBackground(d);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
            dlp.rightMargin = dp(6);
            statusLine.addView(dot, dlp);
        }
        TextView status = text(13, sansMedium, live ? TEXT : 0xB3FFFFFF);
        status.setText(statusText(g));
        statusLine.addView(status);
        right.addView(statusLine);
        TextView league = text(11, sans, FAINT);
        league.setText(g.league);
        league.setGravity(Gravity.END);
        league.setPadding(0, dp(4), 0, 0);
        right.addView(league);
        row.addView(right);
        return row;
    }

    private View teamLine(String logo, String abbr, String score, boolean lost) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(1), dp(14), dp(1));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap b = logo == null ? null : logoCache.get(logo);
        if (b != null) iv.setImageBitmap(b);
        line.addView(iv, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView name = text(15, sansMedium, lost ? 0x80FFFFFF : TEXT);
        name.setText(abbr == null ? "" : abbr);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nlp.leftMargin = dp(9);
        line.addView(name, nlp);
        TextView sc = text(15, sansMedium, lost ? 0x80FFFFFF : TEXT);
        sc.setText(score == null ? "" : score);
        line.addView(sc);
        return line;
    }

    /**
     * The scores wheel: games sit on a drum that turns one game at a time. The game in the middle is
     * full size; the ones above and below tilt away and fade, like a picker wheel.
     */
    private class ScoreWheel extends FrameLayout {
        private final List<View> rows = new ArrayList<>();
        private final TextView empty;
        private float offset = 0f;
        private android.animation.ValueAnimator spin;
        private final Runnable turn = this::turnOnce;

        ScoreWheel(Context ctx) {
            super(ctx);
            setClipChildren(true);
            empty = text(14, sans, FAINT);
            empty.setText("Loading scores");
            addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        }

        private int rowH() { return dp(44); }

        boolean hasGames() { return !rows.isEmpty(); }

        void setGames(List<Game> games) {
            for (View v : rows) removeView(v);
            rows.clear();
            main.removeCallbacks(turn);
            if (spin != null) spin.cancel();
            if (games.isEmpty()) {
                empty.setText("No games today");
                empty.setVisibility(VISIBLE);
                return;
            }
            empty.setVisibility(GONE);
            for (Game g : games) {
                View r = gameRow(g);
                addView(r, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH()));
                rows.add(r);
            }
            if (offset >= rows.size()) offset = 0;
            offset = (float) Math.floor(offset);
            post(this::place);
            if (rows.size() > 1) main.postDelayed(turn, 3000);
        }

        private void turnOnce() {
            final float from = offset;
            spin = android.animation.ValueAnimator.ofFloat(0f, 1f);
            spin.setDuration(750);
            spin.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            spin.addUpdateListener(a -> {
                offset = from + (float) a.getAnimatedValue();
                place();
            });
            spin.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    offset = (from + 1) % rows.size();
                    place();
                    main.postDelayed(turn, 2800);
                }
            });
            spin.start();
        }

        /** Lays each row on the drum by its distance from the middle. */
        private void place() {
            int n = rows.size();
            if (n == 0) return;
            float mid = getHeight() / 2f - rowH() / 2f;
            for (int i = 0; i < n; i++) {
                float d = i - offset;
                // wrap to the nearest copy so the drum loops
                if (n > 2) {
                    while (d > n / 2f) d -= n;
                    while (d < -n / 2f) d += n;
                }
                float ad = Math.abs(d);
                View v = rows.get(i);
                if (ad > 1.6f) { v.setAlpha(0f); continue; }   // three games show in the smaller card
                float y = (float) (Math.sin(Math.max(-1.45, Math.min(1.45, d * 0.62))) * rowH() * 1.45);
                v.setTranslationY(mid + y);
                v.setRotationX(-d * 24f);
                v.setPivotY(rowH() / 2f);
                v.setCameraDistance(getResources().getDisplayMetrics().density * 9000);
                float s = 1f - Math.min(0.2f, ad * 0.09f);
                v.setScaleX(s);
                v.setScaleY(s);
                v.setAlpha(Math.max(0f, 1f - ad * 0.55f));
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            place();
        }
    }

    private String statusText(Game g) {
        if ("pre".equals(g.state) && g.time > 0) {
            return new SimpleDateFormat("h:mm a", Locale.US).format(new Date(g.time));
        }
        String s = g.status == null ? "" : g.status;
        if (s.contains(" - ")) s = s.substring(s.indexOf(" - ") + 3);
        return s;
    }

    private void prefetchLogo(String url) {
        if (url == null || url.isEmpty() || logoCache.containsKey(url)) return;
        try {
            File dir = new File(getCacheDir(), "logos");
            dir.mkdirs();
            File f = new File(dir, Integer.toHexString(url.hashCode()) + ".png");
            byte[] data;
            if (f.exists()) {
                try (InputStream in = new FileInputStream(f)) { data = readAll(in); }
            } else {
                data = getBytes(url);
                try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(data); }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b != null) logoCache.put(url, b);
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- weather, calendar, photos

    private final Runnable slowTick = new Runnable() {
        @Override public void run() {
            io.execute(() -> {
                JSONObject cfg = readConfig();
                JSONObject wx = fetchWeather(cfg);
                List<Event> ev = readCalendar(cfg);
                main.post(() -> {
                    renderWeather(wx, cfg.optString("place", "Denver"));
                    renderAgenda(ev, cfg);
                });
                List<File> p = listPhotos();
                main.post(() -> {
                    boolean changed = !p.equals(photos);
                    photos = p;
                    if (changed) photoIndex = -1;
                });
            });
            main.postDelayed(this, SLOW_REFRESH_MS);
        }
    };

    private JSONObject readConfig() {
        File f = new File(getExternalFilesDir(null), "config.json");
        JSONObject cfg;
        try (InputStream in = new FileInputStream(f)) {
            cfg = new JSONObject(new String(readAll(in), "UTF-8"));
        } catch (Exception e) {
            cfg = new JSONObject();
        }
        // a place chosen in Settings overrides config.json
        SharedPreferences p = getSharedPreferences("home", MODE_PRIVATE);
        try {
            if (p.contains("lat") && p.contains("lon")) {
                cfg.put("place", p.getString("place", "")).put("lat", Double.parseDouble(p.getString("lat", "0")))
                        .put("lon", Double.parseDouble(p.getString("lon", "0")));
            }
        } catch (Exception ignored) { }
        return cfg;
    }

    /** Refetches the weather now (after a unit or place change). */
    void refreshWeather() {
        try {
            io.execute(() -> {
                JSONObject cfg = readConfig();
                JSONObject wx = fetchWeather(cfg);
                main.post(() -> renderWeather(wx, cfg.optString("place", "Denver")));
            });
        } catch (Exception ignored) { }
    }

    void applyClock() {
        boolean h24 = getSharedPreferences("home", MODE_PRIVATE).getBoolean("clock24", false);
        clock.setFormat12Hour(h24 ? "H:mm" : "h:mm");
        clock.setFormat24Hour("H:mm");
    }

    void nextWallpaper() {
        main.removeCallbacks(photoTick);
        main.post(photoTick);
    }

    void previewSplash() {
        closeSettings();
        main.postDelayed(() -> new Splash(this).play((ViewGroup) getWindow().getDecorView()), 300);
    }

    void launch(String pkg) {
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(launch); } catch (Exception ignored) { }
    }

    void openSettings() {
        if (settings == null) settings = new SettingsPanel(this);
        settings.open((ViewGroup) findViewById(android.R.id.content), homeRoot);
    }

    void closeSettings() {
        if (settings == null) return;
        settings.close(homeRoot, () -> { if (gear != null) gear.requestFocus(); });
    }

    private JSONObject fetchWeather(JSONObject cfg) {
        double lat = cfg.optDouble("lat", 39.7392), lon = cfg.optDouble("lon", -104.9903);
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code,wind_speed_10m,relative_humidity_2m"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                    + "&temperature_unit=" + (getSharedPreferences("home", MODE_PRIVATE).getBoolean("celsius", false) ? "celsius" : "fahrenheit") + "&wind_speed_unit=mph&timezone=auto&forecast_days=4";
            String body = get(url);
            JSONObject wx = new JSONObject(body);
            writeFile("cache-weather.json", body);
            return wx;
        } catch (Exception e) {
            return null;
        }
    }

    private void renderWeather(JSONObject wx, String place) {
        if (wx == null) { weatherNow.setText(""); return; }
        try {
            JSONObject cur = wx.getJSONObject("current");
            JSONObject daily = wx.getJSONObject("daily");
            int t = (int) Math.round(cur.getDouble("temperature_2m"));
            weatherNow.setText(t + "\u00B0 " + wmo(cur.getInt("weather_code")));
            int hi = (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0));
            int lo = (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0));
            StringBuilder sb = new StringBuilder("H " + hi + "\u00B0  L " + lo + "\u00B0");
            JSONArray days = daily.getJSONArray("time");
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.US);
            for (int i = 1; i < Math.min(4, days.length()); i++) {
                sb.append("   \u00B7   ").append(out.format(in.parse(days.getString(i)))).append(" ")
                        .append(Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i))).append("\u00B0");
            }
            weatherDays.setText(sb.toString());
        } catch (Exception e) {
            weatherNow.setText("");
        }
    }

    private static String wmo(int c) {
        if (c == 0) return "Clear";
        if (c <= 2) return "Partly cloudy";
        if (c == 3) return "Cloudy";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c >= 85 && c <= 86) return "Snow showers";
        if (c >= 95) return "Storms";
        return "Mixed";
    }

    private static class Event {
        long start, end;
        boolean allDay;
        String title;
        int color;
    }

    private List<Event> readCalendar(JSONObject cfg) {
        return readCalendar(cfg, false);
    }

    private List<Event> readCalendar(JSONObject cfg, boolean cachedOnly) {
        List<Event> out = new ArrayList<>();
        // 1. Calendar links the TV fetches itself (works with the Mac off).
        JSONArray ics = cfg.optJSONArray("ics");
        if (ics != null) {
            int[] colors = {0xFFFF5147, 0xFF5AA9FF, 0xFF46D68C, 0xFFB98CFF, ACCENT};
            for (int i = 0; i < ics.length(); i++) {
                try {
                    String url = ics.getString(i).replaceFirst("^webcal://", "https://");
                    String body;
                    if (cachedOnly) {
                        body = readFile("cache-ics-" + i + ".txt");
                        if (body == null) continue;
                    } else {
                        body = get(url);
                        writeFile("cache-ics-" + i + ".txt", body);
                    }
                    List<Event> got = parseIcs(body, colors[i % colors.length]);
                    out.addAll(got);
                    debugNote(i, "ok " + body.length() + " bytes, " + got.size() + " events\n"
                            + body.substring(0, Math.min(3000, body.length())));
                } catch (Exception e) {
                    debugNote(i, "error " + e);
                }
            }
        }
        // 2. Events the Mac pushed, when it is on.
        File f = new File(getExternalFilesDir(null), "calendar.json");
        if (f.exists()) {
            try (InputStream in = new FileInputStream(f)) {
                JSONArray arr = new JSONArray(new String(readAll(in), "UTF-8"));
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Event e = new Event();
                    e.allDay = o.optBoolean("allDay");
                    e.title = o.optString("title");
                    try { e.color = Color.parseColor(o.optString("color", "#5AA9FF")); } catch (Exception x) { e.color = 0xFF5AA9FF; }
                    try {
                        e.start = iso.parse(o.getString("start")).getTime();
                        e.end = o.has("end") ? iso.parse(o.getString("end")).getTime() : e.start;
                    } catch (Exception x) { continue; }
                    out.add(e);
                }
            } catch (Exception ignored) { }
        }
        // Drop the past and duplicates (the same event can come from both sources).
        long now = System.currentTimeMillis();
        Calendar dayStart = Calendar.getInstance();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        List<Event> keep = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (Event e : out) {
            if (e.allDay ? e.end <= dayStart.getTimeInMillis() : e.end < now) continue;
            if (!seen.add(e.title + "|" + e.start)) continue;
            keep.add(e);
        }
        Collections.sort(keep, (a, b) -> Long.compare(a.start, b.start));
        return keep;
    }

    /** Writes what each calendar link returned, for checking from the Mac over adb. */
    private void debugNote(int i, String text) {
        try (FileOutputStream fo = new FileOutputStream(new File(getExternalFilesDir(null), "ics-" + i + ".txt"))) {
            fo.write(text.getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    /** A small iCalendar reader: one off events, plus daily and weekly repeats over the next two weeks. */
    private static List<Event> parseIcs(String body, int color) {
        List<Event> out = new ArrayList<>();
        String unfolded = body.replace("\r\n ", "").replace("\r\n\t", "").replace("\n ", "").replace("\n\t", "");
        String[] lines = unfolded.split("\r?\n");
        Event cur = null;
        String rrule = null;
        long horizon = System.currentTimeMillis() + 60L * 86400000L;
        for (String line : lines) {
            if (line.equals("BEGIN:VEVENT")) { cur = new Event(); cur.color = color; rrule = null; continue; }
            if (cur == null) continue;
            if (line.equals("END:VEVENT")) {
                if (cur.title != null && cur.start > 0) {
                    if (cur.end == 0) cur.end = cur.allDay ? cur.start + 86400000L : cur.start;
                    out.add(cur);
                    if (rrule != null) out.addAll(repeat(cur, rrule, horizon));
                }
                cur = null;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon), val = line.substring(colon + 1);
            String name = key.contains(";") ? key.substring(0, key.indexOf(';')) : key;
            if (name.equals("SUMMARY")) {
                cur.title = val.replace("\\,", ",").replace("\\;", ";").replace("\\n", " ");
            } else if (name.equals("DTSTART") || name.equals("DTEND")) {
                String tz = null;
                for (String part : key.split(";")) if (part.startsWith("TZID=")) tz = part.substring(5);
                boolean dateOnly = val.length() == 8;
                long t = icsTime(val, tz);
                if (name.equals("DTSTART")) { cur.start = t; cur.allDay = dateOnly; } else cur.end = t;
            } else if (name.equals("RRULE")) {
                rrule = val;
            }
        }
        return out;
    }

    /** Expands DAILY, WEEKLY (without BYDAY), MONTHLY and YEARLY repeats up to the horizon. */
    private static List<Event> repeat(Event base, String rrule, long horizon) {
        List<Event> out = new ArrayList<>();
        int field;
        if (rrule.contains("FREQ=DAILY")) field = Calendar.DAY_OF_YEAR;
        else if (rrule.contains("FREQ=WEEKLY") && !rrule.contains("BYDAY=")) field = Calendar.WEEK_OF_YEAR;
        else if (rrule.contains("FREQ=MONTHLY") && !rrule.contains("BYDAY=")) field = Calendar.MONTH;
        else if (rrule.contains("FREQ=YEARLY") && !rrule.contains("BYDAY=")) field = Calendar.YEAR;
        else return out;
        int interval = 1, count = Integer.MAX_VALUE;
        long until = Long.MAX_VALUE;
        for (String part : rrule.split(";")) {
            if (part.startsWith("INTERVAL=")) interval = Math.max(1, num(part.substring(9)));
            if (part.startsWith("COUNT=")) count = num(part.substring(6));
            if (part.startsWith("UNTIL=")) until = icsTime(part.substring(6), null);
        }
        long dur = base.end - base.start;
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(base.start);
        for (int n = 1; n < count && n < 5000; n++) {
            c.add(field, interval);
            long t = c.getTimeInMillis();
            if (t > horizon || t > until) break;
            if (t + dur < now) continue;
            Event e = new Event();
            e.title = base.title; e.color = base.color; e.allDay = base.allDay;
            e.start = t; e.end = t + dur;
            out.add(e);
        }
        return out;
    }

    private static long icsTime(String v, String tz) {
        try {
            if (v.length() == 8) {
                SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
                return f.parse(v).getTime();
            }
            boolean utc = v.endsWith("Z");
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US);
            if (utc) f.setTimeZone(TimeZone.getTimeZone("UTC"));
            else if (tz != null) f.setTimeZone(TimeZone.getTimeZone(tz));
            return f.parse(utc ? v.substring(0, v.length() - 1) : v).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private void renderAgenda(List<Event> events, JSONObject cfg) {
        agenda.removeAllViews();
        if (events.isEmpty()) {
            boolean connected = cfg.has("ics") || new File(getExternalFilesDir(null), "calendar.json").exists();
            agendaEmpty.setText(connected ? "Nothing coming up" : "Calendar not connected yet");
            agenda.addView(agendaEmpty);
            return;
        }
        String lastDay = null;
        int lines = 0;
        for (Event e : events) {
            String day = dayLabel(e.start);
            int need = day.equals(lastDay) ? 1 : 2;
            if (lines + need > 5) break;
            if (!day.equals(lastDay)) {
                TextView h = text(12, sansMedium, DIM);
                h.setText(day);
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hlp.topMargin = lastDay == null ? 0 : dp(8);
                agenda.addView(h, hlp);
                lastDay = day;
                lines++;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(3), 0, dp(3));
            View bar = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setColor(e.color);
            d.setCornerRadius(dp(2));
            bar.setBackground(d);
            row.addView(bar, new LinearLayout.LayoutParams(dp(3), dp(16)));
            TextView t = text(14, sansMedium, TEXT);
            t.setText(e.title);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            tlp.leftMargin = dp(9);
            row.addView(t, tlp);
            TextView tm = text(12, sans, DIM);
            tm.setText(e.allDay ? "All day" : new SimpleDateFormat("h:mm a", Locale.US).format(new Date(e.start)));
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.leftMargin = dp(8);
            row.addView(tm, mlp);
            agenda.addView(row);
            lines++;
        }
    }

    private static String dayLabel(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        Calendar now = Calendar.getInstance();
        int diff = (c.get(Calendar.YEAR) - now.get(Calendar.YEAR)) * 366
                + c.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR);
        if (diff <= 0) return "Today";
        if (diff == 1) return "Tomorrow";
        return new SimpleDateFormat("EEEE, MMM d", Locale.US).format(new Date(t));
    }

    /** Startup: paint the last known scores, weather, calendar and a photo before any network call. */
    private void showCached() {
        List<File> p = listImages(new File(getExternalFilesDir(null), "photos"));
        if (p.isEmpty()) p = listImages(scenicDir());
        final List<File> pics = p;
        if (!pics.isEmpty()) {
            final int idx = (int) ((System.currentTimeMillis() / PHOTO_MS) % pics.size());
            File f = pics.get(idx);
            Bitmap b = decode(f, 1920);
            final String cap = f.getParentFile().getName().equals("scenic") ? caption(f.getName()) : "";
            main.post(() -> {
                photos = pics;
                photoIndex = idx;
                if (b != null) {
                    photoView.showNow(b);
                    wallpaper.showNow(b);
                    photoCaption.setText(cap);
                }
                main.postDelayed(photoTick, PHOTO_MS);
            });
        } else {
            main.postDelayed(photoTick, 1000);
        }
        List<Game> cached = loadGames();
        for (Game g : cached) { prefetchLogo(g.awayLogo); prefetchLogo(g.homeLogo); }
        if (!cached.isEmpty()) main.post(() -> { if (!wheel.hasGames()) wheel.setGames(cached); });
        JSONObject cfg = readConfig();
        String wxBody = readFile("cache-weather.json");
        List<Event> ev = readCalendar(cfg, true);
        main.post(() -> {
            if (wxBody != null) {
                try { renderWeather(new JSONObject(wxBody), cfg.optString("place", "Denver")); } catch (Exception ignored) { }
            }
            renderAgenda(ev, cfg);
        });
    }

    private void saveGames(List<Game> games) {
        try {
            JSONArray arr = new JSONArray();
            for (Game g : games) {
                JSONObject o = new JSONObject();
                o.put("league", g.league); o.put("state", g.state); o.put("status", g.status); o.put("time", g.time);
                o.put("aa", g.awayAbbr); o.put("ha", g.homeAbbr); o.put("as", g.awayScore); o.put("hs", g.homeScore);
                o.put("al", g.awayLogo); o.put("hl", g.homeLogo);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("day", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()));
            root.put("games", arr);
            writeFile("cache-scores.json", root.toString());
        } catch (Exception ignored) { }
    }

    private List<Game> loadGames() {
        List<Game> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(readFile("cache-scores.json"));
            if (!root.optString("day").equals(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()))) return out;
            JSONArray arr = root.getJSONArray("games");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Game g = new Game();
                g.league = o.optString("league"); g.state = o.optString("state"); g.status = o.optString("status");
                g.time = o.optLong("time");
                g.awayAbbr = o.optString("aa"); g.homeAbbr = o.optString("ha");
                g.awayScore = o.optString("as"); g.homeScore = o.optString("hs");
                g.awayLogo = o.optString("al"); g.homeLogo = o.optString("hl");
                out.add(g);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void writeFile(String name, String text) {
        try (FileOutputStream fo = new FileOutputStream(new File(getFilesDir(), name))) {
            fo.write(text.getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    private String readFile(String name) {
        File f = new File(getFilesDir(), name);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new String(readAll(in), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private final Runnable photoTick = new Runnable() {
        @Override public void run() {
            if (!photos.isEmpty()) {
                photoIndex = (photoIndex + 1) % photos.size();
                final File f = photos.get(photoIndex);
                io.execute(() -> {
                    Bitmap b = decode(f, 1920);
                    final String cap = f.getParentFile().getName().equals("scenic")
                            ? caption(f.getName()) : "";
                    File nf = photos.get((photoIndex + 1) % photos.size());
                    Bitmap card = b;   // the photo card is no longer shown; decoding a second picture for it only cost memory
                    final String ncap = nf.getParentFile().getName().equals("scenic") ? caption(nf.getName()) : "";
                    if (b != null) main.post(() -> {
                        wallpaper.show(b);
                        photoView.show(card != null ? card : b);
                        photoCaption.setText(photos.size() > 1 ? ncap : cap);
                    });
                });
            }
            main.postDelayed(this, photos.isEmpty() ? 3000 : PHOTO_MS);
        }
    };

    /** "1 lake louise pano 2019.jpg" -> "Lake Louise": drop numbers and camera words, title case. */
    private static String caption(String file) {
        String base = file.replaceFirst("\\.[A-Za-z]+$", "").replaceAll("[_()]", " ");
        StringBuilder sb = new StringBuilder();
        for (String w : base.split("\\s+")) {
            String lw = w.toLowerCase(Locale.US);
            if (w.isEmpty() || lw.matches("\\d+|pano|panorama|edit|crop|img|dsc\\w*|hdr|jpg|\\d+x\\d+")) continue;
            if (sb.length() > 0) sb.append(' ');
            boolean small = sb.length() > 0 && lw.matches("of|from|the|and|in|at|on|to|de|la|le");
            sb.append(small ? lw : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        String out = sb.toString().replaceAll("^[\\s\\-,]+|[\\s\\-,]+$", "").split(",\\s*\\d")[0];
        return out.length() < 3 ? "" : out;
    }

    private List<File> listPhotos() {
        List<File> local = listImages(new File(getExternalFilesDir(null), "photos"));
        if (!local.isEmpty()) return local;
        ensureScenic();
        return listImages(scenicDir());
    }

    private File scenicDir() {
        File d = new File(getCacheDir(), "scenic");
        d.mkdirs();
        return d;
    }

    // Wikimedia Commons featured photos: free to use, curated, fetched by the TV itself.
    private static final String[] SCENIC_CATEGORIES = {
            "Featured_pictures_of_landscapes",
            "Featured_pictures_of_mountains",
            "Featured_pictures_of_lakes",
            "Featured_pictures_of_beaches",
    };
    private static final int SCENIC_COUNT = 24;

    private void ensureScenic() {
        File dir = scenicDir();
        File stamp = new File(dir, ".fetched");
        int have = listImages(dir).size();
        if (have >= SCENIC_COUNT && System.currentTimeMillis() - stamp.lastModified() < 7L * 86400000L) return;
        List<String[]> picks = new ArrayList<>();
        for (String cat : SCENIC_CATEGORIES) {
            try {
                String api = "https://commons.wikimedia.org/w/api.php?action=query&format=json"
                        + "&generator=categorymembers&gcmtitle=Category:" + cat + "&gcmtype=file&gcmlimit=80"
                        + "&prop=imageinfo&iiprop=url|size&iiurlwidth=1600";
                JSONObject pages = new JSONObject(get(api)).getJSONObject("query").getJSONObject("pages");
                Iterator<String> it = pages.keys();
                while (it.hasNext()) {
                    JSONObject pg = pages.getJSONObject(it.next());
                    JSONArray ii = pg.optJSONArray("imageinfo");
                    if (ii == null) continue;
                    JSONObject info = ii.getJSONObject(0);
                    int w = info.optInt("width"), h = info.optInt("height");
                    String url = info.optString("thumburl", "");
                    if (url.isEmpty() || h == 0 || w < h * 1.3 || w > h * 2.4) continue;
                    String title = pg.optString("title").replaceFirst("^File:", "").replaceFirst("\\.[A-Za-z]+$", "");
                    picks.add(new String[]{url, title});
                }
            } catch (Exception ignored) { }
        }
        Collections.shuffle(picks);
        int got = have;
        for (String[] p : picks) {
            if (got >= SCENIC_COUNT) break;
            String name = p[1].replaceAll("[^A-Za-z0-9 ,'()-]", " ").replaceAll("\\s+", " ").trim();
            if (name.length() > 60) name = name.substring(0, 60).trim();
            File f = new File(dir, name + ".jpg");
            if (f.exists()) continue;
            try (FileOutputStream fo = new FileOutputStream(f)) {
                fo.write(getBytes(p[0]));
                got++;
            } catch (Exception ignored) { }
        }
        try { stamp.createNewFile(); stamp.setLastModified(System.currentTimeMillis()); } catch (Exception ignored) { }
    }

    private static List<File> listImages(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.US);
            if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) out.add(f);
        }
        Collections.sort(out);
        return out;
    }

    private static Bitmap decode(File f, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (o.outWidth / (s * 2) >= maxW) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        // sampling only halves, so a 6000 px photo arrived at 3000 px (24 MB); bring it to the screen's width
        if (b != null && b.getWidth() > maxW) {
            Bitmap sc = Bitmap.createScaledBitmap(b, maxW, Math.round(b.getHeight() * (maxW / (float) b.getWidth())), true);
            if (sc != b) b.recycle();
            b = sc;
        }
        return b;
    }

    // ---------------------------------------------------------------- helpers

    private static String get(String url) throws Exception {
        return new String(getBytes(url), "UTF-8");
    }

    static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "TVHome/1.0 (personal TV home screen)");
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(14), dp(18), dp(12));
        c.setBackground(cardBg());
        return c;
    }

    private GradientDrawable cardBg() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xB8141418);   // smoked glass, readable over a sharp photo
        g.setStroke(dp(1), 0x1FFFFFFF);
        g.setCornerRadius(dp(22));
        return g;
    }

    private TextView cardTitle(String s) {
        TextView t = text(11, sansMedium, 0x8CFFFFFF);
        t.setText(s);
        t.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    TextView text(float sp, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void rounded(View v, final int radiusDp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        });
        v.setClipToOutline(true);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** Two stacked ImageViews that crossfade, with an optional slow zoom on the incoming picture. */
    private static class Crossfade extends FrameLayout {
        private final ImageView a, b;
        private final boolean kenBurns;
        private boolean showingA = false;

        Crossfade(Context ctx, boolean kenBurns) {
            super(ctx);
            this.kenBurns = kenBurns;
            a = new ImageView(ctx);
            b = new ImageView(ctx);
            for (ImageView v : new ImageView[]{a, b}) {
                v.setScaleType(ImageView.ScaleType.CENTER_CROP);
                v.setAlpha(0f);
                addView(v, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            }
        }

        /** Shows a picture at once, with no fade (first paint). */
        void showNow(Bitmap bmp) {
            ImageView in = showingA ? b : a;
            ImageView out = showingA ? a : b;
            showingA = !showingA;
            in.setImageBitmap(bmp);
            in.bringToFront();
            in.animate().cancel();
            in.setAlpha(1f);
            out.setAlpha(0f);
        }

        void show(Bitmap bmp) {
            ImageView in = showingA ? b : a;
            ImageView out = showingA ? a : b;
            showingA = !showingA;
            in.setImageBitmap(bmp);
            in.bringToFront();
            in.setAlpha(0f);
            in.setScaleX(1f);
            in.setScaleY(1f);
            // The animator remembers delay and interpolator per view, so set both every time.
            in.animate().alpha(1f).setStartDelay(0).setDuration(1200)
                    .setInterpolator(new DecelerateInterpolator()).start();
            if (kenBurns) {
                in.animate().scaleX(1.07f).scaleY(1.07f).setStartDelay(0).setDuration(PHOTO_MS + 1500)
                        .setInterpolator(new LinearInterpolator()).start();
            }
            out.animate().alpha(0f).setStartDelay(1200).setDuration(10).start();
        }
    }
}
