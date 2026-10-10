package io.github.gowanginc.terncompanion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only terminal pane for Tern captures: a built-in WebView locked to a
 * local inert document. HTML captures from the bridge are sanitized to a
 * pre/span/br whitelist with a short inline-style allow-list before they reach
 * the DOM; no navigation, no network, no bridges. Older bridges that still
 * answer with plain text are shown escaped through the same pane.
 *
 * Conversation captures are rendered locally as readable chat: system prose type
 * for You/Assistant turns (user bubbles right-aligned on muted sage, assistant
 * text on the open page), and consecutive tool/context rows condensed into one
 * collapsed Activity group that keeps every character for expansion. Roles come
 * from a fixed set, every label/name/text is escaped, CommonMark markdown is
 * rendered with raw HTML escaped and URLs sanitized, and the reader's expansion
 * state survives live refresh through id-derived (never offset-derived) keys.
 *
 * Attachments are drawn from untrusted JSON: only a lowercase 32-hex id with a
 * raster image mime becomes an inline `https://attachments.tern.invalid/<id>`
 * image, everything else becomes a name/type/size card, and a non-conforming id
 * renders nothing. The pane's own request interceptor is the only consumer of
 * that host and calls the host-supplied AttachmentLoader for ids it just
 * rendered; every other http/https/file/content fetch stays blocked and the CSP
 * still forbids external images, scripts, frames and any other network.
 *
 * Terminal captures keep a separate compact monospace layout so a raw screen is
 * never dressed up as conversation.
 *
 * Updates replace the DOM in place (never reload the page). The reader's
 * scroll position is preserved while browsing history; new output only pulls
 * the view to the tail when the reader was already at the tail.
 */
public final class TerminalView extends FrameLayout {
    private static final String BG = "#f5f2e9";
    private static final String INK = "#172622";
    private static final int TAIL_TOLERANCE_DP = 8;
    private static final long TAIL_SETTLE_MS = 80;

