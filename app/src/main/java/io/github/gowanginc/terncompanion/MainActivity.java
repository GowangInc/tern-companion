package io.github.gowanginc.terncompanion;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebResourceResponse;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    private static final int REQUEST_ATTACH = 4711;
    private static final Pattern UPLOAD_ID = Pattern.compile("[0-9a-f]{32}");
    private static final int HTTP_BODY_LIMIT = 1_000_000;

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
    private final ArrayList<Pending> pendingAttachments = new ArrayList<>();
    private LinearLayout attachmentTray;
    private ScrollView attachmentTrayScroll;
    private EditText draft;
    private Button sendButton;
    private Button attachButton;
    private boolean toolsExpanded;
    private boolean sendBusy;
    private int pickerGen = -1;

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
            updateStripVisibility();
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
        Set<String> hiddenSessions = hiddenSet(SessionGroups.hiddenSessionsKey(h.baseUrl()));
        Set<String> hiddenBlocks = hiddenSet(SessionGroups.hiddenBlocksKey(h.baseUrl()));
        List<SessionGroups.Group> normal = SessionGroups.visible(groups, false, hiddenSessions, hiddenBlocks);
        List<SessionGroups.Group> background = SessionGroups.visible(groups, true, hiddenSessions, hiddenBlocks);
        int live = 0;
        for (SessionGroups.Group group : normal) live += group.liveCount();
        for (SessionGroups.Group group : background) live += group.liveCount();
        int hidden = hiddenSessions.size() + hiddenBlocks.size();

        page.addView(brow("LIVE SESSIONS"), mp(-1, -2, 0, 18, 0, 0));
        page.addView(note(countLine(live, normal.size(), background.size(), hidden), 13), mp(-1, -2, 0, 8, 0, 4));
        page.addView(rule(), mp(-1, dp1(), 0, 12, 0, 20));

        for (SessionGroups.Group group : normal) page.addView(sessionCard(h, group), mp(-1, -2, 0, 0, 0, 16));
        if (normal.isEmpty()) page.addView(note(hidden == 0 ? "No live sessions on this PC."
                : "No visible sessions on this PC — restore one from Hidden sessions/agents.", 14), mp(-1, -2, 0, 0, 0, 16));

        if (showBackground) {
            page.addView(brow("BACKGROUND SESSIONS"), mp(-1, -2, 0, 8, 0, 0));
            page.addView(note("Flow and background sessions. Hidden by default; nothing is stopped or removed.", 13),
                    mp(-1, -2, 0, 8, 0, 12));
            if (background.isEmpty()) page.addView(note(hidden == 0 ? "No background sessions."
                    : "No visible background sessions.", 14), mp(-1, -2, 0, 0, 0, 16));
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

        Button hiddenBtn = button(hidden == 0 ? "Hidden sessions/agents"
                : "Hidden sessions/agents (" + hidden + ")");
        hiddenBtn.setContentDescription("Restore sessions and agents hidden on this phone");
        hiddenBtn.setOnClickListener(v -> showHiddenDialog(h));
        page.addView(hiddenBtn, mp(-1, -2, 0, 8, 0, 8));
        page.addView(note("Hiding is phone-only: your PC keeps every session running. Nothing is stopped or deleted.", 13),
                mp(-1, -2, 0, 0, 0, 12));

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

    // ---------- hidden sessions and agents (this phone only) ----------

    /** A defensive copy of one PC's hidden set; SharedPreferences sets stay live. */
    private Set<String> hiddenSet(String key) {
        Set<String> stored = preferences.getStringSet(key, null);
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    private void saveHidden(String key, Set<String> ids) {
        preferences.edit().putStringSet(key, new HashSet<>(ids)).apply();
    }

    /** Re-draws the list already on screen, or reconnects when there is none. */
    private void refreshSessions(Host h) {
        if (lastSessions != null && lastHost != null && lastHost.baseUrl().equals(h.baseUrl())) {
            showSessions(h, lastSessions);
        } else {
            connect(h);
        }
    }

    private void hideSession(Host h, SessionGroups.Group group) {
        String key = SessionGroups.hiddenSessionsKey(h.baseUrl());
        Set<String> ids = hiddenSet(key);
        ids.add(group.id);
        saveHidden(key, ids);
        refreshSessions(h);
        status("Hid “" + group.name + "” on this phone. It keeps running on " + h.name + ".");
    }

    private void hideBlock(Host h, SessionGroups.Group group, SessionGroups.Block block) {
        String key = SessionGroups.hiddenBlocksKey(h.baseUrl());
        Set<String> ids = hiddenSet(key);
        ids.add(SessionGroups.blockKey(group.id, block.blockId));
        saveHidden(key, ids);
        refreshSessions(h);
        status("Hid “" + block.label + "” on this phone. It keeps running on " + h.name + ".");
    }

    private void restoreSession(Host h, String id) {
        String key = SessionGroups.hiddenSessionsKey(h.baseUrl());
        Set<String> ids = hiddenSet(key);
        if (ids.remove(id)) saveHidden(key, ids);
        refreshSessions(h);
        status("Restored on this phone.");
    }

    private void restoreBlock(Host h, String blockKey) {
        String key = SessionGroups.hiddenBlocksKey(h.baseUrl());
        Set<String> ids = hiddenSet(key);
        if (ids.remove(blockKey)) saveHidden(key, ids);
        refreshSessions(h);
        status("Restored on this phone.");
    }

    /** Restores every agent hidden inside one session, stale entries included. */
    private void restoreSessionBlocks(Host h, SessionGroups.Group group) {
        String key = SessionGroups.hiddenBlocksKey(h.baseUrl());
        Set<String> ids = hiddenSet(key);
        boolean changed = false;
        for (String id : new ArrayList<>(ids)) if (id.startsWith(group.id + "/")) changed |= ids.remove(id);
        if (!changed) return;
        saveHidden(key, ids);
        refreshSessions(h);
        status("Restored the hidden agents in “" + group.name + "” on this phone.");
    }

    private void restoreAllHidden(Host h) {
        preferences.edit()
                .remove(SessionGroups.hiddenSessionsKey(h.baseUrl()))
                .remove(SessionGroups.hiddenBlocksKey(h.baseUrl()))
                .apply();
        refreshSessions(h);
        status("All hidden entries restored on this phone.");
    }

    /** Lists this PC's hidden entries; tap one to restore it. */
    private void showHiddenDialog(Host h) {
        if (lastSessions == null || lastHost == null) {
            status("Connect to " + h.name + " to restore hidden sessions.");
            return;
        }
        final Set<String> sessions = hiddenSet(SessionGroups.hiddenSessionsKey(h.baseUrl()));
        final Set<String> blocks = hiddenSet(SessionGroups.hiddenBlocksKey(h.baseUrl()));
        if (sessions.isEmpty() && blocks.isEmpty()) {
            status("Nothing is hidden on " + h.name + ".");
            return;
        }
        Map<String, String> sessionNames = new HashMap<>();
        Map<String, String> blockNames = new HashMap<>();
        for (SessionGroups.Group group : SessionGroups.group(lastSessions)) {
            sessionNames.put(group.id, group.name);
            for (SessionGroups.Block block : group.blocks) {
                blockNames.put(SessionGroups.blockKey(group.id, block.blockId), block.label);
            }
        }
        final List<String> sessionIds = new ArrayList<>(new TreeSet<>(sessions));
        final List<String> blockKeys = new ArrayList<>(new TreeSet<>(blocks));
        List<String> labels = new ArrayList<>();
        for (String id : sessionIds) {
            String name = sessionNames.get(id);
            labels.add("Session · " + (name == null ? id + "  (not on this PC now)" : name));
        }
        for (String key : blockKeys) {
            String label = blockNames.get(key);
            labels.add("Agent · " + (label == null ? key + "  (not on this PC now)" : label));
        }
        new AlertDialog.Builder(this)
                .setTitle("Hidden on this phone")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < sessionIds.size()) restoreSession(h, sessionIds.get(which));
                    else restoreBlock(h, blockKeys.get(which - sessionIds.size()));
                })
                .setNegativeButton("Close", null)
                .setNeutralButton("Restore all", (d, w) -> restoreAllHidden(h))
                .show();
    }

    /** Per-session menu: hiding is local to this phone and reversible here. */
    private void sessionMenu(Host h, SessionGroups.Group group) {
        new AlertDialog.Builder(this)
                .setTitle("Session · " + group.name + " · phone only")
                .setItems(new String[]{"Hide this session"}, (d, which) -> hideSession(h, group))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Per-agent menu, also reached by long-pressing the agent row. */
    private void agentMenu(Host h, SessionGroups.Group group, SessionGroups.Block block) {
        new AlertDialog.Builder(this)
                .setTitle("Agent · " + block.label + " · phone only")
                .setItems(new String[]{"Hide this agent"}, (d, which) -> hideBlock(h, group, block))
                .setNegativeButton("Cancel", null)
                .show();
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
        TextView count = text(countLabel(group), 11, group.liveCount() == 0 ? MUTED : GREEN,
                Typeface.MONOSPACE, Typeface.BOLD);
        count.setLetterSpacing(0.10f);
        head.addView(count);
        Button sessionOpts = smallButton("⋮");
        sessionOpts.setTextSize(18);
        sessionOpts.setContentDescription("Session options for " + group.name);
        sessionOpts.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2217634e), null, bg(Color.WHITE, Color.TRANSPARENT)));
        sessionOpts.setOnClickListener(v -> sessionMenu(h, group));
        head.addView(sessionOpts, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(head);
        SessionGroups.Block current = group.current();
        if (current != null) {
            card.addView(text("CURRENT  ·  " + current.label, 11, GREEN, Typeface.MONOSPACE, Typeface.BOLD),
                    mp(-1, -2, 0, 10, 0, 0));
        }
        for (int i = 0; i < group.blocks.size(); i++) {
            SessionGroups.Block block = group.blocks.get(i);
            LinearLayout blockRow = row();
            blockRow.setFocusable(true);
            blockRow.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2217634e), null, null));
            blockRow.setContentDescription("Open " + block.label + " in " + group.name);
            LinearLayout blockText = column();
            TextView label = text((block.focused ? "● " : "") + block.label, 16, INK, Typeface.DEFAULT_BOLD);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            blockText.addView(label);
            blockText.addView(text(blockSub(block), 12, MUTED, Typeface.MONOSPACE), mp(-1, -2, 0, 6, 0, 0));
            blockRow.addView(blockText, weight1());
            Button agentOpts = smallButton("⋮");
            agentOpts.setTextSize(18);
            agentOpts.setContentDescription("Agent options for " + block.label);
            agentOpts.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2217634e), null, bg(Color.WHITE, Color.TRANSPARENT)));
            agentOpts.setOnClickListener(v -> agentMenu(h, group, block));
            blockRow.addView(agentOpts, new LinearLayout.LayoutParams(dp(48), dp(48)));
            blockRow.setOnClickListener(v -> showBlock(h, group.name, block.blockId, block.label));
            blockRow.setOnLongClickListener(v -> {
                agentMenu(h, group, block);
                return true;
            });
            card.addView(blockRow, mp(-1, -2, 0, i == 0 ? 12 : 10, 0, 0));
        }
        if (group.hiddenBlocks > 0) {
            Button restore = smallButton("Restore " + group.hiddenBlocks
                    + (group.hiddenBlocks == 1 ? " hidden agent" : " hidden agents"));
            restore.setContentDescription("Restore agents hidden in " + group.name);
            restore.setOnClickListener(v -> restoreSessionBlocks(h, group));
            card.addView(restore, mp(-1, dp(48), 0, 12, 0, 0));
        }
        return card;
    }

    /** The header count, always the visible agents, plus what this phone hides. */
    private static String countLabel(SessionGroups.Group group) {
        String live = group.liveCount() == 0 ? "NO LIVE BLOCKS" : group.liveCount() + " LIVE";
        if (group.hiddenBlocks == 0) return live;
        return group.liveCount() == 0 ? "ALL " + group.hiddenBlocks + " HIDDEN"
                : live + "  ·  " + group.hiddenBlocks + " HIDDEN";
    }

    private static String countLine(int live, int sessions, int background, int hidden) {
        return live + (live == 1 ? " live block" : " live blocks") + "  ·  " + sessions
                + (sessions == 1 ? " session" : " sessions")
                + (background == 0 ? "" : "  ·  " + background + " background")
                + (hidden == 0 ? "" : "  ·  " + hidden + " hidden");
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

        // Compact chat header: identity, mode, Tools and Settings. The reading
        // surface carries no nested card borders.
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(6), dp(8), dp(6));
        header.setBackgroundColor(PAPER);
        Button backBtn = smallButton("‹");
        backBtn.setTextSize(28);
        backBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2217634e),
                null, bg(Color.WHITE, Color.TRANSPARENT)));
        backBtn.setContentDescription("Back");
        backBtn.setOnClickListener(v -> connect(h));
        header.addView(backBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = column();
        LinearLayout.LayoutParams titlesLp = weight1();
        titlesLp.leftMargin = dp(8);
        TextView headLabel = text(label, 17, INK, Typeface.DEFAULT, Typeface.BOLD);
        headLabel.setSingleLine(true);
        headLabel.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(headLabel);
        LinearLayout subRow = row();
        TextView subLabel = text(h.name + "  ·  " + sessionName, 12, MUTED, Typeface.DEFAULT, Typeface.NORMAL);
        subLabel.setSingleLine(true);
        subLabel.setEllipsize(TextUtils.TruncateAt.END);
        subRow.addView(subLabel, weight1());
        TextView live = text("Connecting…", 12, MUTED, Typeface.DEFAULT, Typeface.BOLD);
        subRow.addView(live);
        titles.addView(subRow, mp(-1, -2, 0, 2, 0, 0));
        header.addView(titles, titlesLp);
        final Button toolsBtn = smallButton("Tools");
        toolsBtn.setTextSize(13);
        toolsBtn.setMinWidth(dp(56));
        toolsBtn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2217634e),
                null, bg(Color.WHITE, Color.TRANSPARENT)));
        toolsBtn.setContentDescription("Tools and keyboard shortcuts");
        ViewCompat.setStateDescription(toolsBtn, "Collapsed");
        toolsBtn.setOnClickListener(v -> {
            toolsExpanded = !toolsExpanded;
            toolsBtn.setSelected(toolsExpanded);
            ViewCompat.setStateDescription(toolsBtn, toolsExpanded ? "Expanded" : "Collapsed");
            updateStripVisibility();
        });
        header.addView(toolsBtn, new LinearLayout.LayoutParams(-2, dp(48)));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));
        View headerRule = rule();
        headerRule.setBackgroundColor(0xffb4c8b7);
        page.addView(headerRule, new LinearLayout.LayoutParams(-1, dp1()));

        final int[] pageEnd = {-1}; // -1 follows the latest page; otherwise a history boundary.
        final int[] pageStart = {0};
        final boolean[] changingPage = {false};
        LinearLayout historyBar = row();
        historyBar.setPadding(dp(12), 0, dp(12), 0);
        historyBar.setVisibility(View.GONE);
        Button earlierButton = smallButton("Earlier");
        earlierButton.setTextSize(14);
        earlierButton.setContentDescription("Load earlier messages");
        historyBar.addView(earlierButton, new LinearLayout.LayoutParams(dp(72), dp(48)));
        TextView pageRange = text("", 12, MUTED, Typeface.DEFAULT);
        pageRange.setGravity(Gravity.CENTER);
        historyBar.addView(pageRange, weight1());
        Button latestButton = smallButton("Latest");
        latestButton.setTextSize(14);
        latestButton.setContentDescription("Load latest messages");
        historyBar.addView(latestButton, new LinearLayout.LayoutParams(dp(72), dp(48)));
        page.addView(historyBar, new LinearLayout.LayoutParams(-1, dp(48)));

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

        // Capture refreshes must not erase an unconfirmed-send error.
        final TextView captureNote = text("", 12, CORAL, Typeface.MONOSPACE);
        captureNote.setContentDescription("Live screen status");
        captureNote.setVisibility(View.GONE);
        captureNote.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.addView(captureNote, new LinearLayout.LayoutParams(-1, -2));
        status = text("", 12, CORAL, Typeface.MONOSPACE);
        status.setContentDescription("Send status");
        status.setVisibility(View.GONE);
        status.setPadding(dp(12), dp(4), dp(12), dp(4));
        page.addView(status, new LinearLayout.LayoutParams(-1, -2));

        // Attachment tray: pending files for this conversation, pinned above the composer.
        attachmentTray = column();
        attachmentTray.setPadding(dp(12), dp(4), dp(12), dp(4));
        attachmentTray.setBackgroundColor(PAPER);
        attachmentTrayScroll = new ScrollView(this);
        attachmentTrayScroll.setVisibility(View.GONE);
        attachmentTrayScroll.addView(attachmentTray);
        page.addView(attachmentTrayScroll, new LinearLayout.LayoutParams(-1, 0));

        // 3. Shortcut strip: one horizontally scrollable row of terminal keys.
        String[][] keys = {
                {"Ctrl+P", "ctrl+p"}, {"Ctrl+C", "ctrl+c"}, {"Esc", "Escape"}, {"Tab", "Tab"},
                {"Enter", "Enter"}, {"↑", "Up"}, {"↓", "Down"}, {"←", "Left"}, {"→", "Right"}};
        HorizontalScrollView stripScroll = new HorizontalScrollView(this);
        terminalKeys = stripScroll;
        stripScroll.setVisibility(toolsExpanded && !imeVisible ? View.VISIBLE : View.GONE);
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

        // 4. Pinned composer: attach control, multiline draft, explicit Send.
        LinearLayout composer = row();
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(12), dp(8), dp(12), dp(10));
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
        input.setContentDescription("Message or command");
        draft = input;
        final Button attach = smallButton("＋");
        attach.setTextSize(22);
        attach.setMinWidth(dp(48));
        attach.setMinimumWidth(dp(48));
        attach.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2217634e),
                null, bg(0xffeeeee5, MUTED)));
        attach.setContentDescription("Attach image or file");
        attach.setOnClickListener(v -> launchPicker());
        attachButton = attach;
        composer.addView(attach, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams inputLp = weight1();
        inputLp.leftMargin = dp(8);
        composer.addView(input, inputLp);
        final Button send = button("Send");
        send.setTextSize(14);
        send.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        send.setMinWidth(dp(64));
        send.setMinimumWidth(dp(64));
        send.setTextColor(PAPER);
        send.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffffff),
                bg(GREEN, GREEN), null));
        send.setContentDescription("Send message");
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(-2, dp(48));
        sendLp.leftMargin = dp(8);
        composer.addView(send, sendLp);
        sendButton = send;
        page.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateSendEnabled(); }
        });
        send.setOnClickListener(v -> submitDraft(h, sessionName, blockId, input, send));
        updateSendEnabled();

        // Chat captions may carry image attachments; only the 32-hex ids the
        // renderer mints are ever streamed, and only through the authenticated
        // bridge GET. Everything else the WebView asks for is refused.
        pane.setAttachmentLoader(id -> loadAttachment(h, sessionName, blockId, id));

        pane.setOnTailChangeListener(atTail -> pane.setJumpToLatestVisible(!atTail));

        // Live screen: one capture in flight at a time; the next poll is only
        // scheduled after the current request completes (no 2s backlog).
        final Runnable tick = new Runnable() {
            @Override public void run() {
                final int requestedEnd = pageEnd[0];
                io.execute(() -> {
                    try {
                        JSONObject req = new JSONObject()
                                .put("block", sessionName + "/" + blockId).put("format", "conversation");
                        if (requestedEnd >= 0) req.put("before", requestedEnd);
                        HttpResult r = http(h, "POST", "/capture", req);
                        final JSONObject capture = r.code == 200 ? new JSONObject(r.body) : null;
                        final String captureType = capture == null ? "" : capture.getString("type");
                        if (capture != null && !"conversation".equals(captureType)
                                && !"terminal".equals(captureType)) {
                            throw new Exception("Unsupported screen response");
                        }
                        runUi(() -> {
                            if (gen != viewGen || requestedEnd != pageEnd[0]) return;
                            if (r.code == 200) {
                                status(captureNote, "");
                                if ("conversation".equals(captureType)) {
                                    pane.loadConversation(capture);
                                    pageStart[0] = capture.optInt("start");
                                    int end = capture.optInt("end");
                                    int total = capture.optInt("total");
                                    historyBar.setVisibility(pageStart[0] > 0 || requestedEnd >= 0
                                            ? View.VISIBLE : View.GONE);
                                    earlierButton.setEnabled(pageStart[0] > 0);
                                    latestButton.setEnabled(requestedEnd >= 0);
                                    pageRange.setText((pageStart[0] + 1) + "–" + end + " / " + total);
                                    if (changingPage[0]) {
                                        changingPage[0] = false;
                                        pane.postDelayed(pane::scrollToTail, 100);
                                    }
                                } else {
                                    pane.loadCapture(capture.optString("html"));
                                }
                                live.setTextColor(GREEN);
                                live.setText("conversation".equals(captureType)
                                        ? (requestedEnd >= 0 ? "History" : "Chat") : "Terminal");
                            } else {
                                status(captureNote, "Not live: " + r.body);
                                earlierButton.setEnabled(pageStart[0] > 0);
                                latestButton.setEnabled(requestedEnd >= 0);
                                live.setTextColor(CORAL);
                                live.setText("Offline");
                            }
                            scheduleNext(gen, this);
                        });
                    } catch (Exception e) {
                        runUi(() -> {
                            if (gen != viewGen || requestedEnd != pageEnd[0]) return;
                            status(captureNote, "Screen unavailable: " + shortError(e));
                            earlierButton.setEnabled(pageStart[0] > 0);
                            latestButton.setEnabled(requestedEnd >= 0);
                            live.setTextColor(CORAL);
                            live.setText("Offline");
                            scheduleNext(gen, this);
                        });
                    }
                });
            }
        };
        earlierButton.setOnClickListener(v -> {
            pageEnd[0] = pageStart[0];
            changingPage[0] = true;
            earlierButton.setEnabled(false);
            latestButton.setEnabled(false);
            if (refreshTick != null) ui.removeCallbacks(refreshTick);
            tick.run();
        });
        latestButton.setOnClickListener(v -> {
            pageEnd[0] = -1;
            changingPage[0] = true;
            earlierButton.setEnabled(false);
            latestButton.setEnabled(false);
            if (refreshTick != null) ui.removeCallbacks(refreshTick);
            tick.run();
        });
        pane.setOnJumpToLatestListener(() -> {
            if (pageEnd[0] >= 0) latestButton.performClick();
            else pane.scrollToTail();
        });
        tick.run();
    }

    private void scheduleNext(int gen, Runnable tick) {
        if (gen != viewGen) return;
        refreshTick = tick;
        ui.postDelayed(tick, 2000);
    }

    /** Uploads any pending attachments, then submits the draft (and Enter) in one
     *  accepted action. The draft stays editable in flight; text and files are
     *  cleared only when the accepted snapshot is still unchanged. */
    private void submitDraft(Host h, String sessionName, String blockId, EditText input, Button sendButton) {
        final String snapshot = input.getText().toString();
        final ArrayList<Pending> snapshotPending = new ArrayList<>(pendingAttachments);
        if (snapshot.trim().isEmpty() && snapshotPending.isEmpty()) return;
        if (sendBusy || !sendButton.isEnabled()) return;
        final int gen = viewGen;
        setBusy(true);
        final String selector = sessionName + "/" + blockId;
        io.execute(() -> {
            try {
                JSONArray ids = new JSONArray();
                for (int i = 0; i < snapshotPending.size(); i++) {
                    Pending p = snapshotPending.get(i);
                    if (p.id == null) {
                        final int at = i + 1;
                        final int total = snapshotPending.size();
                        runUi(() -> {
                            if (gen == viewGen) status("Uploading " + at + " of " + total + "…");
                        });
                        p.id = upload(h, selector, p);
                    }
                    ids.put(p.id);
                }
                if (!snapshotPending.isEmpty()) runUi(() -> {
                    if (gen == viewGen) status("Sending…");
                });
                JSONObject b = new JSONObject().put("block", selector).put("kind", "submit")
                        .put("value", snapshot).put("attachments", ids);
                HttpResult r = http(h, "POST", "/send", b);
                if (r.code != 200) throw new Exception(r.body);
                JSONObject acknowledgement = new JSONObject(r.body);
                if (!acknowledgement.optBoolean("ok")
                        || (!snapshotPending.isEmpty() && !acknowledgement.optBoolean("accepted"))) {
                    throw new Exception("Delivery not confirmed — check the PC before retrying.");
                }
                runUi(() -> {
                    if (gen != viewGen) return;
                    status("");
                    setBusy(false);
                    boolean sameText = input.getText().toString().equals(snapshot);
                    boolean sameAttachments = pendingAttachments.equals(snapshotPending);
                    if (sameText) input.setText("");
                    if (sameAttachments) {
                        pendingAttachments.clear();
                        rebuildTray();
                    }
                    updateSendEnabled();
                });
            } catch (Exception e) {
                runUi(() -> {
                    if (gen != viewGen) return;
                    status("Send failed: " + shortError(e) + " Your text and files are kept.");
                    setBusy(false);
                    updateSendEnabled();
                });
            }
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
                runUi(() -> {
                    status("");
                    if (result != null) result.done(true);
                });
            } catch (Exception e) {
                runUi(() -> {
                    status("Send failed: " + shortError(e));
                    if (result != null) result.done(false);
                });
            }
        });
    }

    // ---------- attachments ----------

    /** One file chosen for this conversation; uploaded lazily on first send. */
    private static final class Pending {
        final Uri uri;
        final String name;
        final String mime;
        final long size;
        String id;
        Bitmap thumb;

        Pending(Uri uri, String name, String mime, long size) {
            this.uri = uri;
            this.name = name;
            this.mime = mime == null ? "" : mime;
            this.size = size;
        }
    }

    private void launchPicker() {
        if (sendBusy) return;
        if (pendingAttachments.size() >= AttachmentSupport.MAX_FILES) {
            status("Up to 8 attachments per message.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Attach")
                .setItems(new String[]{"Images", "Files"}, (d, which) -> openDocument(which == 0 ? "image/*" : "*/*"))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Opens the system document picker for images or for any file, both multi-select. */
    private void openDocument(String type) {
        pickerGen = viewGen; // a result arriving after a screen change is dropped
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_ATTACH);
        } catch (Exception e) {
            status("No file picker available: " + shortError(e));
        }
    }

    private void addPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        int room = AttachmentSupport.MAX_FILES - pendingAttachments.size();
        if (room <= 0) {
            status("Up to 8 attachments per message.");
            return;
        }
        final boolean clipped = uris.size() > room;
        final List<Uri> take = new ArrayList<>(uris.subList(0, Math.min(room, uris.size())));
        final int gen = viewGen;
        io.execute(() -> {
            List<AttachmentSupport.Doc> docs = AttachmentSupport.resolve(this, take);
            final ArrayList<Pending> added = new ArrayList<>();
            final ArrayList<String> rejected = new ArrayList<>();
            for (AttachmentSupport.Doc d : docs) {
                long size = d.knownSize && d.size >= 0 ? d.size
                        : AttachmentSupport.measureBytes(this, d.uri, AttachmentSupport.MAX_BYTES);
                if (size < 0 || size > AttachmentSupport.MAX_BYTES) {
                    rejected.add(d.name);
                    continue;
                }
                added.add(new Pending(d.uri, d.name, d.mime, size));
            }
            runUi(() -> {
                if (gen != viewGen) return;
                pendingAttachments.addAll(added);
                rebuildTray();
                updateSendEnabled();
                if (!rejected.isEmpty()) {
                    status("Skipped " + rejected.size() + (rejected.size() == 1 ? " file" : " files")
                            + " unreadable or over 20 MiB: " + rejected.get(0) + (rejected.size() > 1 ? "…" : ""));
                } else if (clipped) {
                    status("Added the first " + room + " — 8 attachments max.");
                }
            });
        });
    }

    private void rebuildTray() {
        if (attachmentTray == null) return;
        attachmentTray.removeAllViews();
        for (Pending p : pendingAttachments) attachmentTray.addView(trayRow(p));
        boolean empty = pendingAttachments.isEmpty();
        attachmentTrayScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        // ponytail: a two-row viewport keeps Send reachable with all eight files.
        attachmentTrayScroll.getLayoutParams().height = empty ? 0
                : dp(Math.min(144, pendingAttachments.size() * 64 + 8));
        attachmentTrayScroll.requestLayout();
    }

    private View trayRow(final Pending p) {
        LinearLayout row = row();
        row.setBackground(bg(CARD, EDGE));
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        if (AttachmentSupport.isPreviewableImage(p.mime)) {
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setContentDescription("Image preview");
            if (p.thumb != null) thumb.setImageBitmap(p.thumb);
            else decodeThumb(p, thumb);
            row.addView(thumb, new LinearLayout.LayoutParams(dp(44), dp(44)));
        } else {
            String ext = AttachmentSupport.extension(p.name);
            TextView tag = text(ext.isEmpty() ? "FILE" : ext.toUpperCase(java.util.Locale.ROOT), 11,
                    GREEN, Typeface.MONOSPACE, Typeface.BOLD);
            tag.setGravity(Gravity.CENTER);
            tag.setBackground(bg(0xffeeeee5, EDGE));
            row.addView(tag, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        LinearLayout meta = column();
        LinearLayout.LayoutParams metaLp = weight1();
        metaLp.leftMargin = dp(10);
        TextView name = text(p.name, 14, INK, Typeface.DEFAULT, Typeface.NORMAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(name);
        String type = p.mime == null || p.mime.isEmpty() ? "unknown type" : p.mime;
        meta.addView(text(type + "  ·  " + formatSize(p.size), 11, MUTED, Typeface.DEFAULT, Typeface.NORMAL));
        row.addView(meta, metaLp);
        Button remove = smallButton("✕");
        remove.setTextSize(16);
        remove.setContentDescription("Remove " + p.name);
        remove.setOnClickListener(v -> {
            pendingAttachments.remove(p);
            rebuildTray();
            updateSendEnabled();
        });
        row.addView(remove, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    private void decodeThumb(final Pending p, final ImageView view) {
        final int gen = viewGen;
        final int maxPx = dp(96);
        io.execute(() -> {
            final Bitmap bitmap = AttachmentSupport.thumbnail(this, p.uri, maxPx);
            runUi(() -> {
                if (gen != viewGen || bitmap == null) return;
                p.thumb = bitmap;
                view.setImageBitmap(bitmap);
            });
        });
    }

    private void updateSendEnabled() {
        if (sendButton == null) return;
        boolean hasText = draft != null && !draft.getText().toString().trim().isEmpty();
        sendButton.setEnabled(!sendBusy && (hasText || !pendingAttachments.isEmpty()));
    }

    private void setBusy(boolean busy) {
        sendBusy = busy;
        if (attachButton != null) attachButton.setEnabled(!busy);
        updateSendEnabled();
    }

    private void updateStripVisibility() {
        if (terminalKeys != null) {
            terminalKeys.setVisibility(toolsExpanded && !imeVisible ? View.VISIBLE : View.GONE);
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Uploads one picked file as raw bytes with a fixed Content-Length. */
    private String upload(Host h, String selector, Pending p) throws Exception {
        String mime = p.mime == null || p.mime.trim().isEmpty() ? "application/octet-stream" : p.mime;
        String query = "?block=" + URLEncoder.encode(selector, "UTF-8")
                + "&name=" + URLEncoder.encode(p.name, "UTF-8")
                + "&mime=" + URLEncoder.encode(mime, "UTF-8");
        long started = SystemClock.elapsedRealtime();
        HttpURLConnection c = (HttpURLConnection) new URL(h.baseUrl() + "/upload" + query).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(120000);
            authorize(c, h);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(p.size);
            try (InputStream in = getContentResolver().openInputStream(p.uri);
                 OutputStream os = c.getOutputStream()) {
                if (in == null) throw new Exception("That file can no longer be read.");
                byte[] buffer = new byte[8 * 1024];
                long written = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    os.write(buffer, 0, read);
                    written += read;
                }
                if (written != p.size) throw new Exception("That file changed while uploading.");
            }
            int code = c.getResponseCode();
            String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream(), 200_000);
            Log.d(TAG, "upload status=" + code + " ms=" + (SystemClock.elapsedRealtime() - started)
                    + " len=" + p.size);
            if (code != 200) throw new Exception(body.isEmpty() ? "HTTP " + code : body);
            String id = new JSONObject(body).getString("id");
            if (!UPLOAD_ID.matcher(id).matches()) throw new Exception("Bridge returned an invalid upload id.");
            return id;
        } finally {
            c.disconnect();
        }
    }

    /** Serves one renderer attachment image from the authenticated bridge GET. */
    private WebResourceResponse loadAttachment(Host h, String sessionName, String blockId, String id) {
        if (id == null || !UPLOAD_ID.matcher(id).matches()) return blockedAttachment();
        HttpURLConnection c = null;
        try {
            String selector = sessionName + "/" + blockId;
            c = (HttpURLConnection) new URL(h.baseUrl() + "/attachment?block="
                    + URLEncoder.encode(selector, "UTF-8") + "&id=" + id).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(4000);
            c.setReadTimeout(20000);
            authorize(c, h);
            int code = c.getResponseCode();
            String contentType = c.getContentType();
            String mime = contentType == null ? "" : contentType.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
            if (code != 200 || !AttachmentSupport.isPreviewableImage(mime)) {
                c.disconnect();
                return blockedAttachment();
            }
            return new WebResourceResponse(mime, null, new DisconnectingInputStream(c.getInputStream(), c));
        } catch (Exception e) {
            if (c != null) c.disconnect();
            return blockedAttachment();
        }
    }

    private static WebResourceResponse blockedAttachment() {
        return new WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
    }

    private static String readAll(InputStream in, int cap) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                if (sb.length() + read > cap) throw new Exception("Response exceeded " + cap + " characters — refused.");
                sb.append(buffer, 0, read);
            }
            return sb.toString();
        }
    }

    /** Keeps the connection alive for the stream's lifetime, then closes it. */
    private static final class DisconnectingInputStream extends FilterInputStream {
        private final HttpURLConnection connection;
        private boolean closed;

        DisconnectingInputStream(InputStream in, HttpURLConnection connection) {
            super(in);
            this.connection = connection;
        }

        @Override public void close() throws java.io.IOException {
            try {
                super.close();
            } finally {
                if (!closed) {
                    closed = true;
                    connection.disconnect();
                }
            }
        }
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
        if (requestCode == REQUEST_ATTACH) {
            if (resultCode != RESULT_OK || data == null) return; // cancelled: draft and tray untouched
            if (viewGen != pickerGen) {
                status("Selection ignored — you moved to a different session.");
                return;
            }
            ArrayList<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri uri = clip.getItemAt(i).getUri();
                    if (uri != null) uris.add(uri);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            for (Uri uri : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                    // Not all providers hand out persistable grants; the read grant still applies.
                }
            }
            addPicked(uris);
            return;
        }
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
        final String body;
        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /** Applies the bridge auth headers; bridge and Access credentials never follow a redirect. */
    private static void authorize(HttpURLConnection c, Host h) {
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Authorization", "Bearer " + h.token);
        if (!h.accessId.isEmpty()) {
            c.setRequestProperty("CF-Access-Client-Id", h.accessId);
            c.setRequestProperty("CF-Access-Client-Secret", h.accessSecret);
        }
    }

    /** Logs only method, endpoint, status, duration and length — never bodies, names or addresses. */
    private static HttpResult http(Host h, String method, String path, JSONObject payload) throws Exception {
        long started = SystemClock.elapsedRealtime();
        HttpURLConnection c = (HttpURLConnection) new URL(h.baseUrl() + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(4000);
            c.setReadTimeout(15000);
            authorize(c, h);
            if (payload != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            String body = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream(), HTTP_BODY_LIMIT);
            Log.d(TAG, "http " + method + " " + path + " status=" + code
                    + " ms=" + (SystemClock.elapsedRealtime() - started) + " len=" + body.length());
            return new HttpResult(code, body);
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
        toolsExpanded = false;
        attachmentTray = null;
        attachmentTrayScroll = null;
        draft = null;
        sendButton = null;
        attachButton = null;
        sendBusy = false;
        pickerGen = -1;
        pendingAttachments.clear();
        if (refreshTick != null) { ui.removeCallbacks(refreshTick); refreshTick = null; }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0);
    }

    private void runUi(Runnable r) { ui.post(r); }

    private void status(String s) {
        status(status, s);
    }

    private void status(TextView target, String s) {
        if (target != null) {
            target.setText(s);
            target.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
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
