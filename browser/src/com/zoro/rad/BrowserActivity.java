package com.zoro.rad;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rad Browser: a web browser for the TV remote.
 *   Arrows move a pointer (it speeds up while held); at the screen edge the page scrolls.
 *   OK clicks. Holding OK opens the top bar (address, back, forward, reload, home, favorite).
 *   Back goes back a page. Play/Pause controls the video on the page.
 * Rad Shields block ads and trackers, hide leftover ad boxes, and stop pop ups and redirect hijacks.
 */
public class BrowserActivity extends Activity {

    private static final String START = "file:///android_asset/start.html";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";
    private static final String[][] LISTS = {
            {"easylist", "https://easylist.to/easylist/easylist.txt"},
            {"easyprivacy", "https://easylist.to/easylist/easyprivacy.txt"},
            {"ublock", "https://ublockorigin.github.io/uAssets/filters/filters.txt"},
            {"ublock-privacy", "https://ublockorigin.github.io/uAssets/filters/privacy.txt"},
            {"peterlowe", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext"},
    };
    private static final long LIST_MAX_AGE = 4L * 86400000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Shields shields;          // null until the lists are loaded
    private volatile String pageUrl;           // the page requests are made from
    private final AtomicInteger blockedOnPage = new AtomicInteger();
    private int popupsBlocked, redirectsBlocked;
    private volatile String lastClickHref;     // href of the link under the pointer at the last click

    private FrameLayout root, fullscreen;
    private WebView web;
    private Cursor cursor;
    private Icon menuButton;             // the always there way into the bar and bookmarks
    private FrameLayout bar;
    private LinearLayout marks;          // the bookmarks strip shown under the bar
    private android.widget.HorizontalScrollView marksScroll;
    private final java.util.Map<String, Bitmap> favicons = new java.util.concurrent.ConcurrentHashMap<>();
    private android.widget.ImageView backdrop;
    private EditText address;
    private TextView shieldLabel, hint;
    private Icon starButton, uaButton;
    private View customView;
    private View progress;              // thin blue loading line at the top
    private WebChromeClient.CustomViewCallback customCallback;
    private SharedPreferences prefs;
    private boolean desktop = true;

    private float cx, cy;
    // Held arrows and how long they have been held, for the gliding pointer.
    private boolean holdL, holdR, holdU, holdD;
    private long holdStart, lastFrame;
    private float nudgeLeft;            // a quick tap still moves the pointer a little, for precision
    private boolean gliding;
    private long okDownAt;
    private long lastBackUp;
    private volatile boolean pageKeys;      // the page wants the arrow keys (it has its own remote navigation)
    private boolean keyboardWasUp;
    private boolean okLongFired;
    // Holding OK: on a favorite of the start page it renames it; anywhere else it opens the menu.
    private final Runnable okLong = () -> {
        okLongFired = true;
        String u = web.getUrl();
        if (u != null && u.startsWith(START)) {
            float density = getResources().getDisplayMetrics().density;
            int px = Math.round(cx / density), py = Math.round(cy / density);
            web.evaluateJavascript("(function(){var e=document.elementFromPoint(" + px + "," + py + ");"
                    + "if(window.RadWidgets&&RadWidgets.hold(" + px + "," + py + "))return true;"
                    + "var a=e&&e.closest&&e.closest('a.tile');return !!(a&&window.radRename&&radRename(a.href));})()",
                    v -> { if (!"true".equals(v)) openMenu(); });
        } else openMenu();
    };
    private final Runnable hideCursor = () -> {
        cursor.animate().alpha(0f).setDuration(400).start();
        menuButton.animate().alpha(0f).setDuration(400).start();
    };

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("rad", MODE_PRIVATE);
        desktop = prefs.getBoolean("desktop", true);
        dropSampleFavorites();
        importFavorites();
        mirrorFavorites();

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setFocusable(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(true);
        s.setUserAgentString(desktop ? DESKTOP_UA : null);
        s.setOffscreenPreRaster(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);   // http streams inside https pages           // paint just outside the screen so scrolling stays smooth
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        web.addJavascriptInterface(new Bridge(), "Rad");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        root.addView(web, match());

        progress = new View(this);
        progress.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF64D2FF, 0xFF0A84FF}));
        progress.setPivotX(0);
        progress.setScaleX(0f);
        progress.setAlpha(0f);
        root.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP));

        fullscreen = new FrameLayout(this);
        fullscreen.setBackgroundColor(Color.BLACK);
        fullscreen.setVisibility(View.GONE);
        root.addView(fullscreen, match());

        menuButton = new Icon(this, Icon.BOOKMARKS, v -> openMenu());
        menuButton.setFocusable(false);
        GradientDrawable mb = new GradientDrawable();
        mb.setShape(GradientDrawable.OVAL);
        mb.setColor(0xB31C1C22);
        mb.setStroke(dp(1), 0x59FFFFFF);
        menuButton.setBackground(mb);
        menuButton.setElevation(dp(8));
        FrameLayout.LayoutParams mblp = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.END);
        mblp.setMargins(0, dp(18), dp(22), 0);
        root.addView(menuButton, mblp);

        cursor = new Cursor(this);
        root.addView(cursor, new FrameLayout.LayoutParams(cursor.size(), cursor.size()));

        marksScroll = new android.widget.HorizontalScrollView(this);
        marksScroll.setHorizontalScrollBarEnabled(false);
        marksScroll.setClipToPadding(false);
        marksScroll.setPadding(dp(40), 0, dp(40), 0);
        marks = new LinearLayout(this);
        marks.setOrientation(LinearLayout.HORIZONTAL);
        marks.setPadding(0, dp(6), 0, dp(6));
        marksScroll.addView(marks);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        mlp.topMargin = dp(96);
        root.addView(marksScroll, mlp);
        marksScroll.setVisibility(View.GONE);
        bar = buildBar();
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP);
        blp.setMargins(dp(40), dp(22), dp(40), 0);
        root.addView(bar, blp);
        bar.setVisibility(View.GONE);

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable hb = new GradientDrawable();
        hb.setColor(0xCC1C1C22);
        hb.setStroke(dp(1), 0x40FFFFFF);
        hb.setCornerRadius(dp(20));
        hint.setBackground(hb);
        hint.setAlpha(0f);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(28);
        root.addView(hint, hlp);

        setContentView(root);
        // When the keyboard closes (Back on the keyboard), take focus off the page's text box, so the
        // search bar drops back down right away instead of waiting for another press.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            boolean kb = insets.isVisible(android.view.WindowInsets.Type.ime());
            if (keyboardWasUp && !kb) {
                web.evaluateJavascript("document.activeElement&&document.activeElement.blur&&document.activeElement.blur();"
                        + "document.body&&document.body.classList.remove('typing')", null);
            }
            if (kb && !keyboardWasUp) {
                // Keyboard just came up: if the page's text box is under it, scroll the page so the box sits
                // just above the keyboard (the keyboard does not resize this window, so the page cannot know).
                float dens = getResources().getDisplayMetrics().density;
                int kbCss = Math.round(insets.getInsets(android.view.WindowInsets.Type.ime()).bottom / dens);
                web.postDelayed(() -> web.evaluateJavascript("(function(){var e=document.activeElement;if(!e||!e.getBoundingClientRect)return;"
                        + "var r=e.getBoundingClientRect(),lim=innerHeight-" + kbCss + "-24;"
                        + "if(r.bottom>lim){var d=r.bottom-lim;var s=document.scrollingElement||document.body;"
                        + "var p=e.parentElement;while(p&&p!==document.body){var cs=getComputedStyle(p);"
                        + "if(/(auto|scroll)/.test(cs.overflowY)&&p.scrollHeight>p.clientHeight){p.scrollTop+=d;r=e.getBoundingClientRect();d=r.bottom-lim;if(d<=0)return;}p=p.parentElement;}"
                        + "s.scrollTop+=d;}})()", null), 120);
            }
            keyboardWasUp = kb;
            return insets;
        });
        root.post(() -> {
            cx = root.getWidth() / 2f;
            cy = root.getHeight() / 2f;
            cursor.moveTo(cx, cy);
        });

        new Thread(this::loadShields, "shields").start();

        Uri data = getIntent() == null ? null : getIntent().getData();
        String last = prefs.getString("last", null);
        load(data != null ? data.toString() : last != null ? last : START);
    }

    /** A link sent to Rad while it is already open (from another app, or the home screen). */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        Uri data = intent == null ? null : intent.getData();
        if (data != null) {
            closeMenu();
            load(data.toString());
        }
    }

    /** Glass Keys says it closed: take focus off the text box so the search bar drops back down. */
    private final android.content.BroadcastReceiver keysHidden = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            web.evaluateJavascript("document.activeElement&&document.activeElement.blur&&document.activeElement.blur();"
                    + "document.body&&document.body.classList.remove('typing')", null);
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(keysHidden, new android.content.IntentFilter("com.zoro.keys.HIDDEN"), RECEIVER_EXPORTED);
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(keysHidden); } catch (Exception ignored) { }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        File add = new File(getExternalFilesDir(null), "add-favorites.json");
        if (add.exists()) {
            importFavorites();
            mirrorFavorites();
            String u = web.getUrl();
            if (u != null && u.startsWith(START)) web.reload();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pageUrl != null && !pageUrl.startsWith("file:")) prefs.edit().putString("last", pageUrl).apply();
        else prefs.edit().remove("last").apply();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ shields

    private void loadShields() {
        Shields sh = new Shields();
        boolean any = false;
        for (String[] l : LISTS) {
            File f = new File(getFilesDir(), "list-" + l[0] + ".txt");
            String text = null;
            if (f.exists()) text = read(f);
            if (text == null || System.currentTimeMillis() - f.lastModified() > LIST_MAX_AGE) {
                try {
                    String fresh = new String(getBytes(l[1]), "UTF-8");
                    if (fresh.length() > 1000) {
                        text = fresh;
                        try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(fresh.getBytes("UTF-8")); }
                    }
                } catch (Exception ignored) { }
            }
            if (text != null) { sh.addList(text); any = true; }
        }
        if (!any) return;
        sh.finish();
        shields = sh;
        final int n = sh.ruleCount;
        main.post(() -> {
            if (pendingUrl != null) { web.loadUrl(pendingUrl); pendingUrl = null; } updateShieldLabel(); });
    }

    private void updateShieldLabel() {
        if (shieldLabel == null) return;
        shieldLabel.setText(shields == null ? "Shields loading" : blockedOnPage.get() + " blocked");
    }

    // ------------------------------------------------------------------ remote control

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (customView != null) {
            if (code == KeyEvent.KEYCODE_BACK && down) { exitFullscreen(); return true; }
            if (isOk(code) && down) { playPause(); return true; }
            return super.dispatchKeyEvent(e);
        }
        if (menuOpen()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) {
                    // Back twice quickly: home. Back once more later: just close the menu.
                    boolean twice = SystemClock.uptimeMillis() - lastBackUp < 450;
                    closeMenu();
                    if (twice) web.loadUrl(START);
                    lastBackUp = 0;
                }
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (bar.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK && !down) { showBar(false); return true; }
            if (code == KeyEvent.KEYCODE_BACK) return true;
            return super.dispatchKeyEvent(e);
        }
        // A text field on the page has the keyboard: let keys go to it.
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        boolean typing = imm != null && imm.isAcceptingText() && web.hasFocus();
        if (typing && code != KeyEvent.KEYCODE_BACK && !isArrow(code)
                && code != KeyEvent.KEYCODE_DPAD_CENTER) {
            return super.dispatchKeyEvent(e);   // Enter and letters belong to the keyboard, not a click
        }

        // A page that asked for the remote gets the arrows and OK as plain keys, no pointer.
        if (pageKeys && (isArrow(code) || isOk(code))) {
            if (!web.hasFocus()) web.requestFocus();
            cursor.setAlpha(0f);
            return super.dispatchKeyEvent(e);
        }
        if (isArrow(code)) {
            arrow(code, down, e.getRepeatCount());
            return true;
        }
        if (isOk(code)) {
            if (down && e.getRepeatCount() == 0) {
                okDownAt = SystemClock.uptimeMillis();
                okLongFired = false;
                main.postDelayed(okLong, 800);
            } else if (!down) {
                main.removeCallbacks(okLong);
                if (!okLongFired) click();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (!down) {
                // A page's own text box with the keyboard up: Back just closes the keyboard.
                // Back once opens the menu with the search bar; Back twice quickly goes home.
                if (typing) hideKeyboard();
                else if (web.getUrl() != null && web.getUrl().startsWith(START)) {
                    // On the start page, Back first closes an open sheet; otherwise the menu as usual.
                    web.evaluateJavascript("window.radBack?radBack():false", v -> {
                        if (!"true".equals(v)) { openMenu(); lastBackUp = SystemClock.uptimeMillis(); }
                    });
                }
                else { openMenu(); lastBackUp = SystemClock.uptimeMillis(); }
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (down) playPause();
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SEARCH) {
            if (down) openMenu();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isArrow(int c) {
        return c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_DOWN
                || c == KeyEvent.KEYCODE_DPAD_LEFT || c == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static boolean isOk(int c) {
        return c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    private void arrow(int code, boolean down, int repeat) {
        if (down && repeat > 0) return;   // the frame loop handles holding
        boolean anyBefore = holdL || holdR || holdU || holdD;
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) holdL = down;
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) holdR = down;
        if (code == KeyEvent.KEYCODE_DPAD_UP) holdU = down;
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) holdD = down;
        if (down) {
            lastDirX = (holdR ? 1 : 0) - (holdL ? 1 : 0);
            lastDirY = (holdD ? 1 : 0) - (holdU ? 1 : 0);
            if (!anyBefore) holdStart = SystemClock.uptimeMillis();
            nudgeLeft = dp(14);
            cursor.animate().cancel();
            cursor.setAlpha(1f);
            menuButton.animate().cancel();
            menuButton.setAlpha(1f);
            main.removeCallbacks(hideCursor);
            if (!gliding) {
                gliding = true;
                lastFrame = 0;
                android.view.Choreographer.getInstance().postFrameCallback(glide);
            }
        } else if (!(holdL || holdR || holdU || holdD)) {
            main.postDelayed(hideCursor, 5000);
        }
    }

    /** Runs every frame while an arrow is held: the pointer glides and speeds up, and scrolls at the edges. */
    private final android.view.Choreographer.FrameCallback glide = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameNanos) {
            boolean held = holdL || holdR || holdU || holdD;
            if (!held && nudgeLeft <= 0) { endDrag(); gliding = false; return; }
            long now = frameNanos / 1000000L;
            float dt = lastFrame == 0 ? 1f / 60f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            float heldFor = (SystemClock.uptimeMillis() - holdStart) / 1000f;
            // 320 dp/s at first, easing up to 1500 dp/s after about a second.
            float t = Math.min(1f, Math.max(0f, (heldFor - 0.15f) / 1.1f));
            float speed = dp(320 + (1500 - 320) * t * t);
            float step = speed * dt;
            if (!held) step = Math.min(step, nudgeLeft);       // finish the tap nudge after release
            nudgeLeft -= step;
            int dirX = (holdR ? 1 : 0) - (holdL ? 1 : 0);
            int dirY = (holdD ? 1 : 0) - (holdU ? 1 : 0);
            if (!held) { dirX = lastDirX; dirY = lastDirY; } else { lastDirX = dirX; lastDirY = dirY; }
            float len = (dirX != 0 && dirY != 0) ? 0.7071f : 1f;
            float w = root.getWidth(), h = root.getHeight(), edge = dp(4);
            float nx = cx + dirX * step * len, ny = cy + dirY * step * len;
            float sx = 0, sy = 0;
            if (nx < edge) { sx = -1; nx = edge; } else if (nx > w - edge) { sx = 1; nx = w - edge; }
            if (ny < edge) { sy = -1; ny = edge; } else if (ny > h - edge) { sy = 1; ny = h - edge; }
            cx = nx;
            cy = ny;
            cursor.moveTo(cx, cy);
            if (!dragging && now - lastHover >= 50) { lastHover = now; hover(cx, cy); }
            if (held && (sx != 0 || sy != 0)) dragScroll(sx, sy, heldFor, dt);
            else endDrag();
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };
    private int lastDirX, lastDirY;
    private long lastHover;

    // A pretend finger that drags the page while the pointer is held against an edge: the page scrolls
    // exactly like a touch drag, and lets go with natural momentum.
    private boolean dragging;
    private long dragDown;
    private float fx, fy, fStartX, fStartY;

    private void dragScroll(float sx, float sy, float heldFor, float dt) {
        float speed = dp(260 + Math.min(700, heldFor * 450));    // dp per second, a little faster the longer it is held
        float w = root.getWidth(), h = root.getHeight();
        if (!dragging) {
            dragging = true;
            dragDown = SystemClock.uptimeMillis();
            // Put the finger down on the content, not on a fixed header or footer at the edge: a third of the
            // way in from the edge it is travelling away from, so it has room to move.
            fx = fStartX = sx == 0 ? Math.max(dp(40), Math.min(w - dp(40), cx)) : (sx > 0 ? w * 0.7f : w * 0.3f);
            fy = fStartY = sy == 0 ? Math.max(dp(40), Math.min(h - dp(40), cy)) : (sy > 0 ? h * 0.7f : h * 0.3f);
            touch(MotionEvent.ACTION_DOWN, fx, fy);
        }
        fx -= sx * speed * dt;   // the finger moves against the scroll direction, like a real swipe
        fy -= sy * speed * dt;
        touch(MotionEvent.ACTION_MOVE, fx, fy);
        // The finger has travelled most of the screen: lift and put it back down to keep going.
        if (Math.abs(fy - fStartY) > h * 0.4f || Math.abs(fx - fStartX) > w * 0.4f) {
            liftWithoutFling();
        }
    }

    /** Tells the page where the pointer is, like a mouse, so menus open and hover highlights show. */
    private void hover(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x;
        pc[0].y = y;
        MotionEvent e = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 1, pp, pc, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        web.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    private void endDrag() {
        if (!dragging) return;
        liftWithoutFling();
    }

    /** Holds the pretend finger still for a moment before lifting, so the page stops instead of flinging. */
    private void liftWithoutFling() {
        dragging = false;
        long t = SystemClock.uptimeMillis();
        touchAt(MotionEvent.ACTION_MOVE, fx, fy, t + 150);
        touchAt(MotionEvent.ACTION_UP, fx, fy, t + 160);
    }

    private void touchAt(int action, float x, float y, long when) {
        MotionEvent e = MotionEvent.obtain(dragDown, when, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(e);
        e.recycle();
    }

    private void touch(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(dragDown, now, action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(e);
        e.recycle();
    }

    /** A tap at the pointer. Notes the link under it first, so a pop up it opens can be told from an ad. */
    private void click() {
        cursor.pulse();
        if (menuButton.getVisibility() == View.VISIBLE && menuButton.getAlpha() > 0.9f
                && cx >= menuButton.getLeft() && cx <= menuButton.getRight()
                && cy >= menuButton.getTop() && cy <= menuButton.getBottom()) {
            openMenu();
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        int px = Math.round(cx / density), py = Math.round(cy / density);
        web.evaluateJavascript("(function(){var e=document.elementFromPoint(" + px + "," + py + ");"
                + "var a=e&&e.closest&&e.closest('a');return a&&a.href?a.href:'';})()",
                v -> lastClickHref = v == null ? "" : v.replace("\"", ""));
        long now = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0);
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        MotionEvent u = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, cx, cy, 0);
        u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.requestFocus();
        web.dispatchTouchEvent(d);
        web.dispatchTouchEvent(u);
        d.recycle();
        u.recycle();
    }

    private void playPause() {
        web.evaluateJavascript("(function(){var v=document.querySelector('video');"
                + "if(!v){var f=document.querySelectorAll('iframe');}if(v){v.paused?v.play():v.pause();}})()", null);
    }

    // ------------------------------------------------------------------ glass bar

    /**
     * A floating liquid glass pill. Behind it sits a blurred snapshot of the page under it (the TV has
     * window blur switched off, and the page holds still while the bar is open, so this reads as real glass),
     * a soft white tint, and a bright hairline edge.
     */
    private FrameLayout buildBar() {
        FrameLayout b = new FrameLayout(this);
        b.setClipToOutline(true);
        b.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getHeight() / 2f);
            }
        });
        b.setElevation(dp(14));

        backdrop = new android.widget.ImageView(this);
        backdrop.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        b.addView(backdrop, match());
        View tint = new View(this);
        tint.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x1FFFFFFF, 0x05FFFFFF, 0x0DFFFFFF}));
        b.addView(tint, match());
        View darken = new View(this);
        darken.setBackgroundColor(0x1A000000);
        b.addView(darken, match());
        GradientDrawable rim = new GradientDrawable();
        rim.setColor(Color.TRANSPARENT);
        rim.setCornerRadius(dp(32));
        rim.setStroke(dp(1.5f), 0xCCFFFFFF);
        b.setForeground(rim);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(12), 0);
        row.setClipChildren(false);
        b.addView(row, match());

        row.addView(new Icon(this, Icon.BACK, v -> { if (web.canGoBack()) web.goBack(); showBar(false); }));
        row.addView(new Icon(this, Icon.FORWARD, v -> { if (web.canGoForward()) web.goForward(); showBar(false); }));
        row.addView(new Icon(this, Icon.RELOAD, v -> { web.reload(); showBar(false); }));
        row.addView(new Icon(this, Icon.HOME, v -> { web.loadUrl(START); showBar(false); }));
        row.addView(new Icon(this, Icon.BOOKMARKS, v -> { web.loadUrl(START + "#bookmarks"); showBar(false); }));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        address.setTextColor(Color.WHITE);
        address.setHintTextColor(0x99FFFFFF);
        address.setHint("Search or type a site");
        address.setGravity(Gravity.CENTER);
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setPadding(dp(18), dp(9), dp(18), dp(9));
        address.setBackground(glassPill(0x26FFFFFF, 0x00FFFFFF));
        address.setOnFocusChangeListener((v, has) -> {
            address.setBackground(glassPill(has ? 0x40FFFFFF : 0x26FFFFFF, has ? 0x99FFFFFF : 0x00FFFFFF));
            address.setGravity(has ? Gravity.START | Gravity.CENTER_VERTICAL : Gravity.CENTER);
        });
        address.setOnEditorActionListener((v, id, ev) -> {
            go(address.getText().toString());
            return true;
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        alp.leftMargin = dp(10);
        alp.rightMargin = dp(10);
        row.addView(address, alp);

        row.addView(new Icon(this, Icon.MIC, v -> { showBar(false); startVoice(); }));
        starButton = new Icon(this, Icon.STAR, v -> toggleFavorite());
        row.addView(starButton);
        uaButton = new Icon(this, desktop ? Icon.DESKTOP : Icon.PHONE, v -> toggleDesktop());
        row.addView(uaButton);

        shieldLabel = new TextView(this);
        shieldLabel.setTextColor(Color.WHITE);
        shieldLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        shieldLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        shieldLabel.setPadding(dp(12), dp(7), dp(14), dp(7));
        shieldLabel.setBackground(glassPill(0x3334C759, 0x6634C759));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(6);
        row.addView(shieldLabel, slp);
        updateShieldLabel();
        return b;
    }

    /** Glass chips for every bookmark, plus one to bookmark this page. Down from the bar reaches them. */
    private void buildMarks() {
        marks.removeAllViews();
        JSONArray f = favorites();
        String here = web.getUrl();
        if (here != null && !here.startsWith("file:") && !isFavorite(here)) {
            marks.addView(chip("+  Bookmark this page", null, v -> { toggleFavorite(); buildMarks(); }));
        }
        for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i);
            final String url = o.optString("url");
            String title = o.optString("title");
            if (title.length() > 22) title = title.substring(0, 21).trim() + "\u2026";
            marks.addView(chip(title, url, v -> { load(url); showBar(false); }));
        }
    }

    private View chip(String label, String url, View.OnClickListener l) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(10), dp(7), dp(16), dp(7));
        c.setFocusable(true);
        c.setBackground(glassPill(0xB31C1C22, 0x40FFFFFF));
        c.setElevation(dp(6));
        c.setOnClickListener(l);
        c.setOnFocusChangeListener((v, has) -> {
            v.setBackground(glassPill(has ? 0xFF0A84FF : 0xB31C1C22, has ? 0xCCFFFFFF : 0x40FFFFFF));
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120).start();
        });
        if (url != null) {
            android.widget.ImageView icon = new android.widget.ImageView(this);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(18), dp(18));
            ilp.rightMargin = dp(8);
            c.addView(icon, ilp);
            String host = Shields.host(url);
            Bitmap b = host == null ? null : favicons.get(host);
            if (b != null) icon.setImageBitmap(b);
            else if (host != null) new Thread(() -> {
                try {
                    byte[] d = getBytes("https://www.google.com/s2/favicons?sz=64&domain=" + host);
                    Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(d, 0, d.length);
                    if (bm != null) { favicons.put(host, bm); main.post(() -> icon.setImageBitmap(bm)); }
                } catch (Exception ignored) { }
            }).start();
        } else {
            c.setPadding(dp(16), dp(7), dp(16), dp(7));
        }
        TextView t = new TextView(this);
        t.setText(label);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(150));
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    // ------------------------------------------------------------------ back menu

    /**
     * The Back menu: a liquid glass sheet over a blurred copy of the page, with the address, the main
     * actions and the bookmarks. Back opens it; Back again closes it. The first button goes back a page.
     */
    private FrameLayout menu;
    private android.widget.ImageView menuBackdrop;
    private LinearLayout menuActions, menuMarks;
    private TextView menuTitle, menuHost, menuShield;
    private EditText menuAddress;

    private void buildMenu() {
        menu = new FrameLayout(this);
        menu.setVisibility(View.GONE);
        menuBackdrop = new android.widget.ImageView(this);
        menuBackdrop.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        menu.addView(menuBackdrop, match());
        View dim = new View(this);
        dim.setBackgroundColor(0x73000000);
        menu.addView(dim, match());

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(28), dp(22), dp(28), dp(22));
        // Dark smoked glass with a white sheen on top, so white text reads on bright pages too.
        GradientDrawable smoke = new GradientDrawable();
        smoke.setColor(0xB31A1A20);
        smoke.setCornerRadius(dp(30));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x38FFFFFF, 0x14FFFFFF, 0x0AFFFFFF});
        g.setCornerRadius(dp(30));
        g.setStroke(dp(1), 0x59FFFFFF);
        sheet.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{smoke, g}));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(dp(860), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        menu.addView(sheet, slp);

        // Header: what page this is, and the shield count.
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        menuTitle = new TextView(this);
        menuTitle.setTextColor(Color.WHITE);
        menuTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        menuTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        menuTitle.setSingleLine(true);
        menuTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        menuHost = new TextView(this);
        menuHost.setTextColor(0x99FFFFFF);
        menuHost.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        titles.addView(menuTitle);
        titles.addView(menuHost);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        menuShield = new TextView(this);
        menuShield.setTextColor(Color.WHITE);
        menuShield.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        menuShield.setPadding(dp(14), dp(7), dp(14), dp(7));
        head.addView(menuShield);
        sheet.addView(head);

        // Address and voice.
        LinearLayout addr = new LinearLayout(this);
        addr.setGravity(Gravity.CENTER_VERTICAL);
        menuAddress = new EditText(this);
        menuAddress.setSingleLine(true);
        menuAddress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        menuAddress.setTextColor(Color.WHITE);
        menuAddress.setHintTextColor(0x99FFFFFF);
        menuAddress.setHint("Search Google or type a site");
        menuAddress.setSelectAllOnFocus(true);
        menuAddress.setShowSoftInputOnFocus(false);     // the keyboard waits for a click, not for focus
        menuAddress.setImeOptions(EditorInfo.IME_ACTION_GO);
        menuAddress.setPadding(dp(20), dp(11), dp(20), dp(11));
        menuAddress.setBackground(glassPill(0x26FFFFFF, 0x33FFFFFF));
        menuAddress.setOnFocusChangeListener((v, has) ->
                menuAddress.setBackground(glassPill(has ? 0x40FFFFFF : 0x26FFFFFF, has ? 0xCC0A84FF : 0x33FFFFFF)));
        menuAddress.setOnClickListener(v -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(menuAddress, 0);
        });
        menuAddress.setOnEditorActionListener((v, id, ev) -> {
            String t = menuAddress.getText().toString();
            closeMenu();
            go(t);
            return true;
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        alp.rightMargin = dp(10);
        addr.addView(menuAddress, alp);
        addr.addView(new Icon(this, Icon.MIC, v -> { closeMenu(); startVoice(); }));
        LinearLayout.LayoutParams adl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        adl.topMargin = dp(16);
        sheet.addView(addr, adl);

        // Actions.
        menuActions = new LinearLayout(this);
        menuActions.setGravity(Gravity.CENTER_HORIZONTAL);
        menuActions.setClipChildren(false);
        menuActions.setClipToPadding(false);
        sheet.setClipChildren(false);
        sheet.setClipToPadding(false);
        LinearLayout.LayoutParams acl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acl.topMargin = dp(18);
        sheet.addView(menuActions, acl);

        // Bookmarks.
        TextView label = new TextView(this);
        label.setText("BOOKMARKS");
        label.setLetterSpacing(0.15f);
        label.setTextColor(0x99FFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(18);
        ll.bottomMargin = dp(8);
        sheet.addView(label, ll);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        menuMarks = new LinearLayout(this);
        menuMarks.setOrientation(LinearLayout.VERTICAL);
        sv.addView(menuMarks);
        sheet.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));

        root.addView(menu, match());
    }

    private void openMenu() {
        if (menu == null) buildMenu();
        String u = web.getUrl();
        boolean onPage = u != null && !u.startsWith("file:");
        String title = web.getTitle();
        menuTitle.setText(onPage ? (title == null || title.isEmpty() ? Shields.host(u) : title) : "Rad Browser");
        menuHost.setText(onPage ? Shields.host(u) : "Start page");
        menuAddress.setText(onPage ? u : "");
        boolean off = onPage && shieldsOffFor(Shields.host(u));
        menuShield.setText(off ? "Shields off here" : blockedOnPage.get() + " blocked");
        menuShield.setBackground(glassPill(off ? 0x33FF453A : 0x3334C759, off ? 0x66FF453A : 0x6634C759));

        menuActions.removeAllViews();
        View first = action(Icon.BACK, "Back", v -> { closeMenu(); if (web.canGoBack()) web.goBack(); else web.loadUrl(START); });
        menuActions.addView(first);
        menuActions.addView(action(Icon.FORWARD, "Forward", v -> { closeMenu(); if (web.canGoForward()) web.goForward(); }));
        menuActions.addView(action(Icon.RELOAD, "Reload", v -> { closeMenu(); web.reload(); }));
        menuActions.addView(action(Icon.HOME, "Home", v -> { closeMenu(); web.loadUrl(START); }));
        if (onPage) {
            boolean fav = isFavorite(u);
            menuActions.addView(action(Icon.STAR, fav ? "Saved" : "Bookmark", v -> { toggleFavorite(); openMenu(); }, fav));
        }
        menuActions.addView(action(desktop ? Icon.DESKTOP : Icon.PHONE, desktop ? "Desktop" : "Mobile", v -> { closeMenu(); toggleDesktop(); }));
        if (onPage) {
            String host = Shields.host(u);
            menuActions.addView(action(Icon.SHIELD, off ? "Shields on" : "Shields off", v -> {
                setShieldsOff(host, !shieldsOffFor(host));
                closeMenu();
                web.reload();
            }));
        }
        menuActions.addView(action(Icon.EXIT, "Exit", v -> { closeMenu(); finish(); }));

        buildMenuMarks();
        try {
            int w = root.getWidth() / 6, h = root.getHeight() / 6;
            Bitmap snap = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(snap);
            c.scale(1f / 6, 1f / 6);
            c.translate(-web.getScrollX(), -web.getScrollY());
            web.draw(c);
            menuBackdrop.setImageBitmap(snap);
            menuBackdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(14), dp(14),
                    android.graphics.Shader.TileMode.CLAMP));
        } catch (Exception ignored) { }

        menu.setVisibility(View.VISIBLE);
        menu.setAlpha(0f);
        menu.setScaleX(1.03f);
        menu.setScaleY(1.03f);
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
        cursor.setAlpha(0f);
        menuButton.setAlpha(0f);
        first.requestFocus();
    }

    private boolean menuOpen() { return menu != null && menu.getVisibility() == View.VISIBLE; }

    private void closeMenu() {
        if (!menuOpen()) return;
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(menuAddress.getWindowToken(), 0);
        menu.animate().alpha(0f).setDuration(140).withEndAction(() -> menu.setVisibility(View.GONE)).start();
        web.requestFocus();
        cursor.setAlpha(1f);
        menuButton.setAlpha(1f);
    }

    /** One menu action: a round glass icon with its name under it. */
    private View action(int kind, String name, View.OnClickListener l) { return action(kind, name, l, false); }

    private View action(int kind, String name, View.OnClickListener l, boolean filled) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setFocusable(true);
        col.setPadding(dp(4), dp(10), dp(4), dp(8));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setOnClickListener(l);
        Icon icon = new Icon(this, kind, null);
        icon.setFocusable(false);
        icon.setClickable(false);
        icon.setFilled(filled);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x26FFFFFF);
        ring.setStroke(dp(1), 0x33FFFFFF);
        icon.setBackground(ring);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(52), dp(52));
        col.addView(icon, ilp);
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(0xCCFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(8);
        col.addView(t, tlp);
        col.setOnFocusChangeListener((v, has) -> {
            GradientDrawable r = new GradientDrawable();
            r.setShape(GradientDrawable.OVAL);
            r.setColor(has ? 0xFF0A84FF : 0x26FFFFFF);
            r.setStroke(dp(1), has ? 0xCCFFFFFF : 0x33FFFFFF);
            icon.setBackground(r);
            t.setTextColor(has ? Color.WHITE : 0xCCFFFFFF);
            icon.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(130).start();
        });
        return col;
    }

    /** Bookmarks as glass tiles, five to a row. */
    private void buildMenuMarks() {
        menuMarks.removeAllViews();
        JSONArray f = favorites();
        LinearLayout row = null;
        for (int i = 0; i < f.length(); i++) {
            if (i % 5 == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rl.bottomMargin = dp(10);
                menuMarks.addView(row, rl);
            }
            JSONObject o = f.optJSONObject(i);
            final String url = o.optString("url");
            View chip = chip(o.optString("title"), url, v -> { closeMenu(); load(url); });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            cl.rightMargin = dp(10);
            row.addView(chip, cl);
        }
        if (row != null) while (row.getChildCount() < 5) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        if (f.length() == 0) {
            TextView t = new TextView(this);
            t.setText("No bookmarks yet. Open a site, press Back, and choose Bookmark.");
            t.setTextColor(0x99FFFFFF);
            menuMarks.addView(t);
        }
    }

    // Per site shields switch, for the odd site that breaks with blocking on.
    private boolean shieldsOffFor(String host) {
        return host != null && prefs.getStringSet("shieldsOff", java.util.Collections.emptySet()).contains(host);
    }

    private void setShieldsOff(String host, boolean off) {
        java.util.Set<String> set = new java.util.HashSet<>(prefs.getStringSet("shieldsOff", java.util.Collections.emptySet()));
        if (off) set.add(host); else set.remove(host);
        prefs.edit().putStringSet("shieldsOff", set).apply();
        toast(off ? "Shields off for " + host : "Shields on for " + host);
    }

    private GradientDrawable glassPill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(22));
        g.setStroke(dp(1), stroke);
        return g;
    }

    /** Blurs the strip of page the bar is about to cover and puts it behind the glass. */
    private void snapshotBehindBar() {
        try {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
            int w = root.getWidth() - lp.leftMargin - lp.rightMargin, h = lp.height;
            if (w <= 0) return;
            int scale = 1;
            Bitmap snap = Bitmap.createBitmap(Math.max(1, w / scale), Math.max(1, h / scale), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(snap);
            c.scale(1f / scale, 1f / scale);
            c.translate(-lp.leftMargin - web.getScrollX(), -lp.topMargin - web.getScrollY());
            web.draw(c);
            backdrop.setImageBitmap(snap);
            backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(1.5f), dp(1.5f),
                    android.graphics.Shader.TileMode.CLAMP));
        } catch (Exception ignored) { }
    }

    private void showBar(boolean show) {
        if (show) {
            String u = web.getUrl();
            address.setText(u == null || u.startsWith("file:") ? "" : u);
            starButton.setFilled(isFavorite(u));
            updateShieldLabel();
            snapshotBehindBar();
            bar.setVisibility(View.VISIBLE);
            bar.setAlpha(0f);
            bar.setScaleX(0.96f);
            bar.setScaleY(0.96f);
            bar.setTranslationY(-dp(12));
            bar.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(220)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
            buildMarks();
            marksScroll.setVisibility(View.VISIBLE);
            marksScroll.setAlpha(0f);
            marksScroll.setTranslationY(-dp(8));
            marksScroll.animate().alpha(1f).translationY(0).setStartDelay(60).setDuration(220).start();
            address.requestFocus();
            cursor.setAlpha(0f);
        } else {
            bar.animate().alpha(0f).translationY(-dp(12)).setDuration(160)
                    .withEndAction(() -> bar.setVisibility(View.GONE)).start();
            marksScroll.animate().alpha(0f).setStartDelay(0).setDuration(140)
                    .withEndAction(() -> marksScroll.setVisibility(View.GONE)).start();
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
            web.requestFocus();
            cursor.setAlpha(1f);
        }
    }

    private void go(String text) {
        String t = text.trim();
        if (t.isEmpty()) return;
        String url;
        if (t.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) url = t;
        else if (t.contains(".") && !t.contains(" ")) url = "https://" + t;
        else {
            try { url = "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8"); }
            catch (Exception e) { return; }
        }
        hideKeyboard();
        load(url);
        if (bar.getVisibility() == View.VISIBLE) showBar(false);
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(web.getWindowToken(), 0);
            imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
        }
        web.evaluateJavascript("document.activeElement&&document.activeElement.blur&&document.activeElement.blur()", null);
    }

    /** Opens a page, waiting (briefly) for the blocker the first time so no ad slips through at startup. */
    private void load(String url) {
        if (shields != null || !url.startsWith("http")) { web.loadUrl(url); return; }
        pendingUrl = url;
        main.postDelayed(() -> { if (pendingUrl != null) { web.loadUrl(pendingUrl); pendingUrl = null; } }, 2500);
    }
    private volatile String pendingUrl;

    // ------------------------------------------------------------------ voice

    private static final int VOICE = 41;

    /** Google's speech screen; what you say is searched, or opened if it sounds like a site. */
    private void startVoice() {
        android.content.Intent i = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Say a search or a site");
        try { startActivityForResult(i, VOICE); }
        catch (Exception e) { toast("Voice search is not available on this TV"); }
    }

    @Override
    protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE || result != RESULT_OK || data == null) return;
        java.util.ArrayList<String> said = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (said == null || said.isEmpty()) return;
        String t = said.get(0).trim();
        // "espn dot com" -> "espn.com"
        String site = t.toLowerCase(java.util.Locale.US).replace(" dot ", ".").replace(" ", "");
        go(site.matches("^[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?$") ? site : t);
    }

    private void toggleDesktop() {
        desktop = !desktop;
        prefs.edit().putBoolean("desktop", desktop).apply();
        web.getSettings().setUserAgentString(desktop ? DESKTOP_UA : null);
        uaButton.setKind(desktop ? Icon.DESKTOP : Icon.PHONE);
        toast(desktop ? "Desktop sites" : "Mobile sites");
        web.reload();
    }

    // ------------------------------------------------------------------ favorites

    private JSONArray favorites() {
        try { return new JSONArray(prefs.getString("favorites", defaultFavorites())); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static String defaultFavorites() {
        return "[]";   // favorites are only what the user saves
    }

    /** One time clean up: early builds seeded four sample favorites the user never added. */
    private void dropSampleFavorites() {
        if (prefs.getBoolean("samplesDropped", false)) return;
        java.util.Set<String> samples = new java.util.HashSet<>(java.util.Arrays.asList(
                "https://www.google.com", "https://www.espn.com", "https://www.reddit.com", "https://en.wikipedia.org"));
        JSONArray f = favorites(), out = new JSONArray();
        for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i);
            if (!samples.contains(o.optString("url"))) out.put(o);
        }
        prefs.edit().putString("favorites", out.toString()).putBoolean("samplesDropped", true).commit();
    }

    /**
     * Favorites added from the Mac: a list pushed over adb to files/add-favorites.json is merged in once
     * (skipping any already there) and the file removed.
     */
    private void importFavorites() {
        File f = new File(getExternalFilesDir(null), "add-favorites.json");
        if (!f.exists()) return;
        try {
            JSONArray add = new JSONArray(read(f)), cur = favorites();
            java.util.Set<String> have = new java.util.HashSet<>();
            for (int i = 0; i < cur.length(); i++) have.add(cur.optJSONObject(i).optString("url"));
            for (int i = 0; i < add.length(); i++) {
                JSONObject o = add.optJSONObject(i);
                if (o != null && o.has("url") && !have.contains(o.optString("url"))) cur.put(o);
            }
            prefs.edit().putString("favorites", cur.toString()).commit();
        } catch (Exception ignored) { }
        f.delete();
    }

    /**
    /** Keeps a readable copy of the favorites list next to the app, for checking from the Mac over adb. */
    private void mirrorFavorites() {
        try (FileOutputStream fo = new FileOutputStream(new File(getExternalFilesDir(null), "favorites.json"))) {
            fo.write(prefs.getString("favorites", "[]").getBytes("UTF-8"));
        } catch (Exception ignored) { }
    }

    private boolean isFavorite(String url) {
        if (url == null) return false;
        JSONArray f = favorites();
        for (int i = 0; i < f.length(); i++) if (url.equals(f.optJSONObject(i).optString("url"))) return true;
        return false;
    }

    private void toggleFavorite() {
        String url = web.getUrl();
        if (url == null || url.startsWith("file:")) return;
        JSONArray f = favorites(), out = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < f.length(); i++) {
            JSONObject o = f.optJSONObject(i);
            if (url.equals(o.optString("url"))) removed = true; else out.put(o);
        }
        if (!removed) {
            try {
                JSONObject o = new JSONObject();
                String title = web.getTitle();
                o.put("title", title == null || title.isEmpty() ? Shields.host(url) : title);
                o.put("url", url);
                out.put(o);
            } catch (Exception ignored) { }
        }
        prefs.edit().putString("favorites", out.toString()).commit();   // saved at once, survives a restart
        mirrorFavorites();
        starButton.setFilled(!removed);
        toast(removed ? "Removed from favorites" : "Added to favorites");
    }

    /** What the start page can ask for. Only the start page gets real answers. */
    private class Bridge {
        @JavascriptInterface
        public String favorites() {
            String u = pageUrl;
            return u != null && u.startsWith("file:///android_asset/") ? BrowserActivity.this.favorites().toString() : "[]";
        }

        @JavascriptInterface
        public String stats() {
            return "{\"total\":" + prefs.getLong("totalBlocked", 0) + ",\"rules\":"
                    + (shields == null ? 0 : shields.ruleCount) + "}";
        }

        @JavascriptInterface
        public void go(String text) {
            String u = pageUrl;
            if (u != null && u.startsWith("file:///android_asset/")) main.post(() -> BrowserActivity.this.go(text));
        }

        private boolean trusted() {
            String u = pageUrl;
            return u != null && u.startsWith("file:///android_asset/");
        }

        @JavascriptInterface
        public String get(String key) {
            return trusted() ? prefs.getString("page." + key, "") : "";
        }

        @JavascriptInterface
        public void set(String key, String value) {
            if (trusted()) prefs.edit().putString("page." + key, value).apply();
        }

        /** Lets a built in page read text from another site (a page cannot, by browser rules). */
        @JavascriptInterface
        public String fetchText(String url) {
            if (!trusted() || url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return "";
            try { return new String(getBytes(url), "UTF-8"); } catch (Exception e) { return "ERROR " + e.getMessage(); }
        }

        /** Fetches in the background and hands the text to window.radDone(id, text): no page freeze, no CORS. */
        @JavascriptInterface
        public void fetchAsync(String url, String id) {
            if (!trusted() || url == null || !url.startsWith("https://")) return;
            new Thread(() -> {
                String text;
                try { text = new String(getBytes(url), "UTF-8"); } catch (Exception e) { text = ""; }
                final String js = "window.radDone&&window.radDone(" + JSONObject.quote(id) + "," + JSONObject.quote(text) + ")";
                main.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        /**
         * Internet speed: ping is the best of three tiny requests, download is 25 MB pulled from Cloudflare's
         * speed test server and timed. Answers {down: Mbps, ping: ms} through radDone.
         */
        @JavascriptInterface
        public void speedTest(String cb) {
            if (!trusted()) return;
            new Thread(() -> {
                String out = "{}";
                try {
                    long ping = Long.MAX_VALUE;
                    for (int i = 0; i < 3; i++) {
                        long t = System.nanoTime();
                        getBytes("https://speed.cloudflare.com/__down?bytes=0");
                        ping = Math.min(ping, (System.nanoTime() - t) / 1000000L);
                    }
                    HttpURLConnection c = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(30000);
                    long t0 = System.nanoTime(), n = 0;
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) n += r;
                    } finally { c.disconnect(); }
                    double secs = (System.nanoTime() - t0) / 1e9;
                    long mbps = Math.round(n * 8 / secs / 1e6);
                    out = "{\"down\":" + mbps + ",\"ping\":" + ping + "}";
                } catch (Exception ignored) { }
                final String js = "window.radDone&&window.radDone(" + JSONObject.quote(cb) + "," + JSONObject.quote(out) + ")";
                main.post(() -> web.evaluateJavascript(js, null));
            }).start();
        }

        @JavascriptInterface
        public void keys(boolean on) {
            if (!trusted()) { pageKeys = false; return; }
            pageKeys = on;
            main.post(() -> {
                if (on) cursor.setAlpha(0f);
                else {
                    // handing back to the pointer: put it near the left, by the sidebar, and show it
                    if (cx > root.getWidth() * 0.4f) { cx = root.getWidth() * 0.25f; cursor.moveTo(cx, cy); }
                    cursor.animate().cancel();
                    cursor.setAlpha(1f);
                }
            });
        }

        @JavascriptInterface
        public void renameFavorite(String url, String title) {
            if (!trusted() || title == null || title.isEmpty()) return;
            JSONArray f = BrowserActivity.this.favorites();
            for (int i = 0; i < f.length(); i++) {
                JSONObject o = f.optJSONObject(i);
                if (url.equals(o.optString("url"))) { try { o.put("title", title); } catch (Exception ignored) { } }
            }
            prefs.edit().putString("favorites", f.toString()).commit();
            mirrorFavorites();
        }

        @JavascriptInterface
        public void removeFavorite(String url) {
            if (!trusted()) return;
            JSONArray f = BrowserActivity.this.favorites(), out = new JSONArray();
            for (int i = 0; i < f.length(); i++) {
                JSONObject o = f.optJSONObject(i);
                if (!url.equals(o.optString("url"))) out.put(o);
            }
            prefs.edit().putString("favorites", out.toString()).commit();   // saved at once, survives a restart
        }

        /** The last web page visited, for the start page's "Last page" tile. */
        @JavascriptInterface
        public String lastPage() {
            if (!trusted()) return "{}";
            try {
                JSONObject o = new JSONObject();
                o.put("url", prefs.getString("lastPage", ""));
                o.put("title", prefs.getString("lastTitle", ""));
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void voice() {
            if (trusted()) main.post(BrowserActivity.this::startVoice);
        }

        @JavascriptInterface
        public String css(String url) {
            Shields sh = shields;
            return sh == null ? "" : sh.css(url);
        }
    }

    // ------------------------------------------------------------------ web clients

    private class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            pageUrl = url;
            pageKeys = false;
            if (url != null && url.startsWith("http")) { progress.setScaleX(0f); showLoading(8); }
            else { progress.animate().cancel(); progress.setAlpha(0f); }
            int b = blockedOnPage.getAndSet(0);
            if (b > 0) prefs.edit().putLong("totalBlocked", prefs.getLong("totalBlocked", 0) + b).apply();
            injectCss(view);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) { injectCss(view); }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageUrl = url;
            if (url != null && url.startsWith("http")) {
                String t = view.getTitle();
                prefs.edit().putString("lastPage", url).putString("lastTitle", t == null ? "" : t).apply();
            }
            menuButton.setVisibility(url != null && url.startsWith("file:") ? View.GONE : View.VISIBLE);
            injectCss(view);
            updateShieldLabel();
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Shields sh = shields;
            if (sh == null || req.isForMainFrame()) return null;
            if (shieldsOffFor(Shields.host(pageUrl))) return null;
            String url = req.getUrl().toString();
            if (!url.startsWith("http")) return null;
            if (sh.shouldBlock(url, pageUrl)) {
                blockedOnPage.incrementAndGet();
                return new WebResourceResponse("text/plain", "utf-8", 200, "OK", null,
                        new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            String url = req.getUrl().toString();
            if (!url.startsWith("http") && !url.startsWith("file:")) return true;   // intent:, market:, app links
            if (!req.isForMainFrame() || req.isRedirect()) return false;
            String current = view.getUrl();
            if (current == null || current.startsWith("file:")) return false;
            boolean crossSite = !Shields.site(Shields.host(url)).equals(Shields.site(Shields.host(current)));
            Shields sh = shields;
            boolean listed = sh != null && sh.blockPopup(url, current);
            // A page sending you to another site on its own, with no click: a redirect hijack.
            if (listed || (crossSite && !req.hasGesture())) {
                redirectsBlocked++;
                toast("Blocked a redirect to " + Shields.host(url));
                return true;
            }
            return false;
        }
    }

    /**
     * Runs on every page: adds the element hiding CSS, and strips the sandbox attribute from embedded
     * players. Many embedded players refuse to play inside a sandboxed frame; with the
     * sandbox gone, Rad's own pop up and redirect blocking does the job the sandbox was doing.
     */
    private void injectCss(WebView view) {
        String unsandbox = "if(!window.__radU){window.__radU=1;"
                + "var fix=function(f){if(f.tagName!=='IFRAME'||!f.hasAttribute('sandbox'))return;f.removeAttribute('sandbox');"
                + "var s=f.getAttribute('src');if(s){f.setAttribute('src','about:blank');setTimeout(function(){f.setAttribute('src',s);},0);}};"
                + "var scan=function(n){if(!n||!n.querySelectorAll)return;fix(n);n.querySelectorAll('iframe[sandbox]').forEach(fix);};"
                + "new MutationObserver(function(ms){ms.forEach(function(m){if(m.type==='attributes')fix(m.target);"
                + "m.addedNodes&&m.addedNodes.forEach(scan);});}).observe(document.documentElement,"
                + "{childList:true,subtree:true,attributes:true,attributeFilter:['sandbox']});scan(document.documentElement);}";
        String css = shields == null || shieldsOffFor(Shields.host(pageUrl)) ? "" : "if(!document.getElementById('rad-shields')){var s=document.createElement('style');"
                + "s.id='rad-shields';s.textContent=Rad.css(location.href);"
                + "(document.head||document.documentElement).appendChild(s);}";
        view.evaluateJavascript("(function(){function add(){if(!document.documentElement){return setTimeout(add,30);}"
                + unsandbox + css + "}add();})()", null);
    }

    /** Shows the loading line at once and moves it with the page's progress. */
    private void showLoading(int p) {
        if (p >= 100) {
            progress.animate().scaleX(1f).setDuration(120)
                    .withEndAction(() -> progress.animate().alpha(0f).setDuration(250).start()).start();
        } else {
            progress.animate().cancel();
            progress.setAlpha(1f);
            progress.animate().scaleX(Math.max(0.08f, p / 100f)).setDuration(200).start();
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int p) {
            String u = view.getUrl();
            if (u != null && u.startsWith("file:")) return;
            showLoading(p);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            if (!isUserGesture) {
                popupsBlocked++;
                toast("Blocked a pop up");
                return false;
            }
            // Catch where the new window wants to go, then decide.
            WebView probe = new WebView(BrowserActivity.this);
            probe.setWebViewClient(new WebViewClient() {
                boolean decided;

                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    decide(r.getUrl().toString());
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String url, Bitmap f) {
                    if (url != null && !url.equals("about:blank")) decide(url);
                    v.stopLoading();
                }

                private void decide(String url) {
                    if (decided) return;
                    decided = true;
                    String current = web.getUrl();
                    Shields sh = shields;
                    boolean listed = sh != null && sh.blockPopup(url, current);
                    boolean sameSite = Shields.site(Shields.host(url)).equals(Shields.site(Shields.host(current)));
                    boolean wasTheLink = url.equals(lastClickHref);
                    if (!listed && (sameSite || wasTheLink)) {
                        web.loadUrl(url);    // one tab: open it here
                    } else {
                        popupsBlocked++;
                        toast("Blocked a pop up to " + Shields.host(url));
                    }
                    main.post(probe::destroy);
                }
            });
            WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
            t.setWebView(probe);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            customView = view;
            customCallback = callback;
            fullscreen.addView(view, match());
            fullscreen.setVisibility(View.VISIBLE);
            cursor.setAlpha(0f);
            menuButton.setVisibility(View.GONE);
        }

        @Override
        public void onHideCustomView() { exitFullscreen(); }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        cursor.setAlpha(1f);
        menuButton.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ helpers

    private void toast(String text) {
        hint.setText(text);
        hint.animate().cancel();
        hint.setAlpha(1f);
        hint.animate().alpha(0f).setStartDelay(2200).setDuration(500).start();
    }

    private static String read(File f) {
        try (InputStream in = new FileInputStream(f)) { return new String(readAll(in), "UTF-8"); }
        catch (Exception e) { return null; }
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "RadBrowser/1.0");
        try (InputStream in = c.getInputStream()) { return readAll(in); }
        finally { c.disconnect(); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /** A round glass button with a drawn icon. Focus brightens it and lifts it slightly. */
    private static class Icon extends View {
        static final int BACK = 0, FORWARD = 1, RELOAD = 2, HOME = 3, STAR = 4, DESKTOP = 5, PHONE = 6, BOOKMARKS = 7, TV = 8, MIC = 9, SHIELD = 10, EXIT = 11;
        private int kind;
        private boolean filled;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint focusFill = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(Context c, int kind, View.OnClickListener l) {
            super(c);
            this.kind = kind;
            float d = c.getResources().getDisplayMetrics().density;
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2.1f * d);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            solid.setColor(0xFFFFD60A);
            focusFill.setColor(0x4DFFFFFF);
            setFocusable(true);
            setOnClickListener(l);
            int size = Math.round(46 * d);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.leftMargin = Math.round(2 * d);
            setLayoutParams(lp);
            setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(140).start();
                invalidate();
            });
        }

        void setKind(int k) { kind = k; invalidate(); }
        void setFilled(boolean f) { filled = f; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (isFocused()) c.drawCircle(w / 2, h / 2, Math.min(w, h) / 2f, focusFill);
            float u = Math.min(w, h) / 46f * 0.95f;   // icons are drawn on a 24 unit grid, centred
            c.save();
            c.translate(w / 2 - 12 * u, h / 2 - 12 * u);
            Path p = new Path();
            switch (kind) {
                case BACK: p.moveTo(15 * u, 5 * u); p.lineTo(8 * u, 12 * u); p.lineTo(15 * u, 19 * u); break;
                case FORWARD: p.moveTo(9 * u, 5 * u); p.lineTo(16 * u, 12 * u); p.lineTo(9 * u, 19 * u); break;
                case RELOAD:
                    p.addArc(new android.graphics.RectF(5 * u, 5 * u, 19 * u, 19 * u), -60, 300);
                    p.moveTo(19.5f * u, 4.5f * u); p.lineTo(19 * u, 9.5f * u); p.lineTo(14 * u, 9 * u);
                    break;
                case HOME:
                    p.moveTo(4 * u, 11 * u); p.lineTo(12 * u, 4.5f * u); p.lineTo(20 * u, 11 * u);
                    p.moveTo(6.5f * u, 9.5f * u); p.lineTo(6.5f * u, 19.5f * u); p.lineTo(17.5f * u, 19.5f * u);
                    p.lineTo(17.5f * u, 9.5f * u);
                    p.moveTo(10.5f * u, 19.5f * u); p.lineTo(10.5f * u, 14.5f * u); p.lineTo(13.5f * u, 14.5f * u);
                    p.lineTo(13.5f * u, 19.5f * u);
                    break;
                case STAR: {
                    for (int i = 0; i < 10; i++) {
                        double a = Math.PI / 2 + i * Math.PI / 5;
                        float r = (i % 2 == 0 ? 8.5f : 3.6f) * u;
                        float x = 12 * u + (float) Math.cos(a) * r, y = 12.6f * u - (float) Math.sin(a) * r;
                        if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                    }
                    p.close();
                    if (filled) c.drawPath(p, solid);
                    break;
                }
                case DESKTOP:
                    p.addRoundRect(new android.graphics.RectF(4 * u, 5 * u, 20 * u, 16 * u), 1.5f * u, 1.5f * u, Path.Direction.CW);
                    p.moveTo(12 * u, 16 * u); p.lineTo(12 * u, 19.5f * u);
                    p.moveTo(8.5f * u, 19.5f * u); p.lineTo(15.5f * u, 19.5f * u);
                    break;
                case PHONE:
                    p.addRoundRect(new android.graphics.RectF(8 * u, 4 * u, 16 * u, 20 * u), 2 * u, 2 * u, Path.Direction.CW);
                    p.moveTo(11 * u, 17.2f * u); p.lineTo(13 * u, 17.2f * u);
                    break;
                case BOOKMARKS:
                    p.moveTo(7 * u, 4.5f * u); p.lineTo(17 * u, 4.5f * u); p.lineTo(17 * u, 19.5f * u);
                    p.lineTo(12 * u, 15.5f * u); p.lineTo(7 * u, 19.5f * u); p.close();
                    break;
                case SHIELD:
                    p.moveTo(12 * u, 3.5f * u); p.lineTo(19 * u, 6.5f * u); p.lineTo(19 * u, 11.5f * u);
                    p.cubicTo(19 * u, 16 * u, 15.5f * u, 19 * u, 12 * u, 20.5f * u);
                    p.cubicTo(8.5f * u, 19 * u, 5 * u, 16 * u, 5 * u, 11.5f * u);
                    p.lineTo(5 * u, 6.5f * u); p.close();
                    break;
                case EXIT:
                    p.moveTo(7 * u, 7 * u); p.lineTo(17 * u, 17 * u);
                    p.moveTo(17 * u, 7 * u); p.lineTo(7 * u, 17 * u);
                    break;
                case MIC:
                    p.addRoundRect(new android.graphics.RectF(9 * u, 3.5f * u, 15 * u, 14 * u), 3 * u, 3 * u, Path.Direction.CW);
                    p.addArc(new android.graphics.RectF(6 * u, 7 * u, 18 * u, 17 * u), 0, 180);
                    p.moveTo(12 * u, 17 * u); p.lineTo(12 * u, 20.5f * u);
                    break;
                case TV:
                    p.addRoundRect(new android.graphics.RectF(3.5f * u, 7 * u, 20.5f * u, 18.5f * u), 2 * u, 2 * u, Path.Direction.CW);
                    p.moveTo(8.5f * u, 3.5f * u); p.lineTo(12 * u, 7 * u); p.lineTo(15.5f * u, 3.5f * u);
                    break;
            }
            c.drawPath(p, stroke);
            c.restore();
        }
    }

    /**
     * The on screen pointer: a small glassy circle, clicks land on its centre. It is its own small view that
     * slides by translation, so moving it never repaints anything; only a click pulse redraws it.
     */
    private static class Cursor extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float r;
        private final int size;
        private float ringR, ringA, press = 1f;

        Cursor(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            r = 7.5f * d;
            size = Math.round(r * 2.4f * 2);
            fill.setColor(0xF20A84FF);
            edge.setColor(0xFFFFFFFF);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(1.6f * d);
            shade.setColor(0x4D000000);
            shade.setStyle(Paint.Style.STROKE);
            shade.setStrokeWidth(3.6f * d);
            ring.setColor(0xFF0A84FF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2f * d);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        int size() { return size; }

        void moveTo(float nx, float ny) {
            setTranslationX(nx - size / 2f);
            setTranslationY(ny - size / 2f);
        }

        void pulse() {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(300);
            a.addUpdateListener(v -> {
                float t = (float) v.getAnimatedValue();
                ringR = r * (1f + 1.2f * t);
                ringA = 1f - t;
                press = 1f - 0.22f * (float) Math.sin(Math.PI * t);
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas c) {
            float x = size / 2f, y = size / 2f;
            if (ringA > 0) {
                ring.setAlpha((int) (200 * ringA));
                c.drawCircle(x, y, ringR, ring);
            }
            float rr = r * press;
            c.drawCircle(x, y, rr, shade);
            c.drawCircle(x, y, rr, fill);
            c.drawCircle(x, y, rr, edge);
        }
    }
}