    private static final Set<String> TAG_WHITELIST = new HashSet<>(Arrays.asList("pre", "span", "br"));
    private static final Set<String> STYLE_PROPS = new HashSet<>(Arrays.asList(
            "color", "background-color", "font-weight", "font-style", "text-decoration"));
    private static final Pattern BLOCK_RE = Pattern.compile(
            "(?is)<\\s*(script|style|iframe|object|embed|svg|math|link|meta|base|form|"
                    + "frame|frameset|applet|audio|video|img|a)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>");
    private static final Pattern LONE_TAG_RE = Pattern.compile(
            "(?is)<\\s*/?\\s*(script|style|iframe|object|embed|svg|math|link|meta|base|form|"
                    + "frame|frameset|applet|audio|video|img|a)\\b[^>]*>");
    private static final Pattern COMMENT_RE = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAG_RE = Pattern.compile("<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9]*)\\b([^>]*)>");
    private static final Pattern STYLE_ATTR_RE = Pattern.compile("(?is)style\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern SAFE_VALUE_RE = Pattern.compile("^[a-zA-Z0-9#.,%()\\- ]+$");

    /** Root class that lifts the pane out of terminal whitespace into chat prose. */
    private static final String CONVO_CLASS = "convo";
    /** Root class for a raw screen capture: compact monospace, never chat chrome. */
    private static final String TERM_CLASS = "term";
    private static final Set<String> CONVO_ROLES =
            new HashSet<>(Arrays.asList("user", "assistant", "tool", "context"));

    /** The one origin the pane's images may come from; hardcoded, never from payload. */
    static final String ATTACHMENT_HOST = "attachments.tern.invalid";
    private static final Pattern ATTACHMENT_PATH = Pattern.compile("^/([0-9a-f]{32})$");
    private static final Pattern ATTACHMENT_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SAFE_MIME = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}");
    private static final Set<String> RASTER_MIME = new HashSet<>(Arrays.asList(
            "image/png", "image/jpeg", "image/webp", "image/gif"));

    private static final String SHELL_HEAD = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta http-equiv=\"Content-Security-Policy\" content=\""
            + "default-src 'none'; img-src https://" + ATTACHMENT_HOST + "; font-src 'none';"
            + " connect-src 'none'; media-src 'none'; object-src 'none'; frame-src 'none';"
            + " worker-src 'none'; base-uri 'none'; form-action 'none';"
            + " style-src 'unsafe-inline'; script-src 'unsafe-inline'\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0,"
            + " maximum-scale=10.0, user-scalable=yes\">"
            + "<style>html,body{margin:0;padding:0;background:" + BG + ";overflow:auto}"
            + "#t{margin:0;padding:0;font-family:monospace;font-size:16px;line-height:1.5;"
            + "white-space:pre-wrap;overflow-wrap:anywhere;color:" + INK + ";}"
            + "#t pre{font:inherit;margin:0;padding:0;}"
            + "::selection{background:#d5e2d5;color:" + INK + ";}"
            // Terminal captures: compact monospace dump, deliberately not chat chrome.
            + "#t.term{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;"
            + "font-size:0.95em;line-height:1.38;}"
            // Conversation: prose first; frames only where they carry meaning.
            + "#t.convo{font-family:-apple-system,system-ui,\"Segoe UI\",Roboto,sans-serif;"
            + "white-space:normal;overflow-wrap:break-word;line-height:1.55;font-size:16px;}"
            + "#t.convo .msg{margin:0 0 14px;}"
            + "#t.convo .who{display:block;font-size:0.72em;font-weight:600;letter-spacing:0.07em;"
            + "text-transform:uppercase;color:#6b7770;margin:0 0 4px;}"
            + "#t.convo .name{font-weight:400;letter-spacing:0;text-transform:none;margin-left:6px;"
            + "color:#8a6f33;overflow-wrap:anywhere;}"
            + "#t.convo .msg.user{display:flex;flex-direction:column;align-items:flex-end;}"
            + "#t.convo .user .who{text-align:right;}"
            + "#t.convo .user .body{max-width:88%;background:#e6ece1;border-radius:14px 14px 4px 14px;"
            + "padding:8px 12px;}"
            + "#t.convo .assistant .body{max-width:100%;}"
            + "#t.convo details.activity{margin:2px 0 14px;padding-left:10px;"
            + "border-left:2px solid #d9d4c6;}"
            + "#t.convo details.activity>summary{cursor:pointer;color:#5d6b64;font-size:0.76em;"
            + "font-weight:600;letter-spacing:0.06em;text-transform:uppercase;padding:6px 0;"
            + "min-height:32px;}"
            + "#t.convo summary:focus-visible{outline:2px solid #17634e;}"
            + "#t.convo .act{margin:6px 0 6px 8px;padding-left:8px;border-left:2px solid #e4dfd2;}"
            + "#t.convo .act .body{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;"
            + "font-size:0.82em;white-space:pre-wrap;overflow-wrap:anywhere;max-height:16em;"
            + "overflow:auto;background:#efece2;padding:6px 8px;border-radius:6px;}"
            + "#t.convo h1,#t.convo h2,#t.convo h3,#t.convo h4,#t.convo h5,#t.convo h6"
            + "{margin:0.7em 0 0.3em;line-height:1.25;}"
            + "#t.convo h1{font-size:1.35em;}#t.convo h2{font-size:1.2em;}"
            + "#t.convo h3{font-size:1.08em;}#t.convo h4,#t.convo h5,#t.convo h6{font-size:1em;}"
            + "#t.convo p{margin:0.45em 0;}"
            + "#t.convo ul,#t.convo ol{margin:0.45em 0;padding-left:1.5em;}"
            + "#t.convo li{margin:0.15em 0;}"
            + "#t.convo blockquote{margin:0.45em 0;padding-left:0.8em;"
            + "border-left:2px solid #d8d2c4;color:#5d6b64;}"
            + "#t.convo hr{border:0;border-top:1px solid #d8d2c4;margin:0.7em 0;}"
            + "#t.convo table{display:block;overflow-x:auto;border-collapse:collapse;font-size:0.9em;}"
            + "#t.convo th,#t.convo td{border:1px solid #d8d2c4;padding:2px 6px;}"
            + "#t.convo a{color:#365e7a;}"
            + "#t.convo .plain{white-space:pre-wrap;overflow-wrap:anywhere;}"
            + "#t.convo code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;"
            + "font-size:0.9em;background:#efece2;border-radius:3px;padding:0 3px;}"
            // Fenced code scrolls sideways; the pane never becomes one raw dump.
            + "#t.convo pre{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;"
            + "font-size:0.84em;line-height:1.45;white-space:pre;overflow-x:auto;"
            + "-webkit-overflow-scrolling:touch;background:#efece2;padding:6px 8px;border-radius:6px;}"
            + "#t.convo pre code{background:none;padding:0;font-size:1em;}"
            + "#t.convo .attach{margin-top:6px;max-width:100%;min-width:0;}"
            + "#t.convo .attach-img{display:block;max-width:100%;max-height:22em;width:auto;"
            + "height:auto;border-radius:8px;background:#e9e5db;}"
            + "#t.convo .attach-file{display:inline-flex;flex-direction:column;gap:2px;"
            + "background:#efece2;border:1px solid #ded8ca;border-radius:8px;padding:6px 10px;"
            + "max-width:100%;}"
            + "#t.convo .attach-name{font-weight:600;overflow-wrap:anywhere;}"
            + "#t.convo .attach-meta{font-size:0.82em;color:#6b7770;}"
            + "</style></head><body>";
    private static final String SHELL_SCRIPT =
            "<script>function tSet(h){const t=document.getElementById('t');"
            + "const open=new Set(Array.from(t.querySelectorAll('details[open]')).map(d=>d.dataset.key));"
            + "t.innerHTML=h;t.querySelectorAll('details').forEach(d=>{d.open=open.has(d.dataset.key);});}"
            + "function tMode(c){document.getElementById('t').className=c;}</script>";

    public interface OnTailChangeListener {
        void onTailChange(boolean atTail);
    }

    /**
     * Supplies the bytes behind one attachment id this pane has just rendered.
     * The host owns authentication and session binding; returning null hides the
     * image. Called from the WebView's request thread, only for ids that passed
     * the pane's own host/path/id checks.
     */
    public interface AttachmentLoader {
        WebResourceResponse load(String id);
    }

    private final WebView webView;
    private final Button jumpFab;
    private final float density;
    private final float fontScale;
    private OnTailChangeListener tailListener;
    private AttachmentLoader attachmentLoader;
    /** Ids rendered as images by the newest conversation; the loader gate for {@link #ATTACHMENT_HOST}. */
    private volatile Set<String> renderedAttachmentIds = Collections.emptySet();
    private boolean loaded;
    private String lastFragment = "";
    private String lastClass = "";
    private int textSizeSp = 16;
    private float sizeRatio;
    private boolean resizingText;
    private boolean resizeAtTail;

    public TerminalView(Context context) {
        this(context, null);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        fontScale = context.getResources().getConfiguration().fontScale;
        webView = new WebView(context);
        FrameLayout.LayoutParams terminal = new FrameLayout.LayoutParams(-1, -1);
        terminal.setMargins(dp(16), dp(12), dp(16), dp(12));
        addView(webView, terminal);
        setBackgroundColor(Color.parseColor(BG));

        jumpFab = new Button(context);
        jumpFab.setText("↓ Latest");
        jumpFab.setAllCaps(false);
        jumpFab.setTextSize(12);
        jumpFab.setTextColor(0xfff5f2e9);
        jumpFab.setTypeface(Typeface.DEFAULT_BOLD);
        jumpFab.setMinHeight(dp(48));
        jumpFab.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(dp(24));
        pill.setColor(0xff17634e);
        jumpFab.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), pill, null));
        jumpFab.setVisibility(GONE);
        jumpFab.setContentDescription("Jump to the latest output");
        jumpFab.setOnClickListener(v -> scrollToTail());
        FrameLayout.LayoutParams fab = new FrameLayout.LayoutParams(-2, dp(48),
                Gravity.BOTTOM | Gravity.END);
        fab.setMargins(0, 0, dp(16), dp(12));
        addView(jumpFab, fab);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true); // only the pane's own renderer below; no bridges.
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setGeolocationEnabled(false);
        ws.setMediaPlaybackRequiresUserGesture(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUseWideViewPort(false); // keep the phone width as the line-wrapping width.
        ws.setLoadWithOverviewMode(false);
        // Honour the reader's size and the system font scale; pinch-zoom stays
        // available (not locked).
        applyReaderTextZoom();
        if (Build.VERSION.SDK_INT >= 26) ws.setSafeBrowsingEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // inert surface: never navigate, not even to the request URL.
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String scheme = url.getScheme();
                if ("https".equals(scheme) && ATTACHMENT_HOST.equals(url.getHost())) {
                    // Only ids this pane just rendered as images may reach the loader.
                    AttachmentLoader loader = attachmentLoader;
                    String path = url.getPath();
                    if (loader != null && path != null) {
                        Matcher attachment = ATTACHMENT_PATH.matcher(path);
                        if (attachment.matches() && renderedAttachmentIds.contains(attachment.group(1))) {
                            WebResourceResponse response = loader.load(attachment.group(1));
                            if (response != null) return response;
                        }
                    }
                    return blocked();
                }
                if ("http".equals(scheme) || "https".equals(scheme)
                        || "file".equals(scheme) || "content".equals(scheme)) {
                    return blocked(); // every ordinary fetch stays inert.
                }
                return null;
            }

            @Override public void onPageFinished(WebView view, String url) {
                // First paint starts at the tail like a desktop terminal.
                view.post(TerminalView.this::scrollToTail);
            }
        });
        webView.setOnScrollChangeListener((v, x, y, oldX, oldY) -> scheduleTailCheck());
        webView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                webView.removeCallbacks(sizeRestore);
                resizingText = false;
            }
            return false;
        });
        webView.setBackgroundColor(Color.parseColor(BG));
    }

    /** Injects a bridge HTML capture after whitelist sanitization. Safe to call repeatedly. */
    public void loadCapture(@NonNull String html) {
        renderedAttachmentIds = Collections.emptySet(); // terminal bytes never carry attachments
        loadFragment(sanitize(html), TERM_CLASS);
    }

    /** Injects a bridge conversation payload (readable chat + attachment previews). */
    public void loadConversation(@NonNull JSONObject payload) {
        renderedAttachmentIds = collectAttachmentIds(payload);
        loadFragment(conversationHtml(payload), CONVO_CLASS);
    }

    /** Authenticated byte source for the attachment images this pane renders. */
    public void setAttachmentLoader(AttachmentLoader loader) {
        attachmentLoader = loader;
    }

    /** Lets a paged conversation return to the live page instead of its local tail. */
    public void setOnJumpToLatestListener(Runnable action) {
        jumpFab.setOnClickListener(v -> action.run());
    }

    /**
     * Renders a conversation payload into readable chat HTML. Nothing from the
     * payload is interpolated as markup, as a class name, or as a fetchable URL:
     * role classes come from a fixed set, every label/name/text is escaped, the
     * markdown renderer escapes raw HTML and sanitizes link/image URLs, and only
     * ids that pass a strict 32-hex check can become an image under the pane's own
     * attachment origin. Detail keys come from message ids rather than the page
     * offset, so live refreshes keep the reader's expansion state.
     */
    static String conversationHtml(JSONObject payload) {
        JSONArray messages = payload == null ? null : payload.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            return "<div class=\"msg context\"><div class=\"who\">Session</div>"
                    + "<div class=\"body\">No messages captured for this session.</div></div>";
        }
        StringBuilder out = new StringBuilder();
        List<JSONObject> folded = new ArrayList<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !renderable(message)) continue;
            String kind = kindOf(message);
            if (!"user".equals(kind) && !"assistant".equals(kind)) {
                folded.add(message); // consecutive tool/context rows become one Activity group
                continue;
            }
            flushActivity(out, folded);
            folded.clear();
            appendTurn(out, "msg", kind, message);
        }
        flushActivity(out, folded);
        return out.toString();
    }

    /** Fixed role class: known roles pass through, anything else is an inert `other`. */
    private static String kindOf(JSONObject message) {
        String role = message.optString("role", "");
        return CONVO_ROLES.contains(role) ? role : "other";
    }

    /** True when a message carries text or at least one addressable attachment. */
    private static boolean renderable(JSONObject message) {
        if (!message.optString("text", "").trim().isEmpty()) return true;
        JSONArray attachments = message.optJSONArray("attachments");
        if (attachments == null) return false;
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject attachment = attachments.optJSONObject(i);
            if (attachment != null && addressable(attachment)) return true;
        }
        return false;
    }

    /** One written turn (`msg`) or one row inside an Activity group (`act`). */
    private static void appendTurn(StringBuilder out, String wrapper, String kind, JSONObject message) {
        String text = message.optString("text", "");
        out.append("<div class=\"").append(wrapper).append(' ').append(kind).append("\">")
                .append("<div class=\"who\">")
                .append(who(kind, message.optString("role", ""), message.optString("name", "").trim()))
                .append("</div>");
        // An image-only send arrives with empty text: no empty bubble, just the preview.
        if (!text.trim().isEmpty()) {
            out.append("<div class=\"body\">")
                    .append(body(text, message.optString("format", "text"))).append("</div>");
        }
        appendAttachments(out, message);
        out.append("</div>");
    }

    /** Condenses a run of folded tool/context rows into one collapsed, keyed group. */
    private static void flushActivity(StringBuilder out, List<JSONObject> folded) {
        if (folded.isEmpty()) return;
        out.append("<details class=\"activity\" data-key=\"")
                .append(attr("g:" + stableKey(folded.get(0)))).append("\"><summary>")
                .append(escape(activityLabel(folded))).append("</summary>");
        for (JSONObject message : folded) appendTurn(out, "act", kindOf(message), message);
        out.append("</details>");
    }

    /** Summary wording: step count plus the first few tool names seen in the run. */
    private static String activityLabel(List<JSONObject> folded) {
        int count = folded.size();
        StringBuilder label = new StringBuilder("Activity \u00b7 ").append(count)
                .append(count == 1 ? " step" : " steps");
        List<String> names = new ArrayList<>();
        for (JSONObject message : folded) {
            String name = message.optString("name", "").trim();
            if (name.isEmpty()) continue;
            if (name.length() > 40) name = name.substring(0, 40) + "\u2026";
            if (!names.contains(name)) names.add(name);
        }
        for (int i = 0; i < names.size() && i < 3; i++) {
            label.append(i == 0 ? " \u00b7 " : ", ").append(names.get(i));
        }
        if (names.size() > 3) label.append("\u2026");
        return label.toString();
    }

    /** Refresh-stable detail key: the message id, or a content hash when it has none. */
    private static String stableKey(JSONObject message) {
        String id = message.optString("id", "").trim();
        if (!id.isEmpty()) return id;
        String text = message.optString("role", "") + "\u0000" + message.optString("text", "");
        long hash = 0xcbf29ce484222325L; // FNV-1a: deterministic and offset independent
        for (int i = 0; i < text.length(); i++) {
            hash = (hash ^ text.charAt(i)) * 0x100000001b3L;
        }
        return "h" + Long.toHexString(hash);
    }

    /** A rendered attachment: inline raster preview, or a readable name/type/size card. */
    private static void appendAttachments(StringBuilder out, JSONObject message) {
        JSONArray attachments = message.optJSONArray("attachments");
        if (attachments == null) return;
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject attachment = attachments.optJSONObject(i);
            if (attachment == null || !addressable(attachment)) continue;
            String id = attachment.optString("id", "").trim();
            String name = displayName(attachment.optString("name", ""));
            String mime = safeMime(attachment.optString("mime", ""));
            out.append("<div class=\"attach\">");
            if (RASTER_MIME.contains(mime)) {
                out.append("<img class=\"attach-img\" src=\"https://").append(ATTACHMENT_HOST)
                        .append('/').append(id).append("\" alt=\"").append(attr(name)).append("\">");
            } else {
                out.append("<div class=\"attach-file\"><span class=\"attach-name\">")
                        .append(escape(name)).append("</span><span class=\"attach-meta\">")
                        .append(escape(mime)).append(" \u00b7 ")
                        .append(escape(sizeText(Math.max(0L, attachment.optLong("size", 0L)))))
                        .append("</span></div>");
            }
            out.append("</div>");
        }
    }

    /** The pane's own gate for reachable attachments: strict lowercase 32-hex ids. */
    private static boolean addressable(JSONObject attachment) {
        return ATTACHMENT_ID.matcher(attachment.optString("id", "").trim()).matches();
    }

    /** Ids the newest conversation rendered as images — the only ids the loader may see. */
    private static Set<String> collectAttachmentIds(JSONObject payload) {
        JSONArray messages = payload == null ? null : payload.optJSONArray("messages");
        if (messages == null) return Collections.emptySet();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            JSONArray attachments = message.optJSONArray("attachments");
            if (attachments == null) continue;
            for (int j = 0; j < attachments.length(); j++) {
                JSONObject attachment = attachments.optJSONObject(j);
                if (attachment == null || !addressable(attachment)) continue;
                if (RASTER_MIME.contains(safeMime(attachment.optString("mime", "")))) {
                    ids.add(attachment.optString("id", "").trim());
                }
            }
        }
        return ids.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(ids);
    }

    /** Display filename from untrusted text: trimmed, bounded, never used raw as markup. */
    private static String displayName(String raw) {
        String name = raw.trim();
        if (name.isEmpty()) return "attachment";
        return name.length() > 120 ? name.substring(0, 120) + "\u2026" : name;
    }

    /** Normalized mime from untrusted text; anything unrecognizable reads as a plain file. */
    private static String safeMime(String raw) {
        String mime = raw.trim().toLowerCase(Locale.ROOT);
        return SAFE_MIME.matcher(mime).matches() ? mime : "application/octet-stream";
    }

    private static String sizeText(long size) {
        if (size < 1024L) return size + " B";
        if (size < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0));
    }

    /** Escapes an attribute value: entity encoding plus quotes. */
    private static String attr(String s) {
        return escape(s).replace("\"", "&quot;");
    }

    private static WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    /** Escaped role label (fixed wording for known roles) plus the optional name. */
    private static String who(String kind, String role, String name) {
        String label;
        if ("user".equals(kind)) {
            label = "You";
        } else if ("assistant".equals(kind)) {
            label = "Assistant";
        } else if ("tool".equals(kind)) {
            label = "Tool";
        } else if ("context".equals(kind)) {
            label = "Context";
        } else {
            String trimmed = role.trim();
            label = trimmed.isEmpty() ? "Note"
                    : trimmed.length() > 32 ? trimmed.substring(0, 32) + "\u2026" : trimmed;
        }
        StringBuilder out = new StringBuilder(escape(label));
        if (!name.isEmpty()) out.append("<span class=\"name\">").append(escape(name)).append("</span>");
        return out.toString();
    }

    private static String body(String text, String format) {
        if ("markdown".equals(format)) return Markdown.toHtml(text);
        // Non-markdown parts are verbatim: their line structure has to survive.
        return "<div class=\"plain\">" + escape(text) + "</div>";
    }

    private void loadFragment(String fragment, String rootClass) {
        if (!loaded) {
            loaded = true;
            lastFragment = fragment;
            lastClass = rootClass;
            String shellClass = rootClass.isEmpty() ? "" : " class=\"" + rootClass + "\"";
            webView.loadDataWithBaseURL("about:blank",
                    SHELL_HEAD + "<div id=\"t\"" + shellClass + ">" + fragment + "</div>"
                            + SHELL_SCRIPT + "</body></html>",
                    "text/html", "utf-8", null);
            return;
        }
        if (fragment.equals(lastFragment) && rootClass.equals(lastClass)) return;
        boolean tail = resizingText ? resizeAtTail : isAtTail();
        webView.removeCallbacks(sizeRestore); // new layout makes a queued ratio stale
        resizingText = false;
        if (!rootClass.equals(lastClass)) {
            lastClass = rootClass;
            webView.evaluateJavascript("tMode(" + JSONObject.quote(rootClass) + ")", null);
        }
        if (!fragment.equals(lastFragment)) {
            lastFragment = fragment;
            webView.evaluateJavascript("tSet(" + JSONObject.quote(fragment) + ")", null);
        }
        if (tail) webView.postDelayed(this::scrollToTail, TAIL_SETTLE_MS);
    }

    /** True when the reader is at the bottom edge of the capture. Lines wrap
     *  to the pane width, so horizontal position does not affect visible rows. */
    public boolean isAtTail() {
        int contentH = Math.round(webView.getContentHeight() * webView.getScale());
        if (contentH <= 0) return true; // nothing laid out yet; never flash the FAB during load.
        int tolerance = dp(TAIL_TOLERANCE_DP);
        return (long) webView.getScrollY() + webView.getHeight() >= contentH - tolerance;
    }

    /** Re-evaluates the tail state after the WebView has clamped and laid out
     *  the scroll — listener callbacks fire with pre-clamp coordinates. */
    private void scheduleTailCheck() {
        webView.removeCallbacks(tailCheck);
        webView.postDelayed(tailCheck, TAIL_SETTLE_MS);
    }

    private final Runnable tailCheck = new Runnable() {
        @Override public void run() {
            if (tailListener != null) tailListener.onTailChange(isAtTail());
        }
    };

    /** Jumps to the bottom edge of the capture. */
    public void scrollToTail() {
        webView.removeCallbacks(sizeRestore); // an explicit jump beats a queued resize restore
        resizingText = false;
        // Large but finite: Integer.MAX_VALUE is stored unclamped by WebView and
        // overflows position arithmetic (MAX + height goes negative).
        webView.scrollTo(0, 1 << 22);
        scheduleTailCheck();
    }

    /** Sets the reader's base font size in scaled pixels; the system font scale
     *  still multiplies it. Keeps the loaded capture, follows the tail for a
     *  reader who was at the tail, and keeps approximately the same reading
     *  position for one who was reading history. */
    public void setTextSizeSp(int sizeSp) {
        int clamped = Math.max(12, Math.min(24, sizeSp));
        if (clamped == textSizeSp) return;
        if (!resizingText) {
            resizeAtTail = isAtTail();
            int contentH = Math.round(webView.getContentHeight() * webView.getScale());
            int scrollRange = Math.max(0, contentH - webView.getHeight());
            sizeRatio = scrollRange > 0 ? (float) webView.getScrollY() / scrollRange : 0f;
        }
        resizingText = true;
        textSizeSp = clamped;
        applyReaderTextZoom();
        // Coalesce slider changes; keep the original tail/history intent until layout settles.
        webView.removeCallbacks(sizeRestore);
        webView.postDelayed(sizeRestore, TAIL_SETTLE_MS);
    }

    /** Text zoom from the reader's size over the 16px CSS baseline, times the OS font scale. */
    private void applyReaderTextZoom() {
        webView.getSettings().setTextZoom(Math.round(100f * (textSizeSp / 16f) * fontScale));
    }

    // Re-anchors the reading position once the resized text has reflowed.
    private final Runnable sizeRestore = new Runnable() {
        @Override public void run() {
            resizingText = false;
            if (resizeAtTail) {
                scrollToTail();
                return;
            }
            int contentH = Math.round(webView.getContentHeight() * webView.getScale());
            if (contentH <= 0) return;
            int scrollRange = Math.max(0, contentH - webView.getHeight());
            webView.scrollTo(0, Math.round(sizeRatio * scrollRange));
            scheduleTailCheck();
        }
    };

    public void setOnTailChangeListener(OnTailChangeListener listener) {
        tailListener = listener;
    }

    /** Shows or hides the built-in Jump-to-latest overlay. */
    public void setJumpToLatestVisible(boolean visible) {
        jumpFab.setVisibility(visible ? VISIBLE : GONE);
    }

    /** Lazily built CommonMark pipeline: one parser, one inert HTML renderer. */
    private static final class Markdown {
        private static final Parser PARSER = Parser.builder().build();
        private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
                .escapeHtml(true)    // raw HTML in a message stays literal text, never markup
                .sanitizeUrls(true)  // javascript:/file: URLs blanked; http(s)/mailto/data survive
                .softbreak("<br />") // keep the author's line breaks inside a paragraph
                .build();

        static String toHtml(String text) {
            return RENDERER.render(PARSER.parse(text));
        }
    }

    // ---------- sanitization ----------

    /** Keeps pre/span/br and a short inline-style allow-list; everything else is removed. */
    private static String sanitize(String html) {
        String s = COMMENT_RE.matcher(html).replaceAll("");
        s = BLOCK_RE.matcher(s).replaceAll("");
        s = LONE_TAG_RE.matcher(s).replaceAll("");
        Matcher m = TAG_RE.matcher(s);
        StringBuffer out = new StringBuffer(s.length());
        while (m.find()) {
            String tag = m.group(2).toLowerCase(Locale.ROOT);
            String rep;
            if ("br".equals(tag)) {
                rep = "<br>";
            } else if (TAG_WHITELIST.contains(tag)) {
                String style = sanitizeStyle(m.group(3));
                rep = "<" + m.group(1) + tag + (style.isEmpty() ? "" : " style=\"" + style + "\"") + ">";
            } else {
                rep = ""; // drop the tag, keep its text
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String sanitizeStyle(String attrs) {
        Matcher m = STYLE_ATTR_RE.matcher(attrs);
        if (!m.find()) return "";
        StringBuilder out = new StringBuilder();
        for (String decl : m.group(1).split(";")) {
            int colon = decl.indexOf(':');
            if (colon < 0) continue;
            String prop = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (!STYLE_PROPS.contains(prop)) continue;
            String val = decl.substring(colon + 1).trim();
            String low = val.toLowerCase(Locale.ROOT);
            if (!SAFE_VALUE_RE.matcher(val).matches()
                    || low.contains("url") || low.contains("expression") || low.contains("javascript")) continue;
            if ("color".equals(prop) || "background-color".equals(prop)) {
                val = paperColor(val, "background-color".equals(prop));
            }
            if (out.length() > 0) out.append(';');
            out.append(prop).append(':').append(val);
        }
        return out.toString();
    }

    /** Translate terminal colors into readable spot inks and pale highlight fields. */
    private static String paperColor(String value, boolean background) {
        final int color;
        try {
            color = Color.parseColor(value);
        } catch (IllegalArgumentException unsupported) {
            return background ? BG : INK;
        }
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min < 32) {
            return background ? BG : (max >= 90 && max < 210 ? "#5d6b64" : INK);
        }
        if (r >= b && g * 4 >= r * 3 && b * 4 < g * 3) {
            return background ? "#eee5cf" : "#806022";
        }
        if (r >= g && r >= b) {
            if (b * 4 >= r * 3) return background ? "#eae2ec" : "#694e76";
            return background ? "#f3e0d9" : "#a33f32";
        }
        if (g >= b) return background ? "#e2ebdf" : "#17634e";
        return background ? "#e0e8ed" : "#365e7a";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }
}
