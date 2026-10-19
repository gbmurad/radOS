package com.zoro.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Makes our screen the home screen on Google TV, which will not let its own launcher be switched off.
 *  1. A short press of the remote's Home button opens our screen directly, before Google's starts (fast).
 *     A long press still does what Google TV does with it (the quick settings panel).
 *  2. Fallback: if Google's home screen shows up any other way, ours is put on top of it.
 * It reads no screen content and only handles the Home key.
 */
public class HomeGuard extends AccessibilityService {
    private static final long LONG_PRESS_MS = 500;
    static volatile HomeGuard instance;   // lets the settings panel open the power menu
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean homeDown, longFired;
    private final Runnable longPress = () -> {
        longFired = true;
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
    };

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getKeyCode() != KeyEvent.KEYCODE_HOME) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) logKey(e);
            return false;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                homeDown = true;
                longFired = false;
                main.postDelayed(longPress, LONG_PRESS_MS);
            }
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_UP && homeDown) {
            homeDown = false;
            main.removeCallbacks(longPress);
            if (!longFired) openHome();
            return true;
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cls = e.getClassName();
        // Google's home screen, or (with it removed) the setup helper's empty recovery screen.
        if (cls == null || !(cls.toString().endsWith(".home.HomeActivity") || cls.toString().endsWith("RecoveryActivity"))) return;
        openHome();
    }

    /** Notes each button press (code and name) so remote buttons can be identified for remapping. */
    private void logKey(KeyEvent e) {
        try (java.io.FileWriter w = new java.io.FileWriter(new java.io.File(getExternalFilesDir(null), "keys.txt"), true)) {
            w.write(System.currentTimeMillis() + " " + e.getKeyCode() + " " + KeyEvent.keyCodeToString(e.getKeyCode())
                    + " scan=" + e.getScanCode() + " dev=" + e.getDeviceId() + "\n");
        } catch (Exception ignored) { }
    }

    private void openHome() {
        if (BootSplash.showing) return;       // the startup animation is playing; it hands over by itself
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
    }

    /**
     * Startup: Android connects this helper early while the TV boots. Open our home screen right away, and again
     * a few times over the first seconds, so Google's home screen is only ever on screen for a moment.
     */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        long up = android.os.SystemClock.elapsedRealtime();
        if (up > 3 * 60 * 1000L) return;   // only right after a restart, not when the helper is switched on later
        // Once per power on: an app update restarts this helper too, and it must not then pull whatever is
        // being watched back to the home screen (2026-10-19 it closed YouTube 2.7 minutes after a restart).
        int boot = android.provider.Settings.Global.getInt(getContentResolver(), "boot_count", -1);
        android.content.SharedPreferences p = createDeviceProtectedStorageContext().getSharedPreferences("guard", MODE_PRIVATE);
        if (p.getInt("bootHandled", -2) == boot) return;
        p.edit().putInt("bootHandled", boot).commit();
        openHome();
        for (long d : new long[]{400, 1000, 2000, 3500, 6000}) main.postDelayed(this::openHome, d);
        // Android starts Google's home screen at boot anyway; once ours is up, let it go to free its memory.
        for (long d : new long[]{12000, 30000}) main.postDelayed(() -> {
            try {
                getSystemService(android.app.ActivityManager.class)
                        .killBackgroundProcesses("com.google.android.apps.tv.launcherx");
            } catch (Exception ignored) { }
        }, d);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() { }
}
