package com.zoro.keys;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.List;

/**
 * Glass Keys: a keyboard for the whole TV, driven by the remote.
 * Arrows move between keys, OK types, holding OK on a letter types it in capitals, Back closes the keyboard.
 * A strip on top shows what is being typed, so the words stay visible even when the app's box is covered.
 */
public class GlassKeys extends InputMethodService {

    // Key labels. Special keys: SHIFT, ABC/123/#+=, SPACE, DEL, LEFT, RIGHT, DONE.
    // iPhone layouts: ten across on top, nine indented, Shift and Delete around the bottom letters.
    private static final String[][] LETTERS = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"SHIFT", "z", "x", "c", "v", "b", "n", "m", "DEL"},
            {"123", ".com", "SPACE", "DONE"},
    };
    private static final String[][] NUMBERS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"-", "/", ":", ";", "(", ")", "$", "&", "@", "\""},
            {"#+=", ".", ",", "?", "!", "'", "DEL"},
            {"ABC", ".com", "SPACE", "DONE"},
    };
    private static final String[][] SYMBOLS = {
            {"[", "]", "{", "}", "#", "%", "^", "*", "+", "="},
            {"_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"},
            {"123", ".", ",", "?", "!", "'", "DEL"},
            {"ABC", "www.", "SPACE", "DONE"},
    };
    // How wide each key is, in "key units", for the bottom row (the other rows are all 1).
    private static float width(String k) {
        switch (k) {
            case "SPACE": return 5.0f;
            case "DONE": return 1.9f;
            case ".com": case "www.": return 1.6f;
            case "SHIFT": case "DEL": case "123": case "ABC": case "#+=": return 1.45f;
            default: return 1f;
        }
    }

    /** Width of a row in key units (each gap counts as a fraction of a key). */
    private static final float GAP_UNITS = 0.13f;
    private static float rowUnits(String[] r) {
        float u = 0;
        for (String k : r) u += width(k);
        return u + GAP_UNITS * (r.length - 1);
    }
    private static final float FULL = 10 + GAP_UNITS * 9;   // the ten key top row sets the width

    private String[][] layout = LETTERS;
    private int barCol = 1;
    private long flashUntil;      // a short message in the bar (autocorrect on/off)
    private String flash = "";
    private Words words;
    private boolean autocorrect = true, smart = true;   // smart: off for web addresses, emails, passwords
    private List<String> sugg = new java.util.ArrayList<>();
    private String lastFix;                             // "typed|fixed", so Delete right after undoes a correction
    private boolean shift, capsLock;
    private int row = 1, col = 4;
    private KeyboardView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean okDown, okLong;
    private final Runnable okHold = () -> { okLong = true; press(true); };

    @Override
    public View onCreateInputView() {
        view = new KeyboardView(this);
        if (words == null) new Thread(() -> words = new Words(this)).start();
        autocorrect = getSharedPreferences("keys", MODE_PRIVATE).getBoolean("autocorrect", true);
        return view;
    }

    @Override
    public boolean onEvaluateFullscreenMode() { return false; }

    /** Tells Rad the keyboard closed, so its search bar can drop back down at once. */
    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        sendBroadcast(new android.content.Intent("com.zoro.keys.HIDDEN").setPackage("com.zoro.rad"));
    }   // never the full screen typing box

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        int cls = info.inputType & InputType.TYPE_MASK_CLASS;
        layout = (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) ? NUMBERS : LETTERS;
        int v = info.inputType & InputType.TYPE_MASK_VARIATION;
        smart = cls == InputType.TYPE_CLASS_TEXT && v != InputType.TYPE_TEXT_VARIATION_URI
                && v != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS && v != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                && v != InputType.TYPE_TEXT_VARIATION_PASSWORD && v != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                && v != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && (info.inputType & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0;
        capsLock = false;
        shift = smart && sentenceStart();
        lastFix = null;
        row = 1;
        col = Math.min(4, layout[1].length - 1);
        updatePreview();
        if (view != null) view.invalidate();
    }

    @Override
    public void onUpdateSelection(int a, int b, int c, int d, int e, int f) {
        super.onUpdateSelection(a, b, c, d, e, f);
        refreshSuggestions();
        updatePreview();
        if (view != null) view.invalidate();
    }

    // ---------------------------------------------------------------- remote control

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (!isInputViewShown()) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) { okDown = true; okLong = false; main.postDelayed(okHold, 500); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: commit(" "); return true;      // a quick space
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_DEL: backspace(); return true;
            default: return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (!isInputViewShown()) return super.onKeyUp(code, e);
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            main.removeCallbacks(okHold);
            if (okDown && !okLong) press(false);
            okDown = false;
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        return super.onKeyUp(code, e);
    }

    /** Moves the highlight: it slides on from wherever it is now, so a held arrow keeps up. */
    private void move(int dr, int dc) {
        int fr = row, fc = col, fb = barCol;
        android.graphics.RectF start = new android.graphics.RectF();
        view.current(start);
        moveInner(dr, dc);
        if (fr != row || fc != col || fb != barCol) view.slideFrom(start);
    }

    private void pressDip() { view.pressDip(); }

    private void moveInner(int dr, int dc) {
        if (row == -1) {                                   // in the suggestion bar
            if (dr > 0) { row = 0; col = nearest(0, (barCol + 0.5f) / 3f); }
            else if (dc != 0) barCol = (barCol + dc + 3) % 3;
            view.invalidate();
            return;
        }
        if (dr < 0 && row == 0 && !sugg.isEmpty()) {
            barCol = Math.min(2, Math.max(0, Math.round(centerOf(0, col) * 3 - 0.5f)));
            row = -1;
            view.invalidate();
            return;
        }
        if (dr != 0) {
            float x = centerOf(row, col);
            row = (row + dr + layout.length) % layout.length;
            col = nearest(row, x);
        } else {
            col = (col + dc + layout[row].length) % layout[row].length;
        }
        view.invalidate();
    }

    private float centerOf(int r, int c) {
        float x = (FULL - rowUnits(layout[r])) / 2;
        for (int i = 0; i < c; i++) x += width(layout[r][i]) + GAP_UNITS;
        return (x + width(layout[r][c]) / 2) / FULL;
    }

    private int nearest(int r, float x) {
        int best = 0;
        float bestD = 9;
        for (int i = 0; i < layout[r].length; i++) {
            float d = Math.abs(centerOf(r, i) - x);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    // ---------------------------------------------------------------- typing

    private void press(boolean held) {
        pressDip();
        if (row == -1) { pick(barCol); updatePreview(); view.invalidate(); return; }
        String k = layout[row][col];
        switch (k) {
            case "SHIFT":
                if (held) { capsLock = !capsLock; shift = capsLock; } else { shift = !shift; capsLock = false; }
                break;
            case "123": layout = NUMBERS; fixCol(); break;
            case "ABC": layout = LETTERS; fixCol(); break;
            case "#+=": layout = SYMBOLS; fixCol(); break;
            case "SPACE":
                if (held) {
                    autocorrect = !autocorrect;
                    getSharedPreferences("keys", MODE_PRIVATE).edit().putBoolean("autocorrect", autocorrect).apply();
                    flash = autocorrect ? "Autocorrect on" : "Autocorrect off";
                    flashUntil = System.currentTimeMillis() + 1800;
                    view.postInvalidateDelayed(1850);
                } else space();
                break;
            case "DEL":
                if (held) { InputConnection ic = getCurrentInputConnection(); if (ic != null) ic.deleteSurroundingText(40, 0); }
                else if (!undoFix()) backspace();
                break;
            case "LEFT": cursor(-1); break;
            case "RIGHT": cursor(1); break;
            case "DONE": done(); return;
            default:
                boolean upper = held || shift;
                if (k.length() == 1 && ",.?!".contains(k)) fixWord();   // punctuation ends a word too
                commit(upper ? k.toUpperCase() : k);
                lastFix = null;
                if (shift && !capsLock && !held) shift = false;
        }
        updatePreview(); view.invalidate();
    }

    // ---------------------------------------------------------------- words

    /** The letters right before the cursor, back to the last space or punctuation. */
    private String currentWord() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence before = ic.getTextBeforeCursor(40, 0);
        if (before == null) return "";
        int i = before.length();
        while (i > 0 && (Character.isLetter(before.charAt(i - 1)) || before.charAt(i - 1) == '\'')) i--;
        return before.subSequence(i, before.length()).toString();
    }

    private boolean sentenceStart() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        CharSequence b = ic.getTextBeforeCursor(3, 0);
        if (b == null || b.length() == 0) return true;
        String t = b.toString();
        return t.endsWith(". ") || t.endsWith("? ") || t.endsWith("! ") || t.endsWith("\n");
    }

    private void refreshSuggestions() {
        String w = currentWord();
        sugg = (smart && words != null && !w.isEmpty()) ? words.suggest(w, 3) : new java.util.ArrayList<>();
        if (view != null) view.invalidate();
    }

    /** Autocorrect: swap a misspelt word for its correction, keeping a capital first letter. */
    private void fixWord() {
        if (!autocorrect || !smart || words == null) return;
        String w = currentWord();
        if (w.isEmpty()) return;
        String fix = words.correct(w.toLowerCase());
        if (fix == null) return;
        if (Character.isUpperCase(w.charAt(0))) fix = Character.toUpperCase(fix.charAt(0)) + fix.substring(1);
        InputConnection ic = getCurrentInputConnection();
        ic.deleteSurroundingText(w.length(), 0);
        ic.commitText(fix, 1);
        lastFix = w + "|" + fix;
    }

    /** Delete straight after an autocorrect puts back what was typed. */
    private boolean undoFix() {
        if (lastFix == null) return false;
        String[] p = lastFix.split("\\|");
        lastFix = null;
        InputConnection ic = getCurrentInputConnection();
        CharSequence b = ic == null ? null : ic.getTextBeforeCursor(p[1].length() + 1, 0);
        if (b == null || !b.toString().equals(p[1] + " ")) return false;
        ic.deleteSurroundingText(p[1].length() + 1, 0);
        ic.commitText(p[0], 1);
        return true;
    }

    private void space() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence b = ic.getTextBeforeCursor(2, 0);
        // Double space: a full stop, like a phone keyboard.
        if (smart && b != null && b.length() == 2 && b.charAt(1) == ' ' && Character.isLetterOrDigit(b.charAt(0))) {
            ic.deleteSurroundingText(1, 0);
            ic.commitText(". ", 1);
            shift = true;
            lastFix = null;
            return;
        }
        fixWord();
        ic.commitText(" ", 1);
        if (smart && sentenceStart()) shift = true;
    }

    private void pick(int i) {
        if (i >= sugg.size()) return;
        String w = currentWord(), s = sugg.get(i);
        if (!w.isEmpty() && Character.isUpperCase(w.charAt(0))) s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.deleteSurroundingText(w.length(), 0);
        ic.commitText(s + " ", 1);
        lastFix = null;
        shift = smart && sentenceStart();
    }

    private void fixCol() { row = Math.min(row, layout.length - 1); col = Math.min(col, layout[row].length - 1); }

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(s, 1);
        updatePreview();
        updatePreview(); view.invalidate();
    }

    private void backspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) ic.commitText("", 1); else ic.deleteSurroundingText(1, 0);
        updatePreview(); view.invalidate();
    }

    private void cursor(int d) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ExtractedText t = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (t == null) return;
        int p = Math.max(0, Math.min(t.text.length(), t.selectionStart + d));
        ic.setSelection(p, p);
    }

    /** Done: the app's own action (search, go, send) when it has one, otherwise just close the keyboard. */
    private void done() {
        InputConnection ic = getCurrentInputConnection();
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_NONE : ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (ic != null && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else if (ic != null && ei != null && (ei.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
        requestHideSelf(0);
    }

    /** Pushes the field's text and cursor to the preview pill (dots for a password). */
    private void updatePreview() {
        if (view == null) return;
        InputConnection ic = getCurrentInputConnection();
        CharSequence b = ic == null ? null : ic.getTextBeforeCursor(400, 0);
        CharSequence a = ic == null ? null : ic.getTextAfterCursor(400, 0);
        String before = b == null ? "" : b.toString(), after = a == null ? "" : a.toString();
        EditorInfo ei = getCurrentInputEditorInfo();
        int v = ei == null ? 0 : ei.inputType & InputType.TYPE_MASK_VARIATION;
        int cls = ei == null ? 0 : ei.inputType & InputType.TYPE_MASK_CLASS;
        boolean secret = (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        if (secret) { before = dots(before.length()); after = dots(after.length()); }
        view.setPreview(before, after);
    }

    private static String dots(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < n; i++) s.append('\u2022');
        return s.toString();
    }

    // ---------------------------------------------------------------- drawing

    /**
     * The keyboard: a smoked glass panel, glass keys, a text preview pill above, and one bright glass highlight
     * that slides between keys. Everything that does not move is drawn once into cached bitmaps (panel, keys,
     * suggestion bar); a frame draws those, the highlight and the pill text.
     */
    private class KeyboardView extends View {
        private final float d;
        private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint typed = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pillText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint spec = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hiBody = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hiSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint caret = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grain = new Paint();
        private final Typeface tfReg = Typeface.create("sans-serif", Typeface.NORMAL);
        private final Typeface tfMed = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        private final Shader hiGrad = new LinearGradient(0, 0, 0, 1,
                new int[]{0xFFFFFFFF, 0xE6F2F4FF}, null, Shader.TileMode.CLAMP);
        private final Shader sheenGrad = new LinearGradient(0, 0, 1, 0,
                new int[]{0x0064D2FF, 0x5064D2FF, 0x0064D2FF}, null, Shader.TileMode.CLAMP);
        private final Matrix mx = new Matrix();
        private final RectF tmp = new RectF();
        private final Path path = new Path();

        // geometry, rebuilt when the size or layout page changes
        private int gW, gH;
        private String[][] gLayout;
        private final RectF panelR = new RectF(), pillR = new RectF();
        private RectF[][] keyR;
        private float barTop, barH, baseY, cellW;

        // cached layers
        private Bitmap baseBmp, keysBmp, barBmp, noise;
        private int ax;                                  // x of the cached bitmaps in the view
        private int baseKey, keysKey, barKey;
        private boolean debugTiming;
        private int frames;
        private long drawNanos;

        // slide
        private final RectF from = new RectF(), cur = new RectF();
        private android.animation.ValueAnimator slide, sheen;
        private float slideT = 1f, sheenT = -1f;
        private float press = 1f;                         // dips to 0.93 for a moment when a key is typed
        private android.animation.ValueAnimator pressAnim;

        // preview pill
        private String pvBefore = "", pvAfter = "", pvFull = "";
        private float pvCaretX, pvTotal;
        private long blinkStart = System.currentTimeMillis();
        private final Runnable blinkTick = this::invalidate;

        KeyboardView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            debugTiming = android.util.Log.isLoggable("GlassKeysDraw", android.util.Log.DEBUG);
            divider.setColor(0x4DFFFFFF);
            divider.setStrokeWidth(1f * d);
            shade.setMaskFilter(new android.graphics.BlurMaskFilter(4 * d, android.graphics.BlurMaskFilter.Blur.NORMAL));
            glow.setColor(0x26FFFFFF);
            glow.setMaskFilter(new android.graphics.BlurMaskFilter(6 * d, android.graphics.BlurMaskFilter.Blur.NORMAL));
            spec.setMaskFilter(new android.graphics.BlurMaskFilter(2.5f * d, android.graphics.BlurMaskFilter.Blur.NORMAL));
            spec.setColor(0x1FFFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTypeface(tfMed);
            typed.setTextAlign(Paint.Align.CENTER);
            pillText.setColor(Color.WHITE);
            pillText.setTypeface(tfReg);
            pillText.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16,
                    c.getResources().getDisplayMetrics()));
            caret.setColor(0xFF64D2FF);
            hiBody.setShader(hiGrad);
            hiSheen.setShader(sheenGrad);
            noise = makeNoise();
            grain.setShader(new BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            grain.setAlpha(8);                              // 3%
        }

        /** A small fixed noise tile, tinted white, for the frosted grain. */
        private Bitmap makeNoise() {
            int n = 128;
            int[] px = new int[n * n];
            java.util.Random rnd = new java.util.Random(7);
            for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | (0x010101 * (96 + rnd.nextInt(160)));
            return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), Math.round(318 * d));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            baseKey = keysKey = barKey = 0;
        }

        // ------------------------------------------------------------ geometry

        private void ensureGeometry() {
            int W = getWidth(), H = getHeight();
            if (W == gW && H == gH && gLayout == layout && keyR != null) return;
            boolean sizeChanged = W != gW || H != gH;
            gW = W; gH = H; gLayout = layout;
            float side = Math.max(36 * d, (W - 470 * d) / 2);       // a centred panel, not edge to edge
            panelR.set(side, 50 * d, W - side, H - 8 * d);
            pillR.set(side, 4 * d, W - side, 44 * d);
            ax = (int) Math.max(0, side - 24 * d);
            barTop = panelR.top + 6 * d;
            barH = 30 * d;
            cellW = (panelR.width() - 40 * d) / 3f;
            typed.setTextSize(14 * d);
            Paint.FontMetrics tf = typed.getFontMetrics();
            baseY = barTop + barH / 2 - (tf.ascent + tf.descent) / 2;
            float top0 = panelR.top + 44 * d, rowGap = 9 * d;
            float avail = panelR.width() - 28 * d, U = avail / FULL, gap = U * GAP_UNITS;
            float rowH = (panelR.bottom - top0 - 12 * d - rowGap * (layout.length - 1)) / layout.length;
            keyR = new RectF[layout.length][];
            for (int r = 0; r < layout.length; r++) {
                keyR[r] = new RectF[layout[r].length];
                float x = panelR.left + 14 * d + (FULL - rowUnits(layout[r])) / 2 * U;
                float y = top0 + r * (rowH + rowGap);
                for (int i = 0; i < layout[r].length; i++) {
                    float kw = width(layout[r][i]) * U;
                    keyR[r][i] = new RectF(x, y, x + kw, y + rowH);
                    x += kw + gap;
                }
            }
            if (sizeChanged) updatePreviewMetrics();
        }

        /** Where the highlight belongs right now: the focused key swollen 7%, or the focused suggestion cell. */
        private void target(RectF out) {
            ensureGeometry();
            if (row == -1) {
                float cx = panelR.left + 20 * d + cellW * barCol + cellW / 2;
                out.set(cx - cellW / 2 + 4 * d, barTop, cx + cellW / 2 - 4 * d, barTop + barH);
                return;
            }
            RectF k = keyR[Math.min(row, keyR.length - 1)][Math.min(col, keyR[Math.min(row, keyR.length - 1)].length - 1)];
            float dw = k.width() * 0.035f, dh = k.height() * 0.035f;
            out.set(k.left - dw, k.top - dh, k.right + dw, k.bottom + dh);
        }

        /** Where the highlight is drawn at this instant (mid slide or settled). */
        void current(RectF out) {
            target(out);
            if (slide != null && slide.isRunning()) {
                float t = slideT;
                out.set(from.left + (out.left - from.left) * t, from.top + (out.top - from.top) * t,
                        from.right + (out.right - from.right) * t, from.bottom + (out.bottom - from.bottom) * t);
            }
        }

        /** Slides the highlight on from wherever it was; a held arrow keeps retargeting from the live position. */
        void slideFrom(RectF start) {
            from.set(start);
            slideT = 0f;
            if (slide != null) slide.cancel();
            if (sheen != null) sheen.cancel();
            sheenT = -1f;
            slide = android.animation.ValueAnimator.ofFloat(0f, 1f);
            slide.setDuration(120);
            slide.setInterpolator(new android.view.animation.DecelerateInterpolator());
            slide.addUpdateListener(a -> { slideT = (float) a.getAnimatedValue(); invalidate(); });
            slide.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    slideT = 1f;
                    if (!cancelled) startSheen();
                }
            });
            slide.start();
        }

        private void startSheen() {
            sheen = android.animation.ValueAnimator.ofFloat(0f, 1f);
            sheen.setDuration(1600);
            sheen.setInterpolator(new android.view.animation.LinearInterpolator());
            sheen.addUpdateListener(a -> { sheenT = (float) a.getAnimatedValue(); invalidate(); });
            sheen.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) { sheenT = -1f; invalidate(); }
            });
            sheen.start();
        }

        void pressDip() {
            if (pressAnim != null) pressAnim.cancel();
            pressAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
            pressAnim.setDuration(150);
            pressAnim.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                press = 1f - 0.07f * (float) Math.sin(Math.PI * t);
                invalidate();
            });
            pressAnim.start();
        }

        // ------------------------------------------------------------ preview pill

        void setPreview(String before, String after) {
            if (before.equals(pvBefore) && after.equals(pvAfter)) return;
            pvBefore = before; pvAfter = after; pvFull = before + after;
            blinkStart = System.currentTimeMillis();
            updatePreviewMetrics();
            invalidate();
        }

        private void updatePreviewMetrics() {
            pvCaretX = pillText.measureText(pvBefore);
            pvTotal = pillText.measureText(pvFull);
        }

        // ------------------------------------------------------------ drawing

        private Bitmap layer(Bitmap old) {
            int w = gW - 2 * ax, h = gH;
            if (old != null && old.getWidth() == w && old.getHeight() == h) { old.eraseColor(0); return old; }
            if (old != null) old.recycle();
            return Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        }

        @Override
        protected void onDraw(Canvas c) {
            long t0 = debugTiming ? System.nanoTime() : 0;
            ensureGeometry();
            if (gW <= 0) return;

            int bk = gW * 31 + gH;
            if (bk != baseKey || baseBmp == null) {
                baseBmp = layer(baseBmp);
                Canvas bc = new Canvas(baseBmp);
                bc.translate(-ax, 0);
                glassPanel(bc, panelR, 26 * d);
                glassPanel(bc, pillR, pillR.height() / 2);
                baseKey = bk;
            }
            int kk = (bk * 31 + System.identityHashCode(layout)) * 31 + (shift ? 1 : 0) + (capsLock ? 2 : 0);
            if (kk != keysKey || keysBmp == null) {
                keysBmp = layer(keysBmp);
                Canvas kc = new Canvas(keysBmp);
                kc.translate(-ax, 0);
                for (int r = 0; r < layout.length; r++) {
                    for (int i = 0; i < layout[r].length; i++) {
                        String k = layout[r][i];
                        RectF kr = keyR[r][i];
                        glassKey(kc, kr, isFn(k));
                        label.setColor(Color.WHITE);
                        drawKeyFace(kc, k, kr, false);
                    }
                }
                keysKey = kk;
            }
            boolean flashing = System.currentTimeMillis() < flashUntil;
            int rk = (bk * 31 + (flashing ? flash.hashCode() : 0)) * 31 + sugg.hashCode();
            rk = (rk * 31 + (shift ? 1 : 0) + (smart ? 2 : 0) + (autocorrect ? 4 : 0));
            if (rk != barKey || barBmp == null) {
                barBmp = layer(barBmp);
                Canvas rc = new Canvas(barBmp);
                rc.translate(-ax, 0);
                drawBar(rc, flashing);
                barKey = rk;
            }

            c.drawBitmap(baseBmp, ax, 0, bmp);
            c.drawBitmap(keysBmp, ax, 0, bmp);
            c.drawBitmap(barBmp, ax, 0, bmp);
            drawPill(c);
            drawHighlight(c);

            removeCallbacks(blinkTick);
            postDelayed(blinkTick, 530);   // caret blink
            if (debugTiming) {
                drawNanos += System.nanoTime() - t0;
                if (++frames == 30) {
                    android.util.Log.d("GlassKeysDraw", "onDraw avg " + (drawNanos / frames / 1000) + " us over " + frames + " frames");
                    frames = 0; drawNanos = 0;
                }
            }
        }

        /** Frosted glass body: deep gradient, inner top sheen, fine grain, and a rim whose light runs round the top. */
        private void glassPanel(Canvas c, RectF r, float rad) {
            fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xC014141C, 0xD00A0A10, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * 0.3f, 0x1AFFFFFF, 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
            c.drawRoundRect(r, rad, rad, grain);
            stroke.setStrokeWidth(1.2f * d);
            stroke.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    new int[]{0x80FFFFFF, 0x24FFFFFF, 0x0EFFFFFF, 0x0EFFFFFF}, new float[]{0f, 0.18f, 0.6f, 1f},
                    Shader.TileMode.CLAMP));
            tmp.set(r.left + 0.6f * d, r.top + 0.6f * d, r.right - 0.6f * d, r.bottom - 0.6f * d);
            c.drawRoundRect(tmp, rad, rad, stroke);
            stroke.setShader(null);
        }

        /** The top line like Apple's QuickType: three suggestions split by thin lines, or a short message. */
        private void drawBar(Canvas c, boolean flashing) {
            if (flashing) {
                typed.setTypeface(tfReg);
                typed.setColor(autocorrect ? 0xFF7CF29C : 0xB3FFFFFF);
                c.drawText(flash, panelR.centerX(), baseY, typed);
            } else if (smart && !sugg.isEmpty()) {
                for (int k = 0; k < 3; k++) {
                    float cx = panelR.left + 20 * d + cellW * k + cellW / 2;
                    if (k < sugg.size()) {
                        typed.setColor(k == 1 ? Color.WHITE : 0xCCFFFFFF);
                        typed.setTypeface(k == 1 ? tfMed : tfReg);
                        c.drawText(suggText(k), cx, baseY, typed);
                    }
                    if (k < 2) c.drawLine(cx + cellW / 2, barTop + 7 * d, cx + cellW / 2, barTop + barH - 7 * d, divider);
                }
            }
        }

        private String suggText(int k) {
            String w = sugg.get(k);
            return shift ? Character.toUpperCase(w.charAt(0)) + w.substring(1) : w;
        }

        /** The text preview pill: what the field holds, a blinking caret at the cursor, scrolled to keep it in view. */
        private void drawPill(Canvas c) {
            float padX = 18 * d, maxW = pillR.width() - 2 * padX;
            Paint.FontMetrics fm = pillText.getFontMetrics();
            float by = pillR.centerY() - (fm.ascent + fm.descent) / 2;
            float scroll = Math.max(0f, pvCaretX - (maxW - 4 * d));
            c.save();
            c.clipRect(pillR.left + padX, pillR.top, pillR.right - padX, pillR.bottom);
            pillText.setTextAlign(Paint.Align.LEFT);
            if (pvFull.isEmpty()) {
                pillText.setColor(0x80FFFFFF);
                c.drawText("Hold OK for capitals  ·  hold Space for autocorrect", pillR.left + padX + 6 * d, by, pillText);
            } else {
                pillText.setColor(Color.WHITE);
                c.drawText(pvFull, pillR.left + padX - scroll, by, pillText);
            }
            if (((System.currentTimeMillis() - blinkStart) / 530) % 2 == 0) {
                float cx = pillR.left + padX - scroll + pvCaretX + (pvFull.isEmpty() ? 0 : 1.5f * d);
                c.drawRect(cx, pillR.centerY() - 10 * d, cx + 2 * d, pillR.centerY() + 10 * d, caret);
            }
            c.restore();
        }

        /** The sliding bright glass highlight with its glow, rim, face and the landing sheen. */
        private void drawHighlight(Canvas c) {
            current(tmp);
            RectF r = new RectF(tmp);
            if (press != 1f) {
                float dw = r.width() * (press - 1) / 2, dh = r.height() * (press - 1) / 2;
                r.set(r.left - dw, r.top - dh, r.right + dw, r.bottom + dh);
            }
            float rad = Math.min(r.width(), r.height()) * 0.2f;
            shade.setColor(0x66000000);
            c.drawRoundRect(r.left + 1 * d, r.top + 3 * d, r.right - 1 * d, r.bottom + 3 * d, rad, rad, shade);
            float gx = r.width() * 0.015f, gy = r.height() * 0.015f;
            c.drawRoundRect(r.left - gx, r.top - gy, r.right + gx, r.bottom + gy, rad, rad, glow);
            mx.setScale(1, r.height());
            mx.postTranslate(0, r.top);
            hiGrad.setLocalMatrix(mx);
            c.drawRoundRect(r, rad, rad, hiBody);
            if (sheenT >= 0f) {
                float bw = r.width() * 0.6f;
                mx.setScale(bw, 1);
                mx.postSkew(-0.45f, 0, 0, r.height() / 2);
                mx.postTranslate(r.left - bw + sheenT * (r.width() + 2 * bw), r.top);
                sheenGrad.setLocalMatrix(mx);
                c.drawRoundRect(r, rad, rad, hiSheen);
            }
            stroke.setStrokeWidth(1f * d);
            stroke.setColor(0xFFFFFFFF);
            tmp.set(r.left + 0.5f * d, r.top + 0.5f * d, r.right - 0.5f * d, r.bottom - 0.5f * d);
            c.drawRoundRect(tmp, rad, rad, stroke);
            if (row == -1) {
                if (barCol < sugg.size()) {
                    typed.setColor(0xFF111114);
                    typed.setTypeface(barCol == 1 ? tfMed : tfReg);
                    c.drawText(suggText(barCol), r.centerX(), baseY, typed);
                }
            } else {
                String k = layout[row][col];
                label.setColor(0xFF111114);
                drawKeyFace(c, k, r, true);
            }
        }

        private boolean isFn(String k) {
            return k.equals("SHIFT") || k.equals("DEL") || k.equals("123") || k.equals("ABC") || k.equals("#+=")
                    || k.equals("DONE") || k.equals(".com") || k.equals("www.");
        }

        private void drawKeyFace(Canvas c, String k, RectF r, boolean on) {
            if (k.equals("SHIFT")) drawShift(c, r, on);
            else if (k.equals("DEL")) drawDelete(c, r, on);
            else drawLabel(c, k, r, on);
        }

        /**
         * One liquid glass key (cached): a see-through body brighter at the top, a bright lip along the top edge,
         * light pooling at the bottom, a specular spot top left, a rim that catches colour, and a shadow under it.
         */
        private void glassKey(Canvas c, RectF r, boolean fn) {
            float rad = Math.min(r.width(), r.height()) * 0.2f;
            shade.setColor(0x40000000);
            c.drawRoundRect(r.left + 1 * d, r.top + 3 * d, r.right - 1 * d, r.bottom + 3 * d, rad, rad, shade);
            int a = fn ? 0x22 : 0x38, b = fn ? 0x0A : 0x12;
            fill.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                    new int[]{(a << 24) | 0xFFFFFF, (b << 24) | 0xFFFFFF, ((fn ? 0x14 : 0x22) << 24) | 0xFFFFFF},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(new android.graphics.RadialGradient(r.centerX(), r.bottom, r.width() * 0.6f,
                    0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
            // specular spot, top left quarter
            tmp.set(r.left + r.width() * 0.1f, r.top + r.height() * 0.07f, r.left + r.width() * 0.46f, r.top + r.height() * 0.2f);
            c.drawOval(tmp, spec);
            // rim, then the colour sweep over it
            stroke.setStrokeWidth(1.2f * d);
            stroke.setColor(0x40FFFFFF);
            c.drawRoundRect(r, rad, rad, stroke);
            stroke.setShader(new android.graphics.SweepGradient(r.centerX(), r.centerY(),
                    new int[]{0x33FFFFFF, 0x2264D2FF, 0x22FF7AD9, 0x33FFFFFF}, null));
            c.drawRoundRect(r, rad, rad, stroke);
            stroke.setShader(null);
            // bright top lip
            stroke.setStrokeWidth(1.6f * d);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setShader(new LinearGradient(r.left, 0, r.right, 0,
                    new int[]{0x00FFFFFF, 0xD9FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawLine(r.left + rad * 0.8f, r.top + 1.1f * d, r.right - rad * 0.8f, r.top + 1.1f * d, stroke);
            stroke.setShader(null);
            stroke.setStrokeCap(Paint.Cap.BUTT);
        }


        /** Shift: an outlined arrow; filled when on, filled with a bar under it for caps lock. */
        private void drawShift(Canvas c, RectF r, boolean on) {
            float u = Math.min(r.width(), r.height()) / 40f, cx = r.centerX(), cy = r.centerY() - (capsLock ? 2 * u : 0);
            android.graphics.Path a = new android.graphics.Path();
            a.moveTo(cx, cy - 10 * u);
            a.lineTo(cx + 10 * u, cy + 1 * u);
            a.lineTo(cx + 4.5f * u, cy + 1 * u);
            a.lineTo(cx + 4.5f * u, cy + 9 * u);
            a.lineTo(cx - 4.5f * u, cy + 9 * u);
            a.lineTo(cx - 4.5f * u, cy + 1 * u);
            a.lineTo(cx - 10 * u, cy + 1 * u);
            a.close();
            int ink = on ? 0xFF111114 : Color.WHITE;
            iconPaint.setColor(ink);
            iconPaint.setStyle(shift || capsLock ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            iconPaint.setStrokeWidth(1.6f * u);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(a, iconPaint);
            if (capsLock) {
                iconPaint.setStyle(Paint.Style.FILL);
                c.drawRect(cx - 5 * u, cy + 12 * u, cx + 5 * u, cy + 14 * u, iconPaint);
            }
        }

        /** Delete: Apple's outlined tag with an x inside. */
        private void drawDelete(Canvas c, RectF r, boolean on) {
            float u = Math.min(r.width(), r.height()) / 40f, cx = r.centerX() + 1 * u, cy = r.centerY();
            android.graphics.Path t = new android.graphics.Path();
            t.moveTo(cx - 13 * u, cy);
            t.lineTo(cx - 6 * u, cy - 8 * u);
            t.lineTo(cx + 11 * u, cy - 8 * u);
            t.quadTo(cx + 13 * u, cy - 8 * u, cx + 13 * u, cy - 6 * u);
            t.lineTo(cx + 13 * u, cy + 6 * u);
            t.quadTo(cx + 13 * u, cy + 8 * u, cx + 11 * u, cy + 8 * u);
            t.lineTo(cx - 6 * u, cy + 8 * u);
            t.close();
            iconPaint.setColor(on ? 0xFF111114 : Color.WHITE);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(1.6f * u);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            c.drawPath(t, iconPaint);
            c.drawLine(cx - 1 * u, cy - 3.5f * u, cx + 6 * u, cy + 3.5f * u, iconPaint);
            c.drawLine(cx + 6 * u, cy - 3.5f * u, cx - 1 * u, cy + 3.5f * u, iconPaint);
        }

        private void drawLabel(Canvas c, String k, RectF r, boolean on) {
            String t;
            float size = 17 * d;
            switch (k) {
                case "SPACE": t = "space"; size = 14 * d; break;
                case "DEL": t = "⌫"; size = 19 * d; break;
                case "DONE": t = "done"; size = 14 * d; break;
                case "SHIFT": t = capsLock ? "⇪" : "⇧"; size = 19 * d;
                    if (shift && !on) label.setColor(0xFF64D2FF); break;
                case "LEFT": t = "←"; break;
                case "RIGHT": t = "→"; break;
                default:
                    t = (shift && k.length() == 1) ? k.toUpperCase() : k;
                    if (k.length() > 1) size = 13 * d;
            }
            label.setTextSize(size);
            Paint.FontMetrics fm = label.getFontMetrics();
            c.drawText(t, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, label);
        }
    }
}
