package io.github.gowanginc.terncompanion;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Looper;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Android certificate objects and optional platform APK archive checks. */
public final class AndroidCheck {
    public static void main(String[] args) throws Exception {
        Method signers = AppUpdater.class.getDeclaredMethod("sameSigners", Signature[].class, Signature[].class);
        signers.setAccessible(true);
        Signature a = new Signature(new byte[] {1, 2, 3});
        Signature b = new Signature(new byte[] {4, 5, 6});
        expect(signers, new Signature[] {a}, new Signature[] {new Signature(new byte[] {1, 2, 3})}, true);
        expect(signers, new Signature[] {a, b}, new Signature[] {b, a}, true);
        expect(signers, new Signature[] {a}, new Signature[] {b}, false);
        expect(signers, new Signature[] {a}, new Signature[] {a, b}, false);
        expect(signers, new Signature[] {a, a}, new Signature[] {a, b}, false);
        expect(signers, new Signature[] {a, b}, new Signature[] {a, a}, false);
        expect(signers, new Signature[0], new Signature[0], false);
        expect(signers, null, new Signature[] {a}, false);
        expect(signers, new Signature[] {null}, new Signature[] {a}, false);
        System.out.println("Android current-signer compatibility checks passed.");
        checkTerminalTheme();
        checkTerminalConversation();
        if (args.length == 0) {
            System.out.println("Platform APK archive guards NOT RUN; supply newer, wrong-signer and wrong-package APK paths.");
            return;
        }
        if (args.length != 3) throw new IllegalArgumentException("Expected three test APK paths");
        if (Looper.myLooper() == null) Looper.prepare();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object main = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(main);
        Activity owner = new Activity() {
            @Override public String getPackageName() { return "io.github.gowanginc.terncompanion"; }
            @Override public PackageManager getPackageManager() { return context.getPackageManager(); }
        };
        Class<?> releaseType = Class.forName("io.github.gowanginc.terncompanion.AppUpdater$Release");
        Constructor<?> constructor = releaseType.getDeclaredConstructor(String.class, String.class, long.class);
        constructor.setAccessible(true);
        Method validate = AppUpdater.class.getDeclaredMethod("validateApk", Activity.class, File.class, releaseType);
        validate.setAccessible(true);
        File good = new File(args[0]);
        String goodVersion = context.getPackageManager().getPackageArchiveInfo(good.getPath(), 0).versionName;
        Object release = constructor.newInstance("v" + goodVersion, "https://github.com/GowangInc/tern-companion/", good.length());
        validate.invoke(null, owner, good, release);
        File installed = new File(context.getPackageManager().getApplicationInfo(owner.getPackageName(), 0).sourceDir);
        reject(validate, owner, installed, constructor, "v" + goodVersion, "not newer");
        reject(validate, owner, new File(args[1]), constructor, "v" + goodVersion, "signing certificate");
        reject(validate, owner, new File(args[2]), constructor, "v" + goodVersion, "valid APK for this app");
        Object truncated = constructor.newInstance("v" + goodVersion, "https://github.com/", good.length() + 1);
        try {
            validate.invoke(null, owner, good, truncated);
            throw new AssertionError("Incomplete APK accepted");
        } catch (InvocationTargetException expected) {
            if (!expected.getCause().getMessage().contains("incomplete")) throw expected;
        }
        System.out.println("Platform APK package, signer, version and size guards passed.");
    }

