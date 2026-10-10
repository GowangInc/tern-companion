package io.github.gowanginc.terncompanion;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Tern Companion: pick a PC, see its Tern sessions, type into a block and read
 * its screen. Talks to tern_bridge.py over token-authenticated Funnel HTTPS,
 * Cloudflare Access HTTPS, or a private LAN/Tailscale connection.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "TernCompanion";
    private static final int INK = 0xff172622;
    private static final int MUTED = 0xff68736d;
    private static final int PAPER = 0xfff5f2e9;
    private static final int CARD = 0xfffffdf8;
    private static final int EDGE = 0xffe2ded3;
    private static final int GREEN = 0xff17634e;
    private static final int CORAL = 0xffb95743;
    private static final int OCHRE = 0xffb58a37;
    private static final Pattern SESSION_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}");
    private static final String TEXT_SIZE_KEY = "terminal_text_size_sp";
    private static final int TEXT_SIZE_DEFAULT = 16;
    private static final int TEXT_SIZE_MIN = 12;
    private static final int TEXT_SIZE_MAX = 24;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayList<Host> hosts = new ArrayList<>();
    private HostStore store;
    private SharedPreferences preferences;
    private LinearLayout root;
    private TextView status;
    private Runnable refreshTick;
    private HorizontalScrollView terminalKeys;
    private boolean imeVisible;
    private boolean showBackground = false;
    private Host lastHost;
    private JSONArray lastSessions;
    private int viewGen = 0;
    private String screen = "hosts";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.i(TAG, "TernCompanion " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") starting");
        store = new HostStore(this);
        preferences = getSharedPreferences("tern_ui", MODE_PRIVATE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (terminalKeys != null) terminalKeys.setVisibility(imeVisible ? View.GONE : View.VISIBLE);
            return insets;
        });
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
        loadHosts();
    }

    @Override public void onBackPressed() {
        if ("block".equals(screen)) {
            if (lastSessions != null && lastHost != null) showSessions(lastHost, lastSessions);
            else showHosts();
        } else if ("sessions".equals(screen)) {
            showHosts();
        } else {
            super.onBackPressed();
        }
    }

    // ---------- screens ----------

    private void loadHosts() {
        io.execute(() -> {
            try {
                ArrayList<Host> saved = new ArrayList<>(store.load());
                runUi(() -> { hosts.clear(); hosts.addAll(saved); showHosts(); });
            } catch (Exception e) {
                runUi(() -> { hosts.clear(); showHosts(); status("Couldn’t decrypt saved PCs — clear the app’s storage and add them again."); });
            }
        });
    }

    private void showHosts() {
        screen = "hosts";
        Log.d(TAG, "screen hosts");
        stopTick();
        root.removeAllViews();
        ScrollView scroll = paddedScroll();
        LinearLayout page = pageIn(scroll);

        page.addView(brow("TERN COMPANION"));
        page.addView(lineworkBand(), new LinearLayout.LayoutParams(-1, dp(76)));
        page.addView(title("Your PCs.", 36));
        page.addView(note("Your terminals, within reach. Use Tailscale Funnel HTTPS, Cloudflare Access, or your private network.", 15),
                mp(-1, -2, 0, 12, 0, 0));

        page.addView(rule(), mp(-1, dp1(), 0, 24, 0, 24));

        for (Host h : hosts) {
            LinearLayout card = card();
            card.addView(brow(!h.accessId.isEmpty() ? "CLOUDFLARE ACCESS" : h.scheme.equals("https") ? "HTTPS" : "PRIVATE NETWORK"));
            card.addView(text(h.name, 21, INK, Typeface.SERIF, Typeface.BOLD), mp(-1, -2, 0, 8, 0, 4));
            card.addView(text(h.baseUrl(), 12, MUTED, Typeface.MONOSPACE));
            Button connect = button("Connect  →");
            connect.setOnClickListener(v -> connect(h));
            card.addView(connect, mp(-1, -2, 0, 18, 0, 8));
            card.addView(removeRow(h));
            page.addView(card, mp(-1, -2, 0, 0, 0, 16));
        }
        if (hosts.isEmpty()) {
            TextView empty = text("No PCs yet. Start the bridge on a computer,\nthen add its address and token here.", 14, MUTED, Typeface.DEFAULT);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(28), dp(20), dp(28));
            empty.setBackground(bg(CARD, EDGE));
            page.addView(empty, mp(-1, -2, 0, 0, 0, 16));
        }

        Button scan = button("Scan host QR");
        scan.setTextColor(PAPER);
        scan.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), bg(GREEN, GREEN), null));
        scan.setOnClickListener(v -> HostQrScanner.launch(this));
        page.addView(scan, mp(-1, -2, 0, 8, 0, 8));

        Button load = button("Load QR image");
        load.setOnClickListener(v -> HostQrScanner.launchImage(this));
        page.addView(load, mp(-1, -2, 0, 8, 0, 8));

        Button add = button("＋   Add a PC");
        add.setOnClickListener(v -> addDialog());
        page.addView(add, mp(-1, -2, 0, 8, 0, 8));

        Button updates = button("App updates");
        updates.setContentDescription("Check for a new Tern Companion version");
        updates.setOnClickListener(v -> AppUpdater.show(this, io));
        page.addView(updates, mp(-1, -2, 0, 8, 0, 12));

        Button settings = button("Settings");
        settings.setOnClickListener(v -> showSettings(null));
        page.addView(settings, mp(-1, -2, 0, 8, 0, 12));

        status = note("", 13);
        page.addView(status, mp(-1, -2, 0, 16, 0, 0));
        page.addView(footnote());
        status("Funnel HTTPS: 8443. Private HTTP: 9260. Cloudflare Access is a separate option.");
    }

    private int terminalTextSize() {
        return Math.max(TEXT_SIZE_MIN, Math.min(TEXT_SIZE_MAX,
                preferences.getInt(TEXT_SIZE_KEY, TEXT_SIZE_DEFAULT)));
    }

    private void showSettings(TerminalView pane) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);

        int currentSize = terminalTextSize();
        LinearLayout fields = column();
        fields.setPadding(dp(24), dp(8), dp(24), dp(16));
        TextView sizeLabel = text("Terminal text size: " + currentSize + " sp", 16,
                INK, Typeface.DEFAULT, Typeface.BOLD);
        fields.addView(sizeLabel);
        SeekBar size = new SeekBar(this);
        size.setId(View.generateViewId());
        sizeLabel.setLabelFor(size.getId());
        size.setContentDescription("Terminal text size");
        size.setMin(TEXT_SIZE_MIN);
        size.setMax(TEXT_SIZE_MAX);
        size.setProgress(currentSize);
        size.setKeyProgressIncrement(1);
        size.setProgressTintList(ColorStateList.valueOf(GREEN));
        size.setThumbTintList(ColorStateList.valueOf(GREEN));
        ViewCompat.setStateDescription(size, currentSize + " sp");
        fields.addView(size, mp(-1, dp(48), 0, 8, 0, 8));
        TextView preview = text("The quick brown fox\njumps over the lazy dog.\n0123456789",
                currentSize, INK, Typeface.MONOSPACE);
        preview.setContentDescription("Terminal text size preview");
        preview.setLineSpacing(0, 1.5f);
        preview.setPadding(dp(16), dp(12), dp(16), dp(12));
        preview.setBackgroundColor(PAPER);
        fields.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        fields.addView(note("Changes apply immediately and are saved on this phone. Android’s system text size also applies.", 14),
                mp(-1, -2, 0, 16, 0, 0));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                sizeLabel.setText("Terminal text size: " + value + " sp");
                ViewCompat.setStateDescription(bar, value + " sp");
                preview.setTextSize(value);
                if (pane != null) pane.setTextSizeSp(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        ScrollView form = new ScrollView(this);
        form.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Settings").setView(form)
                .setPositiveButton("Done", null)
                .setNeutralButton("Reset", (d, which) -> size.setProgress(TEXT_SIZE_DEFAULT))
                .create();
        dialog.setOnDismissListener(d -> preferences.edit().putInt(TEXT_SIZE_KEY, size.getProgress()).apply());
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private void connect(Host h) {
        status("Connecting to " + h.name + "…");
        io.execute(() -> {
            try {
                HttpResult r = http(h, "GET", "/health", null);
                if (r.code != 200) throw new Exception("HTTP " + r.code + " — check the bridge token"
                        + (h.accessId.isEmpty() ? "." : " and Cloudflare Access credentials."));
                HttpResult s = http(h, "GET", "/sessions", null);
                if (s.code != 200) throw new Exception("HTTP " + s.code + " — check the bridge token"
                        + (h.accessId.isEmpty() ? "." : " and Cloudflare Access credentials."));
                JSONArray sessions = new JSONObject(s.body).getJSONArray("sessions");
                runUi(() -> showSessions(h, sessions));
            } catch (Exception e) {
                runUi(() -> status("Can’t reach " + h.name + ": " + shortError(e) + " Is the bridge running there?"));
            }
        });
    }

    private void showSessions(Host h, JSONArray sessions) {
        screen = "sessions";
        Log.d(TAG, "screen sessions");
        stopTick();
        lastHost = h;
        lastSessions = sessions;
        root.removeAllViews();
        ScrollView scroll = paddedScroll();
        LinearLayout page = pageIn(scroll);

        page.addView(headerWithBack(h.name, () -> showHosts()));

        List<SessionGroups.Group> groups = SessionGroups.group(sessions);
        List<SessionGroups.Group> normal = SessionGroups.normal(groups);
        List<SessionGroups.Group> background = SessionGroups.background(groups);
        int live = 0;
        for (SessionGroups.Group group : groups) live += group.liveCount();

        page.addView(brow("LIVE SESSIONS"), mp(-1, -2, 0, 18, 0, 0));
        page.addView(note(countLine(live, normal.size(), background.size()), 13), mp(-1, -2, 0, 8, 0, 4));
        page.addView(rule(), mp(-1, dp1(), 0, 12, 0, 20));

        for (SessionGroups.Group group : normal) page.addView(sessionCard(h, group), mp(-1, -2, 0, 0, 0, 16));
        if (normal.isEmpty()) page.addView(note("No live sessions on this PC.", 14), mp(-1, -2, 0, 0, 0, 16));

        if (showBackground) {
            page.addView(brow("BACKGROUND SESSIONS"), mp(-1, -2, 0, 8, 0, 0));
            page.addView(note("Flow and background sessions. Hidden by default; nothing is stopped or removed.", 13),
                    mp(-1, -2, 0, 8, 0, 12));
            if (background.isEmpty()) page.addView(note("No background sessions.", 14), mp(-1, -2, 0, 0, 0, 16));
            for (SessionGroups.Group group : background) page.addView(sessionCard(h, group), mp(-1, -2, 0, 0, 0, 16));
        }

        Button toggle = button(showBackground ? "Hide background sessions"
                : background.isEmpty() ? "Show background sessions" : "Show background sessions (" + background.size() + ")");
        toggle.setContentDescription(showBackground ? "Hide background sessions" : "Show background sessions");
        toggle.setOnClickListener(v -> {
            if (lastSessions == null || lastHost == null) return;
            showBackground = !showBackground;
            showSessions(lastHost, lastSessions);
        });
        page.addView(toggle, mp(-1, -2, 0, 8, 0, 8));

        Button again = button("↻   Refresh");
        again.setOnClickListener(v -> connect(h));
        page.addView(again, mp(-1, -2, 0, 8, 0, 0));

        Button newSession = button("＋   New session");
        newSession.setOnClickListener(v -> newSessionDialog(h));
        page.addView(newSession, mp(-1, -2, 0, 8, 0, 8));
        status = note("", 13);
        page.addView(status, mp(-1, -2, 0, 16, 0, 0));
        page.addView(footnote());
    }

    private void newSessionDialog(final Host h) {
        LinearLayout fields = column();
        fields.setPadding(dp(24), dp(16), dp(24), dp(24));
        EditText name = field("Session name (letters, digits, dot, underscore, hyphen)", InputType.TYPE_CLASS_TEXT);
        name.setText("session");
        TextView error = note("", 13);
        fields.addView(name);
        fields.addView(error);
        ScrollView form = new ScrollView(this);
        form.addView(fields);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New session")
                .setMessage("Creates a session that starts OMP — the same desktop agent interface — so it is ready to drive from this app.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String candidate = name.getText().toString().trim();
            if (!SESSION_NAME.matcher(candidate).matches()) {
                error.setText("Use 1-64 letters, digits, dots, underscores or hyphens; no spaces.");
                return;
            }
            io.execute(() -> {
                try {
                    JSONObject b = new JSONObject().put("name", candidate);
                    HttpResult r = http(h, "POST", "/new", b);
                    if (r.code != 200) throw new Exception(r.body.isEmpty() ? "HTTP " + r.code : r.body);
                    runUi(() -> {
                        dialog.dismiss();
                        status(candidate + " created.");
                        connect(h);
                    });
                } catch (Exception e) {
                    runUi(() -> status("New session failed: " + shortError(e)));
                }
            });
        }));
        dialog.show();
    }

    /** One compact session header with its live blocks beneath it. */
    private LinearLayout sessionCard(Host h, SessionGroups.Group group) {
        LinearLayout card = card();
        LinearLayout head = row();
        TextView name = text(group.name, 18, INK, Typeface.SERIF, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(name, weight1());
        TextView count = text(group.liveCount() == 0 ? "NO LIVE BLOCKS" : group.liveCount() + " LIVE",
                11, GREEN, Typeface.MONOSPACE, Typeface.BOLD);
        count.setLetterSpacing(0.10f);
        head.addView(count);
        card.addView(head);
        SessionGroups.Block current = group.current();
        if (current != null) {
            card.addView(text("CURRENT  ·  " + current.label, 11, GREEN, Typeface.MONOSPACE, Typeface.BOLD),
                    mp(-1, -2, 0, 10, 0, 0));
        }
        for (int i = 0; i < group.blocks.size(); i++) {
            SessionGroups.Block block = group.blocks.get(i);
            LinearLayout blockRow = column();
            blockRow.setFocusable(true);
            blockRow.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2217634e), null, null));
            blockRow.setContentDescription("Open " + block.label + " in " + group.name);
            TextView label = text((block.focused ? "● " : "") + block.label, 16, INK, Typeface.DEFAULT_BOLD);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            blockRow.addView(label);
            blockRow.addView(text(blockSub(block), 12, MUTED, Typeface.MONOSPACE), mp(-1, -2, 0, 6, 0, 0));
            blockRow.setOnClickListener(v -> showBlock(h, group.name, block.blockId, block.label));
            card.addView(blockRow, mp(-1, -2, 0, i == 0 ? 12 : 10, 0, 0));
        }
        return card;
    }

    private static String countLine(int live, int sessions, int background) {
        return live + (live == 1 ? " live block" : " live blocks") + "  ·  " + sessions
                + (sessions == 1 ? " session" : " sessions")
                + (background == 0 ? "" : "  ·  " + background + " background");
    }

    private static String blockSub(SessionGroups.Block block) {
        String where = block.cwd.isEmpty() ? shortProgram(block.program) : block.cwd;
        if (where.isEmpty()) where = shortProgram(block.program);
        if (where.isEmpty()) where = block.session;
        return block.tab.isEmpty() ? where : where + "  ·  " + block.tab;
    }

    private void showBlock(Host h, String sessionName, String blockId, String label) {
        screen = "block";
        Log.d(TAG, "screen block");
        stopTick();
        final int gen = viewGen;
        root.removeAllViews();

        // Fixed chrome around an expanding terminal: no outer ScrollView, nothing
        // that fights the pane's own vertical scrolling.
        LinearLayout page = column();
        root.addView(page, new LinearLayout.LayoutParams(-1, -1));

        // Calm app bar; the reading surface carries no nested card borders.
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(16), 0);
        header.setBackgroundColor(PAPER);
        Button backBtn = smallButton("‹");
        backBtn.setTextSize(28);
        backBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2217634e),
                null, bg(Color.WHITE, Color.TRANSPARENT)));
        backBtn.setContentDescription("Back");
        backBtn.setOnClickListener(v -> connect(h));
        header.addView(backBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView headLabel = text(label, 16, INK, Typeface.DEFAULT, Typeface.BOLD);
        headLabel.setSingleLine(true);
        headLabel.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams headLp = weight1();
        headLp.leftMargin = dp(8);
        header.addView(headLabel, headLp);
        TextView live = text("Live", 12, GREEN, Typeface.DEFAULT, Typeface.NORMAL);
        header.addView(live);
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));
        View headerRule = rule();
        headerRule.setBackgroundColor(0xffb4c8b7);
        page.addView(headerRule, new LinearLayout.LayoutParams(-1, dp1()));

        // 2. Terminal pane: takes all remaining height, IME on or off.
        final TerminalView pane = new TerminalView(this);
        pane.setTextSizeSp(terminalTextSize());
        Button settingsBtn = smallButton("Aa");
        settingsBtn.setTextSize(16);
        settingsBtn.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        settingsBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2217634e),
                null, bg(Color.WHITE, Color.TRANSPARENT)));
        settingsBtn.setContentDescription("Settings");
        settingsBtn.setOnClickListener(v -> showSettings(pane));
        header.addView(settingsBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(pane, new LinearLayout.LayoutParams(-1, 0, 1));

        // One shared error/status line, hidden unless something is wrong.
        final TextView captureNote = text("", 12, CORAL, Typeface.MONOSPACE);
        captureNote.setContentDescription("Live screen status");
        captureNote.setVisibility(View.GONE);
        captureNote.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.addView(captureNote, new LinearLayout.LayoutParams(-1, -2));
        status = captureNote;

        // 3. Shortcut strip: one horizontally scrollable row of terminal keys.
        String[][] keys = {
                {"Ctrl+P", "ctrl+p"}, {"Ctrl+C", "ctrl+c"}, {"Esc", "Escape"}, {"Tab", "Tab"},
                {"Enter", "Enter"}, {"↑", "Up"}, {"↓", "Down"}, {"←", "Left"}, {"→", "Right"}};
        HorizontalScrollView stripScroll = new HorizontalScrollView(this);
        terminalKeys = stripScroll;
        stripScroll.setVisibility(imeVisible ? View.GONE : View.VISIBLE);
        stripScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = row();
        strip.setPadding(dp(12), dp(4), dp(12), dp(4));
        for (int i = 0; i < keys.length; i++) {
            Button k = smallButton(keys[i][0]);
            k.setTextSize(14);
            k.setMinWidth(dp(48));
            k.setMinimumWidth(dp(48));
            k.setPadding(dp(12), dp(8), dp(12), dp(8));
            k.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2217634e),
                    null, bg(Color.WHITE, Color.TRANSPARENT)));
            String combo = keys[i][1];
            k.setContentDescription("Send " + combo);
            k.setOnClickListener(v -> send(h, sessionName, blockId, "keys", combo, null));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
            if (i > 0) lp.leftMargin = dp(4);
            strip.addView(k, lp);
        }
        stripScroll.addView(strip);
        page.addView(stripScroll, new LinearLayout.LayoutParams(-1, dp(56)));
        ViewCompat.requestApplyInsets(root);

        // 4. Pinned composer: native multiline EditText plus an explicit Send.
        LinearLayout composer = row();
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(16), dp(8), dp(16), dp(12));
        composer.setBackgroundColor(CARD);
        final EditText input = new EditText(this);
        input.setHint("Message or command…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_NONE); // Enter inserts a newline; Send submits.
        input.setSingleLine(false);
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setMinHeight(dp(56));
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setHintTextColor(0xff5d6b64);
        input.setPadding(dp(12), dp(12), dp(12), dp(12));
        StateListDrawable fieldBackground = new StateListDrawable();
        fieldBackground.addState(new int[]{android.R.attr.state_focused}, bg(CARD, GREEN));
        fieldBackground.addState(new int[]{}, bg(0xffeeeee5, MUTED));
        input.setBackground(fieldBackground);
        input.setContentDescription("Text to send to the terminal");
        composer.addView(input, weight1());
        final Button sendButton = button("Send");
        sendButton.setTextSize(14);
        sendButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sendButton.setMinWidth(dp(64));
        sendButton.setMinimumWidth(dp(64));
        sendButton.setTextColor(PAPER);
        sendButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffffff),
                bg(GREEN, GREEN), null));
        sendButton.setContentDescription("Send the text and press Enter in the terminal");
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(-2, dp(48));
        sendLp.leftMargin = dp(8);
        composer.addView(sendButton, sendLp);
        page.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        sendButton.setOnClickListener(v -> submitDraft(h, sessionName, blockId, input, sendButton));

        pane.setOnTailChangeListener(atTail -> pane.setJumpToLatestVisible(!atTail));

        // Live screen: one capture in flight at a time; the next poll is only
        // scheduled after the current request completes (no 2s backlog).
        final Runnable tick = new Runnable() {
            @Override public void run() {
                io.execute(() -> {
                    try {
                        JSONObject req = new JSONObject()
                                .put("block", sessionName + "/" + blockId).put("format", "html");
                        HttpResult r = http(h, "POST", "/capture", req);
                        runUi(() -> {
                            if (gen != viewGen) return;
                            if (r.code == 200) {
                                captureNote.setText("");
                                if (r.isHtml()) pane.loadCapture(r.body);
                                else pane.loadPlainText(r.body);
                            } else {
                                captureNote.setText("Not live: HTTP " + r.code);
                            }
                            scheduleNext(gen, this);
                        });
                    } catch (Exception e) {
                        runUi(() -> {
                            if (gen != viewGen) return;
                            captureNote.setText("Screen unavailable: " + shortError(e));
                            scheduleNext(gen, this);
                        });
                    }
                });
            }
        };
        tick.run();
    }

    private void scheduleNext(int gen, Runnable tick) {
        if (gen != viewGen) return;
        refreshTick = tick;
        ui.postDelayed(tick, 2000);
    }

    /** Sends the draft and Enter in one action. The draft stays editable while the
     *  request is in flight and is cleared on success only if it was not edited. */
    private void submitDraft(Host h, String sessionName, String blockId, EditText input, Button sendButton) {
        final String snapshot = input.getText().toString();
        if (snapshot.isEmpty() || !sendButton.isEnabled()) return;
        sendButton.setEnabled(false);
        send(h, sessionName, blockId, "submit", snapshot, ok -> {
            sendButton.setEnabled(true);
            if (ok && input.getText().toString().equals(snapshot)) input.setText("");
        });
    }

    private interface SendResult {
        void done(boolean ok);
    }

    private void send(Host h, String sessionName, String blockId, String kind, String value, SendResult result) {
        io.execute(() -> {
            try {
                JSONObject b = new JSONObject().put("block", sessionName + "/" + blockId)
                        .put("kind", kind).put("value", value);
                HttpResult r = http(h, "POST", "/send", b);
                if (r.code != 200) throw new Exception(r.body);
                if (result != null) runUi(() -> result.done(true));
            } catch (Exception e) {
                runUi(() -> {
                    status("Send failed: " + shortError(e));
                    if (result != null) result.done(false);
                });
            }
        });
    }

    // ---------- add / remove ----------

    private void addDialog() {
        LinearLayout fields = column();
        fields.setPadding(dp(24), dp(16), dp(24), dp(24));
        fields.addView(note("Funnel: HTTPS, port 8443 and bridge token; leave Access fields blank. Private HTTP: port 9260 and bridge token. Cloudflare: HTTPS (default 443), bridge token and both Access fields.", 14),
                mp(-1, -2, 0, 0, 0, 16));
        EditText name = field("Name (e.g. Studio Mac)", InputType.TYPE_CLASS_TEXT);
        EditText host = field("Private IP or HTTPS address", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = field("Port (Funnel: 8443)", InputType.TYPE_CLASS_NUMBER);
        EditText token = field("Bridge token", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText accessId = field("Cloudflare Access client ID (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText accessSecret = field("Cloudflare Access secret (optional)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(name); fields.addView(host); fields.addView(port); fields.addView(token);
        fields.addView(accessId); fields.addView(accessSecret);
        TextView error = note("", 13);
        fields.addView(error);
        ScrollView form = new ScrollView(this);
        form.addView(fields);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add a PC")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String address = host.getText().toString().trim();
                int defaultPort = address.toLowerCase(java.util.Locale.ROOT).startsWith("https://") ? 443 : 9260;
                Host h = new Host(name.getText().toString(), address,
                        parseInt(port.getText().toString(), defaultPort),
                        token.getText().toString(),
                        accessId.getText().toString(), accessSecret.getText().toString());
                saveHost(h);
                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                error.setText(e.getMessage());
            }
        }));
        dialog.show();
    }
    private void saveHost(Host host) {
        for (Host saved : hosts)
            if (saved.baseUrl().equals(host.baseUrl()))
                throw new IllegalArgumentException("That address is already saved.");
        hosts.add(host);
        persist(() -> { showHosts(); status(host.name + " saved."); });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!HostQrScanner.handleResult(this, requestCode, resultCode, data, io, host -> {
            try {
                saveHost(host);
            } catch (IllegalArgumentException e) {
                new AlertDialog.Builder(this).setTitle("PC already saved")
                        .setMessage(e.getMessage()).setPositiveButton("OK", null).show();
            }
        })) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }


    private void confirmRemove(Host h) {
        new AlertDialog.Builder(this).setTitle("Remove " + h.name + "?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> { hosts.remove(h); persist(this::showHosts); })
                .show();
    }

    private void persist(Runnable done) {
        io.execute(() -> {
            try {
                store.save(hosts);
                runUi(done);
            } catch (Exception e) {
                runUi(() -> status("Couldn’t save securely — try again."));
            }
        });
    }

    // ---------- http ----------

    private static final class HttpResult {
        final int code;
        final String contentType;
        final String body;
        HttpResult(int code, String contentType, String body) {
            this.code = code;
            this.contentType = contentType;
            this.body = body;
        }

        /** Older bridges answer /capture with text/plain; detect by content type. */
        boolean isHtml() {
            return contentType != null
                    && contentType.toLowerCase(java.util.Locale.ROOT).contains("text/html");
        }
    }

    /** Logs only method, endpoint, status, duration and length — never bodies, names or addresses. */
    private static HttpResult http(Host h, String method, String path, JSONObject payload) throws Exception {
        long started = SystemClock.elapsedRealtime();
        HttpURLConnection c = (HttpURLConnection) new URL(h.baseUrl() + path).openConnection();
        try {
            c.setInstanceFollowRedirects(false); // Never send bridge or Access credentials to a redirect target.
            c.setRequestMethod(method);
            c.setConnectTimeout(4000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Authorization", "Bearer " + h.token);
            if (!h.accessId.isEmpty()) {
                c.setRequestProperty("CF-Access-Client-Id", h.accessId);
                c.setRequestProperty("CF-Access-Client-Secret", h.accessSecret);
            }
            if (payload != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            String contentType = c.getContentType();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    char[] buf = new char[2048];
                    int n;
                    while ((n = r.read(buf)) > 0 && sb.length() < 1_000_000) sb.append(buf, 0, n);
                }
            }
            Log.d(TAG, "http " + method + " " + path + " status=" + code
                    + " ms=" + (SystemClock.elapsedRealtime() - started) + " len=" + sb.length());
            return new HttpResult(code, contentType, sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "http " + method + " " + path + " failed: " + e.getClass().getSimpleName());
            throw e;
        } finally {
            c.disconnect();
        }
    }

    // ---------- ui helpers ----------

    private void stopTick() {
        viewGen++; // invalidate any in-flight capture for the previous view
        terminalKeys = null;
        if (refreshTick != null) { ui.removeCallbacks(refreshTick); refreshTick = null; }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    }

    private void runUi(Runnable r) { ui.post(r); }

    private void status(String s) {
        if (status != null) {
            status.setText(s);
            status.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private ScrollView paddedScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        return scroll;
    }

    private LinearLayout pageIn(ScrollView scroll) {
        LinearLayout page = column();
        page.setPadding(dp(28), dp(28), dp(28), dp(36));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        return page;
    }

    private LinearLayout headerWithBack(String title, Runnable back) {
        LinearLayout head = row();
        Button backBtn = smallButton("‹");
        backBtn.setTextSize(26);
        backBtn.setContentDescription("Back");
        backBtn.setOnClickListener(v -> back.run());
        head.addView(backBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = text(title, 25, INK, Typeface.SERIF, Typeface.BOLD);
        t.setPadding(dp(16), 0, 0, 0);
        head.addView(t, weight1());
        return head;
    }

    private LinearLayout removeRow(Host h) {
        Button rm = smallButton("Remove this PC");
        rm.setOnClickListener(v -> confirmRemove(h));
        LinearLayout wrap = column();
        wrap.addView(rm, new LinearLayout.LayoutParams(-1, dp(48)));
        return wrap;
    }

    private TextView brow(String s) {
        TextView t = text(s, 11, GREEN, Typeface.MONOSPACE, Typeface.BOLD);
        t.setLetterSpacing(0.10f);
        return t;
    }

    private TextView title(String s, int size) {
        return text(s, size, INK, Typeface.SERIF, Typeface.BOLD);
    }

    private TextView note(String s, int size) {
        return text(s, size, MUTED, Typeface.DEFAULT, Typeface.NORMAL);
    }

    private TextView footnote() {
        TextView t = text("ENCRYPTED CREDENTIALS\nStored securely on this phone.", 11, MUTED, Typeface.MONOSPACE, Typeface.NORMAL);
        t.setPadding(0, dp(24), 0, 0);
        t.setLineSpacing(dp(4), 1f);
        return t;
    }

    private TextView text(String s, int size, int color, Typeface face, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(face, style);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private TextView text(String s, int size, int color, Typeface face) {
        return text(s, size, color, face, Typeface.NORMAL);
    }

    private TextView text(String s, int size, int color) {
        return text(s, size, color, Typeface.DEFAULT, Typeface.NORMAL);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(INK);
        b.setTextSize(15);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3317634e), bg(0xffebe7dc, GREEN), null));
        return b;
    }

    private Button smallButton(String label) {
        Button b = button(label);
        b.setTextSize(12);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(4), dp(8), dp(4), dp(8));
        return b;
    }

    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setTextSize(15);
        e.setTextColor(INK);
        e.setHintTextColor(MUTED);
        e.setMinHeight(dp(56));
        e.setPadding(dp(14), dp(14), dp(14), dp(14));
        e.setBackgroundTintList(ColorStateList.valueOf(GREEN));
        return e;
    }

    private LinearLayout card() {
        LinearLayout c = column();
        c.setPadding(dp(20), dp(20), dp(20), dp(20));
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2217634e), LineworkUi.cardBackground(this, CARD, EDGE), null));
        return c;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    private LinearLayout.LayoutParams mp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private GradientDrawable bg(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(6));
        d.setStroke(dp1(), stroke);
        return d;
    }

    private View rule() {
        View v = new View(this);
        v.setBackgroundColor(INK);
        return v;
    }

    /** Folded tracks from the illustrated 34–36 cover; decoration, never data. */
    private View lineworkBand() {
        View band = new View(this) {
            private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path track = new Path();

            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int saved = canvas.save();
                canvas.scale(getWidth() / 320f, getHeight() / 76f);
                ink.setStyle(Paint.Style.STROKE);
                for (int i = 0; i < 7; i++) {
                    float y = 18 + i * 6;
                    track.rewind();
                    track.moveTo(0, y);
                    track.lineTo(176, y);
                    track.cubicTo(208, y, 208, 64 - i * 6, 240, 64 - i * 6);
                    track.lineTo(320, 64 - i * 6);
                    ink.setColor(i == 1 ? CORAL : GREEN);
                    ink.setStrokeWidth(i == 1 ? 3 : 0.9f);
                    canvas.drawPath(track, ink);
                }
                ink.setStyle(Paint.Style.FILL);
                ink.setColor(OCHRE);
                canvas.drawCircle(18, 64, 3, ink);
                ink.setColor(GREEN);
                canvas.drawRect(32, 63.5f, 112, 64.5f, ink);
                canvas.restoreToCount(saved);
            }
        };
        band.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return band;
    }

    private static String shortError(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 160 ? m.substring(0, 160) : m;
    }

    private static String shortProgram(String program) {
        int i = program.lastIndexOf('/');
        return i >= 0 ? program.substring(i + 1) : program;
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private int dp1() { return dp(1); }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopTick();
        io.shutdown();
    }
}
