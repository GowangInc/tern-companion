package io.github.gowanginc.terncompanion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import java.util.Arrays;
import java.util.HashSet;
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
 * Conversation captures are rendered locally as role-separated cards: roles come
 * from a fixed set, every label and text is escaped, and CommonMark markdown is
 * rendered with raw HTML escaped and URLs sanitized (tool/context parts stay
 * folded in a details block).
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
    private static final Set<String> CONVO_ROLES =
            new HashSet<>(Arrays.asList("user", "assistant", "tool", "context"));

    private static final String SHELL_HEAD = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta http-equiv=\"Content-Security-Policy\" content=\""
            + "default-src 'none'; img-src 'none'; font-src 'none'; connect-src 'none';"
            + " media-src 'none'; object-src 'none'; frame-src 'none'; worker-src 'none';"
            + " base-uri 'none'; form-action 'none';"
            + " style-src 'unsafe-inline'; script-src 'unsafe-inline'\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0,"
            + " maximum-scale=10.0, user-scalable=yes\">"
            + "<style>html,body{margin:0;padding:0;background:" + BG + ";overflow:auto}"
            + "#t{margin:0;padding:0;font-family:monospace;font-size:16px;line-height:1.5;"
            + "white-space:pre-wrap;overflow-wrap:anywhere;color:" + INK + ";}"
            + "#t pre{font:inherit;margin:0;padding:0;}"
            + "::selection{background:#d5e2d5;color:" + INK + ";}"
            // Conversation cards: prose whitespace, quiet role separation, no extra frames.
            + "#t.convo{font-family:system-ui,sans-serif;white-space:normal;"
            + "overflow-wrap:break-word;line-height:1.55;font-size:16px;}"
            + "#t.convo .msg{margin:0 0 10px;padding:6px 10px;border-radius:8px;"
            + "border-left:3px solid #c9d3cc;background:#faf8f2;}"
            + "#t.convo .who{font-size:0.78em;font-weight:bold;letter-spacing:0.06em;"
            + "text-transform:uppercase;color:#5d6b64;}"
            + "#t.convo .name{font-weight:normal;letter-spacing:0;text-transform:none;"
            + "margin-left:8px;color:#806022;overflow-wrap:anywhere;}"
            + "#t.convo .user{border-left-color:#17634e;background:#f1f5f0;}"
            + "#t.convo .assistant{border-left-color:#365e7a;background:#f3f5f7;}"
            + "#t.convo .tool,#t.convo .context,#t.convo .other{border-left-color:#c2bcae;"
            + "background:#f6f4ed;}"
            + "#t.convo details{margin:0;}"
            + "#t.convo summary{cursor:pointer;color:#5d6b64;font-size:0.78em;min-height:48px;"
            + "font-weight:bold;letter-spacing:0.06em;text-transform:uppercase;}"
            + "#t.convo summary:focus-visible{outline:2px solid #17634e;}"
            + "#t.convo .body{margin-top:4px;}"
            + "#t.convo .msg.tool .body,#t.convo .msg.context .body,#t.convo .msg.other .body"
            + "{font-family:monospace;font-size:0.85em;white-space:pre-wrap;"
            + "overflow-wrap:anywhere;max-height:14em;overflow:auto;background:#efece2;"
            + "padding:6px 8px;border-radius:6px;}"
            + "#t.convo h1,#t.convo h2,#t.convo h3,#t.convo h4,#t.convo h5,#t.convo h6"
            + "{margin:0.7em 0 0.3em;line-height:1.25;}"
            + "#t.convo h1{font-size:1.35em;}#t.convo h2{font-size:1.2em;}"
            + "#t.convo h3{font-size:1.08em;}#t.convo h4,#t.convo h5,#t.convo h6{font-size:1em;}"
            + "#t.convo p{margin:0.45em 0;}"
            + "#t.convo ul,#t.convo ol{margin:0.45em 0;padding-left:1.5em;}"
            + "#t.convo li{margin:0.15em 0;}"
            + "#t.convo blockquote{margin:0.45em 0;padding-left:0.8em;"
            + "border-left:2px solid #d8d2c4;color:#5d6b64;}"
            + "#t.convo .plain{white-space:pre-wrap;overflow-wrap:anywhere;}"
            + "#t.convo code{font-family:monospace;font-size:0.92em;background:#efece2;"
            + "border-radius:3px;padding:0 3px;}"
            + "#t.convo pre{font-family:monospace;font-size:0.85em;line-height:1.45;"
            + "white-space:pre;overflow-x:auto;background:#efece2;padding:6px 8px;"
            + "border-radius:6px;}"
            + "#t.convo pre code{background:none;padding:0;font-size:1em;}"
            + "#t.convo hr{border:0;border-top:1px solid #d8d2c4;margin:0.7em 0;}"
            + "#t.convo table{border-collapse:collapse;font-size:0.9em;}"
            + "#t.convo th,#t.convo td{border:1px solid #d8d2c4;padding:2px 6px;}"
            + "#t.convo a{color:#365e7a;}"
            + "#t.convo img{max-width:100%;height:auto;}"
            + "</style></head><body>";
    private static final String SHELL_SCRIPT =
            "<script>function tSet(h){const t=document.getElementById('t');"
            + "const open=new Set(Array.from(t.querySelectorAll('details[open]')).map(d=>d.dataset.key));"
            + "t.innerHTML=h;t.querySelectorAll('details').forEach(d=>{d.open=open.has(d.dataset.key);});}"
            + "function tMode(c){document.getElementById('t').className=c;}</script>";

    public interface OnTailChangeListener {
        void onTailChange(boolean atTail);
    }

    private final WebView webView;
    private final Button jumpFab;
    private final float density;
    private final float fontScale;
    private OnTailChangeListener tailListener;
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
        jumpFab.setContentDescription("Jump to the latest terminal output");
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
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)
                        || "file".equals(scheme) || "content".equals(scheme)) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
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
        loadFragment(sanitize(html), "");
    }

    /** Injects a bridge conversation payload (quiet role-separated cards). */
    public void loadConversation(@NonNull JSONObject payload) {
        loadFragment(conversationHtml(payload), CONVO_CLASS);
    }

    /** Lets a paged conversation return to the live page instead of its local tail. */
    public void setOnJumpToLatestListener(Runnable action) {
        jumpFab.setOnClickListener(v -> action.run());
    }

    /**
     * Renders a conversation payload into card HTML. Nothing from the payload is
     * interpolated as markup, as a class name, or as a fetchable URL: role classes
     * come from a fixed set, every label/name/text is escaped, and the markdown
     * renderer escapes raw HTML and sanitizes link/image URLs.
     */
    static String conversationHtml(JSONObject payload) {
        JSONArray messages = payload == null ? null : payload.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            return "<div class=\"msg context\"><span class=\"who\">Session</span>"
                    + "<div class=\"body\">No messages captured for this session.</div></div>";
        }
        StringBuilder out = new StringBuilder();
        int start = payload.optInt("start");
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role", "");
            String name = message.optString("name", "").trim();
            String text = message.optString("text", "");
            String kind = CONVO_ROLES.contains(role) ? role : "other";
            boolean folded = !"user".equals(kind) && !"assistant".equals(kind);
            out.append("<div class=\"msg ").append(kind).append("\">");
            if (folded) {
                // Tool/context parts keep every character but stay folded until tapped.
                String key = escape(message.optString("id") + ":" + kind + ":" + (start + i))
                        .replace("\"", "&quot;");
                out.append("<details data-key=\"").append(key).append("\"><summary><span class=\"who\">")
                        .append(who(kind, role, name))
                        .append("</span></summary><pre class=\"body\">").append(escape(text))
                        .append("</pre></details>");
            } else {
                out.append("<div class=\"who\">").append(who(kind, role, name)).append("</div>")
                        .append("<div class=\"body\">")
                        .append(body(text, message.optString("format", "text"))).append("</div>");
            }
            out.append("</div>");
        }
        return out.toString();
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
