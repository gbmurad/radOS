package com.zoro.home;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The radOS settings panel: liquid glass over the blurred home screen, driven by the D pad alone. It keeps its
 * own focus (a section list on the left, the open section's rows on the right) and takes the keys from
 * HomeActivity while open, so the home screen behind can never steal focus.
 */
class SettingsPanel extends FrameLayout {
    private static final int INK = 0xFF111111;

    private static class Item {
        String label;
        boolean header;
        Supplier<String> value;
        Runnable act;
    }

    private static final String[] SECTIONS = {"General", "Home screen", "Startup", "Display & sound", "Network",
            "Remotes & Bluetooth", "Apps", "Keyboard", "System"};

    private final HomeActivity a;
    private final SharedPreferences home, boot;
    private final View card, dim;
    private final LinearLayout listCol, rowCol;
    private final ScrollView listScroll, rowScroll;
    private final TextView title;
    private final List<View> listViews = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    private List<Item> items = new ArrayList<>();

    private int sec, focus = -1;
    private boolean inList = true, open;
    // a sub list (hidden apps, one app's actions) replaces the section's rows until Back
    private Supplier<List<Item>> subBuilder;
    private String subTitle;
    private int parentFocus;
    private String speed = "";
    private boolean speedBusy;

    SettingsPanel(HomeActivity act) {
        super(act);
        a = act;
        home = a.getSharedPreferences("home", Context.MODE_PRIVATE);
        boot = a.createDeviceProtectedStorageContext().getSharedPreferences("boot", Context.MODE_PRIVATE);
        setClipChildren(false);

        dim = new View(a);
        dim.setBackgroundColor(0x66000000);
        addView(dim, new LayoutParams(-1, -1));

        int w = Math.round(a.getResources().getDisplayMetrics().widthPixels * 0.64f);
        card = new FrameLayout(a);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xCC16161C);
        g.setStroke(a.dp(1), 0x2EFFFFFF);
        g.setCornerRadius(a.dp(28));
        card.setBackground(g);
        LayoutParams clp = new LayoutParams(w, -1, Gravity.END);
        clp.setMargins(0, a.dp(24), a.dp(24), a.dp(24));
        addView(card, clp);

        View hi = new View(a);
        hi.setBackgroundColor(0x40FFFFFF);
        LayoutParams hlp = new LayoutParams(-1, a.dp(1));
        hlp.setMargins(a.dp(40), a.dp(1), a.dp(40), 0);   // inside the straight part of the top edge, clear of the corners
        ((FrameLayout) card).addView(hi, hlp);

        LinearLayout cols = new LinearLayout(a);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setPadding(a.dp(28), a.dp(28), a.dp(28), a.dp(28));
        ((FrameLayout) card).addView(cols, new LayoutParams(-1, -1));

        listScroll = scroller();
        listCol = column();
        listScroll.addView(listCol);
        cols.addView(listScroll, new LinearLayout.LayoutParams(0, -1, 37));

        LinearLayout right = new LinearLayout(a);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, -1, 63);
        rlp.leftMargin = a.dp(24);
        cols.addView(right, rlp);
        title = a.text(24, a.sansMedium, Color.WHITE);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(a.dp(18), 0, 0, a.dp(14));
        right.addView(title);
        rowScroll = scroller();
        rowCol = column();
        rowScroll.addView(rowCol);
        right.addView(rowScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setVisibility(GONE);
    }

    private ScrollView scroller() {
        ScrollView s = new ScrollView(a);
        s.setVerticalScrollBarEnabled(false);
        s.setFocusable(false);
        s.setClipToPadding(false);
        return s;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    boolean isOpen() { return open; }

    // ---------------------------------------------------------------- open and close

    void open(ViewGroup parent, View blurTarget) {
        if (open) return;
        open = true;
        sec = 0;
        inList = true;
        subBuilder = null;
        rebuildList();
        rebuildRows();
        parent.addView(this, new ViewGroup.LayoutParams(-1, -1));
        setVisibility(VISIBLE);
        dim.setAlpha(0f);
        card.setAlpha(0f);
        card.setTranslationX(a.dp(120));
        dim.animate().alpha(1f).setDuration(260).start();
        card.animate().translationX(0).alpha(1f).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        blur(blurTarget, 0f, 28f, 260, new DecelerateInterpolator());
    }

    void close(View blurTarget, Runnable after) {
        if (!open) return;
        open = false;
        dim.animate().alpha(0f).setDuration(200).start();
        card.animate().translationX(a.dp(120)).alpha(0f).setDuration(200).setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (getParent() != null) ((ViewGroup) getParent()).removeView(this);
                    if (after != null) after.run();
                }).start();
        blur(blurTarget, 28f, 0f, 200, new AccelerateInterpolator());
    }

    private void blur(View target, float from, float to, long ms, android.animation.TimeInterpolator ip) {
        ValueAnimator v = ValueAnimator.ofFloat(from, to);
        v.setDuration(ms);
        v.setInterpolator(ip);
        v.addUpdateListener(x -> {
            float r = (float) x.getAnimatedValue();
            // a radius of 0 is rejected; the effect is dropped at the end instead
            target.setRenderEffect(r < 0.5f ? null : RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
        });
        v.start();
    }

    // ---------------------------------------------------------------- keys

    /** Returns true when the key is the panel's (it takes the D pad, OK and Back). */
    boolean key(KeyEvent e) {
        int c = e.getKeyCode();
        boolean ok = c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER;
        boolean mine = ok || c == KeyEvent.KEYCODE_BACK || c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_DOWN
                || c == KeyEvent.KEYCODE_DPAD_LEFT || c == KeyEvent.KEYCODE_DPAD_RIGHT;
        if (!mine) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        boolean first = e.getRepeatCount() == 0;
        switch (c) {
            case KeyEvent.KEYCODE_DPAD_UP: move(-1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (first && inList) enterRows(); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (first && !inList) back(); break;
            case KeyEvent.KEYCODE_BACK: if (first) { if (inList) a.closeSettings(); else back(); } break;
            default:
                if (first) { if (inList) enterRows(); else activate(); }
        }
        return true;
    }

    private void move(int d) {
        if (inList) {
            int n = Math.max(0, Math.min(SECTIONS.length - 1, sec + d));
            if (n == sec) return;
            sec = n;
            subBuilder = null;
            styleList();
            rebuildRows();
            rowScroll.scrollTo(0, 0);
        } else {
            int i = focus + d;
            while (i >= 0 && i < items.size() && items.get(i).header) i += d;
            if (i < 0 || i >= items.size()) return;
            focus = i;
            styleRows();
            ensureVisible(rowScroll, rowViews.get(focus));
        }
    }

    private int firstRow() {
        for (int i = 0; i < items.size(); i++) if (!items.get(i).header) return i;
        return -1;
    }

    private void enterRows() {
        int f = firstRow();
        if (f < 0) return;
        inList = false;
        focus = f;
        styleList();
        styleRows();
        ensureVisible(rowScroll, rowViews.get(focus));
    }

    private void back() {
        if (subBuilder != null) {
            subBuilder = null;
            rebuildRows();
            focus = Math.max(0, Math.min(items.size() - 1, parentFocus));
            styleRows();
            ensureVisible(rowScroll, rowViews.get(focus));
            return;
        }
        inList = true;
        styleList();
        styleRows();
    }

    private void activate() {
        if (focus < 0 || focus >= items.size()) return;
        Item it = items.get(focus);
        if (it.act == null) return;
        it.act.run();
        // the action may have changed values (or closed the panel); redraw the rows where they are
        if (open && (subBuilder != null || !inList)) refreshRows();
    }

    /** Opens a sub list in place of the section's rows. */
    private void openSub(String t, Supplier<List<Item>> b) {
        parentFocus = focus;
        subTitle = t;
        subBuilder = b;
        rebuildRows();
        focus = firstRow();
        styleRows();
    }

    // ---------------------------------------------------------------- drawing

    private void rebuildList() {
        listCol.removeAllViews();
        listViews.clear();
        for (String s : SECTIONS) {
            LinearLayout r = rowView(46, s, null);
            listViews.add(r);
            listCol.addView(r, rowLp(46));
        }
        styleList();
    }

    private void styleList() {
        for (int i = 0; i < listViews.size(); i++) {
            boolean focused = inList && i == sec, picked = !inList && i == sec;
            paint((LinearLayout) listViews.get(i), focused, picked);
        }
        if (inList) ensureVisible(listScroll, listViews.get(sec));
    }

    /** Rebuilds the right column from the section (or the open sub list). */
    private void rebuildRows() {
        items = subBuilder != null ? subBuilder.get() : build(sec);
        title.setText(subBuilder != null ? subTitle : SECTIONS[sec]);
        rowCol.removeAllViews();
        rowViews.clear();
        for (Item it : items) {
            if (it.header) {
                TextView h = a.text(11, a.sansMedium, 0x8CFFFFFF);
                h.setText(it.label.toUpperCase(Locale.US));
                h.setLetterSpacing(0.12f);
                h.setPadding(a.dp(18), a.dp(18), 0, a.dp(6));
                rowViews.add(h);
                rowCol.addView(h);
            } else {
                LinearLayout r = rowView(52, it.label, it.value);
                rowViews.add(r);
                rowCol.addView(r, rowLp(52));
            }
        }
        if (focus >= items.size() || (focus >= 0 && items.get(focus).header)) focus = firstRow();
        styleRows();
    }

    /** Same rows, new values; keeps the focused row and the scroll place. */
    private void refreshRows() {
        int keep = focus, y = rowScroll.getScrollY();
        rebuildRows();
        focus = keep >= items.size() ? firstRow() : keep;
        styleRows();
        rowScroll.post(() -> rowScroll.scrollTo(0, y));
    }

    private void styleRows() {
        for (int i = 0; i < rowViews.size(); i++) {
            if (items.get(i).header) continue;
            paint((LinearLayout) rowViews.get(i), !inList && i == focus, false);
        }
    }

    private LinearLayout.LayoutParams rowLp(int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.dp(h));
        lp.bottomMargin = a.dp(4);
        return lp;
    }

    private LinearLayout rowView(int h, String label, Supplier<String> value) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(a.dp(18), 0, a.dp(18), 0);
        TextView l = a.text(16, a.sans, Color.WHITE);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        if (value != null) {
            TextView v = a.text(15, a.sans, 0x99FFFFFF);
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setMaxWidth(a.dp(210));
            v.setGravity(Gravity.END);
            v.setText(value.get());
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
            vl.leftMargin = a.dp(12);
            r.addView(v, vl);
        }
        return r;
    }

    private void paint(LinearLayout r, boolean focused, boolean picked) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(a.dp(14));
        g.setColor(focused ? 0xF2FFFFFF : picked ? 0x24FFFFFF : Color.TRANSPARENT);
        r.setBackground(g);
        ((TextView) r.getChildAt(0)).setTextColor(focused ? INK : Color.WHITE);
        if (r.getChildCount() > 1) ((TextView) r.getChildAt(1)).setTextColor(focused ? INK : 0x99FFFFFF);
    }

    private void ensureVisible(ScrollView s, View v) {
        s.post(() -> {
            int top = v.getTop() - a.dp(8), bot = v.getBottom() + a.dp(8);
            if (top < s.getScrollY()) s.smoothScrollTo(0, Math.max(0, top));
            else if (bot > s.getScrollY() + s.getHeight()) s.smoothScrollTo(0, bot - s.getHeight());
        });
    }

    // ---------------------------------------------------------------- item helpers

    private Item header(String t) {
        Item i = new Item();
        i.header = true;
        i.label = t;
        return i;
    }

    private Item row(String label, Supplier<String> value, Runnable act) {
        Item i = new Item();
        i.label = label;
        i.value = value;
        i.act = act;
        return i;
    }

    private Item action(String label, Runnable act) { return row(label, null, act); }

    private Item toggle(String label, Supplier<Boolean> get, Consumer<Boolean> set) {
        return row(label, () -> get.get() ? "On" : "Off", () -> set.accept(!get.get()));
    }

    /** Opens a system Settings screen; falls back to the main Settings app. */
    private Runnable open(String act) {
        return () -> {
            a.closeSettings();
            a.main.postDelayed(() -> {
                try { a.startActivity(new Intent(act).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception e) { fallback(); }
            }, 250);
        };
    }

    private void fallback() {
        try { a.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception ignored) { }
    }

    private static final String GOOGLE = "Change in Google settings";

    // ---------------------------------------------------------------- sections

    private List<Item> build(int s) {
        List<Item> l = new ArrayList<>();
        switch (s) {
            case 0: general(l); break;
            case 1: homeScreen(l); break;
            case 2: startup(l); break;
            case 3: display(l); break;
            case 4: network(l); break;
            case 5:
                l.add(row("Pair a remote or headphones", null, open(Settings.ACTION_BLUETOOTH_SETTINGS)));
                cec(l);
                break;
            case 6: apps(l); break;
            case 7: l.add(row("Glass Keys", null, open(Settings.ACTION_INPUT_METHOD_SETTINGS))); break;
            default: system(l);
        }
        return l;
    }

    private void general(List<Item> l) {
        l.add(row("Your name", () -> home.getString("name", "").isEmpty() ? "Not set" : home.getString("name", ""), () ->
                prompt("Your name", home.getString("name", ""), v -> {
                    home.edit().putString("name", v).apply();
                    a.updateGreeting();
                    refreshRows();
                })));
        l.add(row("Clock", () -> home.getBoolean("clock24", false) ? "24 hour" : "12 hour", () -> {
            home.edit().putBoolean("clock24", !home.getBoolean("clock24", false)).apply();
            a.applyClock();
        }));
        l.add(row("Temperature", () -> home.getBoolean("celsius", false) ? "°C" : "°F", () -> {
            home.edit().putBoolean("celsius", !home.getBoolean("celsius", false)).apply();
            a.refreshWeather();
        }));
        l.add(row("Weather location", () -> home.getString("place", "Denver"), () ->
                prompt("City for the weather", "", v -> lookupCity(v))));
    }

    private void homeScreen(List<Item> l) {
        l.add(action("Next wallpaper", a::nextWallpaper));
        l.add(header("Widgets"));
        l.add(toggle("Scores widget", () -> !home.getBoolean("w.scores.hidden", false), v -> {
            home.edit().putBoolean("w.scores.hidden", !v).apply();
            a.applyWidgetVisibility();
        }));
        l.add(toggle("Calendar widget", () -> !home.getBoolean("w.agenda.hidden", false), v -> {
            home.edit().putBoolean("w.agenda.hidden", !v).apply();
            a.applyWidgetVisibility();
        }));
        l.add(header("Apps and layout"));
        l.add(row("Hidden apps", () -> hiddenSet().size() + " hidden", () -> openSub("Hidden apps", this::hiddenList)));
        l.add(action("Reset layout", a::resetLayout));
    }

    private java.util.Set<String> hiddenSet() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (String p : home.getString("hiddenApps", "").split(",")) if (!p.isEmpty()) s.add(p);
        return s;
    }

    private List<Item> hiddenList() {
        List<Item> l = new ArrayList<>();
        l.add(header("A check hides the app from the row"));
        java.util.Set<String> hid = hiddenSet();
        for (String[] app : a.allApps) {
            String pkg = app[0];
            l.add(row(app[1], () -> hiddenSet().contains(pkg) ? "✓" : "", () -> {
                java.util.Set<String> h = hiddenSet();
                if (!h.remove(pkg)) h.add(pkg);
                home.edit().putString("hiddenApps", TextUtils.join(",", h)).apply();
                a.loadApps();
            }));
        }
        return l;
    }

    private void startup(List<Item> l) {
        l.add(toggle("Startup animation", () -> boot.getBoolean("splashOn", true), v -> boot.edit().putBoolean("splashOn", v).commit()));
        l.add(toggle("Startup sound", () -> boot.getBoolean("chimeOn", true), v -> boot.edit().putBoolean("chimeOn", v).commit()));
        int[] vols = {25, 50, 70, 100};
        l.add(row("Chime volume", () -> boot.getInt("chimeVol", 70) + " %", () -> {
            int cur = boot.getInt("chimeVol", 70), n = vols[0];
            for (int i = 0; i < vols.length; i++) if (vols[i] == cur) n = vols[(i + 1) % vols.length];
            boot.edit().putInt("chimeVol", n).commit();
        }));
        l.add(action("Preview", a::previewSplash));
    }

    private void display(List<Item> l) {
        // Screen timeout (Settings.System) and sleep (Settings.Secure) need rights the app only has when granted
        // over adb; without them OK opens Google's own screen and the row says so.
        long[] saver = {5 * 60000L, 10 * 60000L, 15 * 60000L, 30 * 60000L, Integer.MAX_VALUE};
        String[] saverN = {"5 min", "10 min", "15 min", "30 min", "Never"};
        l.add(row("Screensaver after", () -> {
            if (!Settings.System.canWrite(a)) return GOOGLE;
            return label(saver, saverN, Settings.System.getLong(a.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0), "min");
        }, () -> {
            if (!Settings.System.canWrite(a)) { open(Settings.ACTION_DISPLAY_SETTINGS).run(); return; }
            long cur = Settings.System.getLong(a.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0);
            try { Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, (int) next(saver, cur)); }
            catch (Exception e) { open(Settings.ACTION_DISPLAY_SETTINGS).run(); }
        }));
        long[] sleep = {3600000L, 7200000L, 14400000L, -1};
        String[] sleepN = {"1 h", "2 h", "4 h", "Never"};
        boolean secure = a.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
        l.add(row("Sleep after", () -> secure ? label(sleep, sleepN, Settings.Secure.getLong(a.getContentResolver(), "sleep_timeout", 0), "h") : GOOGLE, () -> {
            if (!secure) { open(Settings.ACTION_DISPLAY_SETTINGS).run(); return; }
            long cur = Settings.Secure.getLong(a.getContentResolver(), "sleep_timeout", 0);
            try { Settings.Secure.putInt(a.getContentResolver(), "sleep_timeout", (int) next(sleep, cur)); }
            catch (Exception e) { open(Settings.ACTION_DISPLAY_SETTINGS).run(); }
        }));
        long[] sur = {0, 1, 2};
        String[] surN = {"Auto", "Never", "Always"};
        l.add(row("Surround sound", () -> secure ? label(sur, surN, Settings.Global.getLong(a.getContentResolver(), "encoded_surround_output", 0), "") : GOOGLE, () -> {
            if (!secure) { open(Settings.ACTION_SOUND_SETTINGS).run(); return; }
            long cur = Settings.Global.getLong(a.getContentResolver(), "encoded_surround_output", 0);
            try { Settings.Global.putInt(a.getContentResolver(), "encoded_surround_output", (int) next(sur, cur)); }
            catch (Exception e) { open(Settings.ACTION_SOUND_SETTINGS).run(); }
        }));
        l.add(row("Resolution & HDR", null, open(Settings.ACTION_DISPLAY_SETTINGS)));
    }

    /** The option's name; a value that is none of them is shown as it is. */
    private static String label(long[] vals, String[] names, long cur, String unit) {
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) return names[i];
        if (cur <= 0 || cur >= Integer.MAX_VALUE) return "Never";
        // a value off the list (Google's own default is 20 min) is shown in whole hours only when it is whole hours
        return cur % 3600000 == 0 ? (cur / 3600000) + " h" : (cur / 60000) + " min";
    }

    private static long next(long[] vals, long cur) {
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) return vals[(i + 1) % vals.length];
        return vals[0];
    }

    private void network(List<Item> l) {
        ConnectivityManager cm = a.getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
        String conn = caps == null ? "Not connected" : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : "Other";
        String ip = "—";
        if (lp != null) for (LinkAddress la : lp.getLinkAddresses())
            if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) { ip = la.getAddress().getHostAddress(); break; }
        String ipv = ip;
        String sp = caps == null || caps.getLinkDownstreamBandwidthKbps() <= 0 ? "—" : (caps.getLinkDownstreamBandwidthKbps() / 1000) + " Mbps";
        l.add(row("Connection", () -> conn, null));
        l.add(row("IP address", () -> ipv, null));
        l.add(row("Link speed", () -> sp, null));
        l.add(row("Speed test", () -> speed, this::speedTest));
        l.add(row("Wi-Fi settings", null, open(Settings.ACTION_WIFI_SETTINGS)));
    }

    /** HDMI CEC: real switches when the secure settings right is granted, else Google's screen. */
    private void cec(List<Item> l) {
        boolean secure = a.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
        if (!secure) { l.add(row("TV control (HDMI CEC)", () -> GOOGLE, open("android.settings.HDMI_CEC_SETTINGS"))); return; }
        String[][] keys = {{"TV control (HDMI CEC)", "hdmi_control_enabled", "1"},
                {"Wake the TV with the device", "hdmi_control_auto_wakeup_enabled", "1"},
                {"Turn off devices with the TV", "hdmi_control_auto_device_off_enabled", "1"}};
        for (String[] k : keys) {
            l.add(toggle(k[0], () -> Settings.Global.getInt(a.getContentResolver(), k[1], 1) == 1, v -> {
                try { Settings.Global.putInt(a.getContentResolver(), k[1], v ? 1 : 0); }
                catch (Exception e) { open("android.settings.HDMI_CEC_SETTINGS").run(); }
            }));
        }
        l.add(row("More CEC options", null, open("android.settings.HDMI_CEC_SETTINGS")));
    }

    private void apps(List<Item> l) {
        for (String[] app : a.allApps) {
            l.add(row(app[1], null, () -> openSub(app[1], () -> {
                List<Item> s = new ArrayList<>();
                s.add(action("Open", () -> { a.closeSettings(); a.main.postDelayed(() -> a.launch(app[0]), 250); }));
                s.add(row("App info", null, () -> {
                    a.closeSettings();
                    a.main.postDelayed(() -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app[0]))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception e) { fallback(); }
                    }, 250);
                }));
                return s;
            })));
        }
    }

    private void system(List<Item> l) {
        l.add(header("About"));
        l.add(row("Model", () -> Build.MODEL, null));
        l.add(row("Android", () -> Build.VERSION.RELEASE, null));
        String ver = "";
        try { ver = a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        String verF = ver;
        l.add(row("radOS", () -> verF, null));
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        String store = gb(fs.getAvailableBytes()) + " free of " + gb(fs.getTotalBytes());
        l.add(row("Storage", () -> store, null));
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        a.getSystemService(ActivityManager.class).getMemoryInfo(mi);
        String mem = gb(mi.availMem) + " free of " + gb(mi.totalMem);
        l.add(row("Memory", () -> mem, null));
        long up = SystemClock.elapsedRealtime() / 60000;
        String upS = up >= 1440 ? (up / 1440) + " d " + (up % 1440 / 60) + " h" : up >= 60 ? (up / 60) + " h " + (up % 60) + " min" : up + " min";
        l.add(row("Up time", () -> upS, null));
        l.add(header("Device"));
        l.add(action("Restart", () -> {
            HomeGuard g = HomeGuard.instance;
            if (g != null) { a.closeSettings(); g.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG); }
            else fallback();
        }));
        l.add(row("Date & time", null, open(Settings.ACTION_DATE_SETTINGS)));
        l.add(row("Language", null, open(Settings.ACTION_LOCALE_SETTINGS)));
        l.add(action("All Google settings", () -> {
            a.closeSettings();
            a.main.postDelayed(() -> {
                Intent i = a.getPackageManager().getLeanbackLaunchIntentForPackage("com.android.tv.settings");
                if (i == null) i = a.getPackageManager().getLaunchIntentForPackage("com.android.tv.settings");
                try { a.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { fallback(); }
            }, 250);
        }));
    }

    private static String gb(long b) { return String.format(Locale.US, "%.1f GB", b / 1e9); }

    // ---------------------------------------------------------------- work off the main thread

    private void speedTest() {
        if (speedBusy) return;
        speedBusy = true;
        speed = "Testing…";
        refreshRows();
        try {
            a.io.execute(() -> {
                String res;
                try {
                    long t0 = SystemClock.elapsedRealtime();
                    HttpURLConnection h = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=0").openConnection();
                    h.setRequestMethod("HEAD");
                    h.setConnectTimeout(8000);
                    h.setReadTimeout(8000);
                    h.getResponseCode();
                    h.disconnect();
                    long ping = SystemClock.elapsedRealtime() - t0;
                    HttpURLConnection c = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=25000000").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    long t1 = SystemClock.elapsedRealtime(), total = 0;
                    try (java.io.InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) total += n;
                    } finally { c.disconnect(); }
                    double secs = Math.max(0.001, (SystemClock.elapsedRealtime() - t1) / 1000.0);
                    res = Math.round(total * 8 / secs / 1e6) + " Mbps  ·  " + ping + " ms";
                } catch (Exception e) { res = "Failed"; }
                String out = res;
                a.main.post(() -> { speed = out; speedBusy = false; if (open) refreshRows(); });
            });
        } catch (Exception e) { speed = "Failed"; speedBusy = false; }
    }

    private void lookupCity(String city) {
        if (city.trim().isEmpty()) return;
        try {
            a.io.execute(() -> {
                try {
                    String body = new String(HomeActivity.getBytes("https://geocoding-api.open-meteo.com/v1/search?name="
                            + URLEncoder.encode(city.trim(), "UTF-8") + "&count=1"), "UTF-8");
                    JSONArray r = new JSONObject(body).optJSONArray("results");
                    if (r == null || r.length() == 0) { a.main.post(() -> Toast.makeText(a, "No place called " + city, Toast.LENGTH_LONG).show()); return; }
                    JSONObject p = r.getJSONObject(0);
                    home.edit().putString("place", p.getString("name")).putString("lat", String.valueOf(p.getDouble("latitude")))
                            .putString("lon", String.valueOf(p.getDouble("longitude"))).apply();
                    a.main.post(() -> { a.refreshWeather(); if (open) refreshRows(); });
                } catch (Exception e) { a.main.post(() -> Toast.makeText(a, "Could not look that up", Toast.LENGTH_LONG).show()); }
            });
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- text entry

    private void prompt(String head, String initial, Consumer<String> done) {
        Dialog d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
        FrameLayout f = new FrameLayout(a);
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(a.dp(28), a.dp(24), a.dp(28), a.dp(24));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF016161C);
        g.setStroke(a.dp(1), 0x2EFFFFFF);
        g.setCornerRadius(a.dp(28));
        c.setBackground(g);
        TextView t = a.text(20, a.sansMedium, Color.WHITE);
        t.setText(head);
        t.setPadding(0, 0, 0, a.dp(14));
        c.addView(t);
        EditText e = new EditText(a);
        e.setText(initial);
        e.setSelection(initial.length());
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        e.setTypeface(a.sans);
        e.setTextColor(Color.WHITE);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12));
        GradientDrawable eg = new GradientDrawable();
        eg.setColor(0x24FFFFFF);
        eg.setCornerRadius(a.dp(14));
        e.setBackground(eg);
        c.addView(e, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = a.text(13, a.sans, 0x99FFFFFF);
        hint.setText("Press Done on the keyboard to save, Back to cancel");
        hint.setPadding(a.dp(4), a.dp(12), 0, 0);
        c.addView(hint);
        e.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_DONE) { d.dismiss(); done.accept(e.getText().toString().trim()); return true; }
            return false;
        });
        // near the top: the keyboard takes the lower half of the screen and used to cover a centred box
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(a.dp(520), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clp.topMargin = a.dp(48);
        f.addView(c, clp);
        d.setContentView(f, new ViewGroup.LayoutParams(-1, -1));
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        d.show();
        e.requestFocus();
    }

    // ---------------------------------------------------------------- the gear

    /** The 40 dp glass gear button for the clock line. */
    static class Gear extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), disc = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path body = new Path();

        Gear(Context c) {
            super(c);
            setFocusable(true);
            float d = c.getResources().getDisplayMetrics().density;
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(d);
            ring.setColor(0x40FFFFFF);
            fill.setStyle(Paint.Style.FILL);
            setOnFocusChangeListener((v, has) -> invalidate());
        }

        @Override protected void onDraw(Canvas cv) {
            boolean f = isFocused();
            float w = getWidth(), cx = w / 2f, cy = getHeight() / 2f, d = getResources().getDisplayMetrics().density;
            disc.setColor(f ? 0xF2FFFFFF : 0x33FFFFFF);
            cv.drawCircle(cx, cy, w / 2f - d * 0.5f, disc);
            if (!f) cv.drawCircle(cx, cy, w / 2f - d * 0.5f, ring);
            fill.setColor(f ? 0xFF111111 : Color.WHITE);
            float r = 11 * d;                        // outer reach of the teeth
            body.reset();
            body.setFillType(Path.FillType.EVEN_ODD);
            body.addCircle(cx, cy, r * 0.72f, Path.Direction.CW);
            body.addCircle(cx, cy, r * 0.30f, Path.Direction.CW);
            cv.drawPath(body, fill);
            RectF tooth = new RectF(cx - r * 0.18f, cy - r, cx + r * 0.18f, cy - r * 0.55f);
            for (int i = 0; i < 8; i++) {
                cv.save();
                cv.rotate(i * 45f, cx, cy);
                cv.drawRoundRect(tooth, r * 0.1f, r * 0.1f, fill);
                cv.restore();
            }
        }
    }
}
