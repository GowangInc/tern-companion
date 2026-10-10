package io.github.gowanginc.terncompanion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

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
 * Updates replace the DOM in place (never reload the page). The reader's
 * scroll position is preserved while browsing history; new output only pulls
 * the view to the tail when the reader was already at the tail.
 */
public final class TerminalView extends FrameLayout {
    private static final String BG = "#140f18";
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

    private static final String SHELL_HEAD = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta http-equiv=\"Content-Security-Policy\" content=\""
            + "default-src 'none'; img-src 'none'; font-src 'none'; connect-src 'none';"
            + " media-src 'none'; object-src 'none'; frame-src 'none'; worker-src 'none';"
            + " base-uri 'none'; form-action 'none';"
            + " style-src 'unsafe-inline'; script-src 'unsafe-inline'\">"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0,"
            + " maximum-scale=10.0, user-scalable=yes\">"
            + "<style>html,body{margin:0;padding:0;background:" + BG + ";overflow:auto}"
            + "pre{font-family:monospace;font-size:14px;line-height:1.35;white-space:pre;"
            + "margin:0;padding:8px 10px;color:#f0e8f4;}</style></head><body>";
    private static final String SHELL_SCRIPT =
            "<script>function tSet(h){document.getElementById('t').innerHTML=h;}</script>";

    public interface OnTailChangeListener {
        void onTailChange(boolean atTail);
    }

    private final WebView webView;
    private final Button jumpFab;
    private final float density;
    private OnTailChangeListener tailListener;
    private boolean loaded;
    private boolean showsPlain;
    private String lastFragment = "";

    public TerminalView(Context context) {
        this(context, null);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        webView = new WebView(context);
        addView(webView, new FrameLayout.LayoutParams(-1, -1));

        jumpFab = new Button(context);
        jumpFab.setText("↓ Latest");
        jumpFab.setAllCaps(false);
        jumpFab.setTextSize(12);
        jumpFab.setTextColor(0xfff5f2e9);
        jumpFab.setTypeface(Typeface.DEFAULT_BOLD);
        jumpFab.setMinHeight(dp(48));
        jumpFab.setPadding(dp(14), 0, dp(14), 0);
        jumpFab.setBackgroundColor(0xff17634e);
        jumpFab.setVisibility(GONE);
        jumpFab.setContentDescription("Jump to the latest terminal output");
        jumpFab.setOnClickListener(v -> scrollToTail());
        FrameLayout.LayoutParams fab = new FrameLayout.LayoutParams(-2, dp(48),
                Gravity.BOTTOM | Gravity.END);
        fab.setMargins(0, 0, dp(12), dp(12));
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
        ws.setUseWideViewPort(false); // natural column width; horizontal touch scroll.
        ws.setLoadWithOverviewMode(false);
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
        webView.setBackgroundColor(Color.parseColor(BG));
    }

    /** Injects a bridge HTML capture after whitelist sanitization. Safe to call repeatedly. */
    public void loadCapture(@NonNull String html) {
        showsPlain = false;
        loadFragment(sanitize(html));
    }

    /** Shows a plain-text capture (older bridge) escaped, in the same pane. */
    public void loadPlainText(@NonNull String text) {
        showsPlain = true;
        loadFragment(escape(text));
    }

    /** True after {@link #loadPlainText} until the next {@link #loadCapture}. */
    public boolean showsPlainText() {
        return showsPlain;
    }

    private void loadFragment(String fragment) {
        if (!loaded) {
            lastFragment = fragment;
            loaded = true;
            webView.loadDataWithBaseURL("about:blank",
                    SHELL_HEAD + "<pre id=\"t\">" + fragment + "</pre>" + SHELL_SCRIPT + "</body></html>",
                    "text/html", "utf-8", null);
            return;
        }
        if (fragment.equals(lastFragment)) return; // refresh only when content changed
        boolean tail = isAtTail();
        lastFragment = fragment;
        webView.evaluateJavascript("tSet(" + JSONObject.quote(fragment) + ")", null);
        if (tail) webView.postDelayed(this::scrollToTail, TAIL_SETTLE_MS);
    }

    /** True when the reader is at the bottom edge of the capture. Horizontal
     *  position does not affect which rows are visible (nowrap pre layout). */
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
            android.util.Log.d("TernCompanion", "tailcheck sy=" + webView.getScrollY()
                    + " ch=" + webView.getContentHeight() + " h=" + webView.getHeight()
                    + " at=" + isAtTail());
            if (tailListener != null) tailListener.onTailChange(isAtTail());
        }
    };

    /** Jumps to the bottom-right tail of the capture. */
    public void scrollToTail() {
        // Large but finite: Integer.MAX_VALUE is stored unclamped by WebView and
        // overflows position arithmetic (MAX + height goes negative).
        webView.scrollTo(1 << 22, 1 << 22);
        scheduleTailCheck();
    }

    public void setOnTailChangeListener(OnTailChangeListener listener) {
        tailListener = listener;
    }

    /** Shows or hides the built-in Jump-to-latest overlay. */
    public void setJumpToLatestVisible(boolean visible) {
        jumpFab.setVisibility(visible ? VISIBLE : GONE);
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
            if (out.length() > 0) out.append(';');
            out.append(prop).append(':').append(val);
        }
        return out.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private int dp(int v) {
        return (int) (v * density + 0.5f);
    }
}
