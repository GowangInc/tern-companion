package io.github.gowanginc.terncompanion;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Pure Java release selection rules shared by the updater and its consumer check. */
public final class UpdatePolicy {
    public static final int MAX_REDIRECTS = 5;
    public static final long MAX_APK_BYTES = 150L * 1024 * 1024;
    public static final String APK_NAME = "Tern-Companion.apk";

    private UpdatePolicy() {}

    public static int compareVersions(String available, String installed) {
        String[] a = components(available);
        String[] b = components(installed);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String left = i < a.length ? normalize(a[i]) : "0";
            String right = i < b.length ? normalize(b[i]) : "0";
            int order = Integer.compare(left.length(), right.length());
            if (order == 0) order = left.compareTo(right);
            if (order != 0) return Integer.signum(order);
        }
        return 0;
    }

    private static String[] components(String version) {
        if (version == null || version.length() > 256
                || !version.matches("v?[0-9]+(?:\\.[0-9]+)+")) {
            throw new IllegalArgumentException("A stable numeric version tag is required.");
        }
        return (version.startsWith("v") ? version.substring(1) : version).split("\\.");
    }

    private static String normalize(String component) {
        int start = 0;
        while (start < component.length() - 1 && component.charAt(start) == '0') start++;
        return component.substring(start);
    }

    public static boolean isAllowedDownloadUrl(String value) {
        if (value == null || value.length() > 16384) return false;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getHost() == null) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return host.equals("github.com") || host.equals("api.github.com")
                    || host.equals("objects.githubusercontent.com")
                    || host.equals("release-assets.githubusercontent.com")
                    || host.equals("github-releases.githubusercontent.com");
        } catch (URISyntaxException e) {
            return false;
        }
    }

    public static String selectApkAsset(String[] names, String[] urls) {
        if (names == null || urls == null || names.length != urls.length) {
            throw new IllegalArgumentException("Release assets are malformed.");
        }
        String selected = null;
        for (int i = 0; i < names.length; i++) {
            if (!APK_NAME.equals(names[i])) continue;
            if (selected != null) throw new IllegalArgumentException("Release contains duplicate APK assets.");
            if (!isAllowedDownloadUrl(urls[i])) {
                throw new IllegalArgumentException("Release APK URL is not a trusted HTTPS address.");
            }
            selected = urls[i];
        }
        if (selected == null) throw new IllegalArgumentException("Release is missing Tern-Companion.apk.");
        return selected;
    }
}
