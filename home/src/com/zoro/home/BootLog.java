package com.zoro.home;

/**
 * Startup timings, kept in memory: at boot the system log can drop an app's lines (2026-10-19 none arrived), so
 * the boot screen and the home screen note their steps here and the home screen writes them out once it is up.
 */
final class BootLog {
    private static final StringBuilder sb = new StringBuilder();
    private static boolean dumped;

    static synchronized void mark(String what) {
        sb.append(android.os.SystemClock.uptimeMillis()).append(" ms up  ").append(what).append('\n');
    }

    static synchronized void dumpOnce() {
        if (dumped || sb.length() == 0) return;
        dumped = true;
        for (String l : sb.toString().split("\n")) android.util.Log.i("radOS", l);
    }
}
