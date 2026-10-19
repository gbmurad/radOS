package com.zoro.keys;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The keyboard's dictionary: about 30,000 English words with how common each is (assets/words.txt, from the
 * FrequencyWords subtitle counts). Gives completions for a half typed word and a correction for a misspelt one.
 */
final class Words {
    private final Map<String, Long> freq = new HashMap<>(40000);
    private final List<String> byFreq = new ArrayList<>(30000);

    Words(Context c) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("words.txt"), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                String w = line.substring(0, sp);
                freq.put(w, Long.parseLong(line.substring(sp + 1).trim()));
                byFreq.add(w);
            }
        } catch (Exception ignored) { }
    }

    boolean known(String w) { return freq.containsKey(w.toLowerCase()); }

    /** Up to n guesses for what is being typed: the word itself if known, its correction, then completions. */
    List<String> suggest(String typed, int n) {
        List<String> out = new ArrayList<>();
        String w = typed.toLowerCase();
        if (w.isEmpty()) return out;
        if (known(w)) out.add(w);
        String fix = correct(w);
        if (fix != null && !out.contains(fix)) out.add(fix);
        for (String cand : byFreq) {
            if (out.size() >= n) break;
            if (cand.length() > w.length() && cand.startsWith(w) && !out.contains(cand)) out.add(cand);
        }
        if (!out.contains(w) && out.size() < n) out.add(0, w);   // what was typed, as typed, stays an option
        while (out.size() > n) out.remove(out.size() - 1);
        return out;
    }

    /** The most common known word one edit away (two for long words), or null if the word is fine or hopeless. */
    String correct(String w) {
        if (w.length() < 2 || known(w)) return null;
        String best = null;
        long bestF = 0;
        for (String e : edits(w)) {
            Long f = freq.get(e);
            if (f != null && f > bestF) { bestF = f; best = e; }
        }
        if (best == null && w.length() >= 5) {
            for (String e1 : edits(w)) for (String e2 : edits(e1)) {
                Long f = freq.get(e2);
                if (f != null && f > bestF) { bestF = f; best = e2; }
            }
        }
        return best;
    }

    private static List<String> edits(String w) {
        List<String> out = new ArrayList<>();
        String abc = "abcdefghijklmnopqrstuvwxyz'";
        for (int i = 0; i <= w.length(); i++) {
            String a = w.substring(0, i), b = w.substring(i);
            if (!b.isEmpty()) out.add(a + b.substring(1));                                    // delete
            if (b.length() > 1) out.add(a + b.charAt(1) + b.charAt(0) + b.substring(2));      // swap
            for (int k = 0; k < abc.length(); k++) {
                char ch = abc.charAt(k);
                if (!b.isEmpty()) out.add(a + ch + b.substring(1));                           // replace
                out.add(a + ch + b);                                                          // insert
            }
        }
        return out;
    }
}