    private static void checkTerminalTheme() throws Exception {
        Method sanitize = TerminalView.class.getDeclaredMethod("sanitize", String.class);
        sanitize.setAccessible(true);
        String[] colors = {"#f0e8f4", "#140f18", "#9fb4bd", "#8eb897",
                "#d96c75", "#7c9cf0", "#ffff00", "#ff00ff", "white", "rgb(255,255,255)"};
        for (String source : colors) {
            String html = (String) sanitize.invoke(null, "<span style=\"color:" + source
                    + ";background-color:" + source + "\">readable</span>");
            java.util.regex.Matcher colorsOut = java.util.regex.Pattern.compile(
                    "(?:^|[\";])(?:color|background-color):(#[0-9a-f]{6})").matcher(html);
            if (!colorsOut.find()) throw new AssertionError("Terminal text color missing");
            int ink = Color.parseColor(colorsOut.group(1));
            if (!colorsOut.find()) throw new AssertionError("Terminal highlight missing");
            int paper = Color.parseColor(colorsOut.group(1));
            double paperLuminance = Color.luminance(paper);
            double inkLuminance = Color.luminance(ink);
            if (paperLuminance < 0.75
                    || (paperLuminance + 0.05) / (inkLuminance + 0.05) < 4.5
                    || (Color.luminance(Color.parseColor("#f5f2e9")) + 0.05)
                            / (inkLuminance + 0.05) < 4.5) {
                throw new AssertionError("Unreadable paper terminal color: " + source);
            }
        }
        String red = (String) sanitize.invoke(null, "<span style=\"color:red\">status</span>");
        String green = (String) sanitize.invoke(null, "<span style=\"color:green\">status</span>");
        if (red.equals(green)) throw new AssertionError("Terminal status colors collapsed");
        System.out.println("Paper terminal text/highlight contrast checks passed.");
    }

    /** Conversation captures: escaping, markdown rendering, fixed role classes. */
    private static void checkTerminalConversation() throws Exception {
        Method render = TerminalView.class.getDeclaredMethod("conversationHtml", JSONObject.class);
        render.setAccessible(true);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("format", "markdown")
                .put("text", "# Heading\n\n<body onload=x>raw</body>\n<files>a/b.txt</files>\n\n"
                        + "```java\nint a = 1;\n```\n"));
        messages.put(new JSONObject().put("role", "assistant").put("format", "markdown")
                .put("text", "Use **bold**, `code`, [bad](javascript:alert(1)) and "
                        + "[ok](https://example.com/x)\n\n![diagram](https://example.com/p.png)\n"));
        messages.put(new JSONObject().put("role", "tool").put("name", "read <x>").put("format", "text")
                .put("id", "x\" onclick=\"alert(1)").put("text", "line one\nline two <b>bold</b>"));
        messages.put(new JSONObject().put("role", "weird<script>").put("format", "text")
                .put("text", "unknown role body"));
        String html = (String) render.invoke(null, new JSONObject().put("messages", messages));

        for (String forbidden : new String[] {"<script", "<body", "<files>", "javascript:", "onload=x>", "\" onclick="}) {
            if (html.contains(forbidden)) throw new AssertionError("Conversation markup leaked " + forbidden);
        }
        for (String required : new String[] {"&lt;body onload=x&gt;", "&lt;files&gt;a/b.txt&lt;/files&gt;",
                "<h1", "<strong>bold</strong>", "<code>code</code>", "int a = 1;",
                "href=\"https://example.com/x\"", "alt=\"diagram\"",
                "line two &lt;b&gt;bold&lt;/b&gt;", "weird&lt;script&gt;"}) {
            if (!html.contains(required)) throw new AssertionError("Conversation markup missing " + required);
        }
        if (html.contains("<details open")) throw new AssertionError("Tool card not collapsed");
        if (html.contains("# Heading")) throw new AssertionError("Markdown left as source text");
        java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(
                "msg", "user", "assistant", "tool", "context", "other", "who", "name", "body", "plain"));
        java.util.regex.Matcher classes =
                java.util.regex.Pattern.compile("class=\"([^\"]*)\"").matcher(html);
        while (classes.find()) {
            for (String token : classes.group(1).split("\\s+")) {
                if (token.isEmpty() || allowed.contains(token)
                        || token.matches("language-[A-Za-z0-9+#._-]+")) continue;
                throw new AssertionError("Payload text reached a class attribute: " + token);
            }
        }
        System.out.println("Conversation card escaping, markdown and role-class checks passed.");
    }

    private static void expect(Method method, Signature[] a, Signature[] b, boolean expected) throws Exception {
        boolean actual = (Boolean) method.invoke(null, a, b);
        if (actual != expected) throw new AssertionError("Current signer set comparison failed");
    }

    private static void reject(Method validate, Activity owner, File file, Constructor<?> constructor,
                               String tag, String reason) throws Exception {
        Object release = constructor.newInstance(tag, "https://github.com/", file.length());
        try {
            validate.invoke(null, owner, file, release);
            throw new AssertionError("Unsafe APK accepted");
        } catch (InvocationTargetException expected) {
            if (!expected.getCause().getMessage().contains(reason)) throw expected;
        }
    }
}
