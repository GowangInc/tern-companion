package io.github.gowanginc.terncompanion;

/** Run with javac + java; no Android runtime or test dependencies needed. */
public final class UpdateCheck {
    public static void main(String[] args) {
        order("v1.10.0", "1.9.9", 1);
        order("v1.2.0", "1.2", 0);
        order("1.02.000", "v1.2", 0);
        order("1.2.0.1", "1.2", 1);
        order("1.9", "2.0", -1);
        order("999999999999999999999999999999.0", "999999999999999999999999999998.9", 1);
        for (String invalid : new String[] {null, "", "v1", "1", "V1.2", " 1.2", "1.2 ",
                "1.2-beta", "1.2+build", "1..2", "1.2\n", "-1.2", "1.2.٣"}) {
            reject(() -> UpdatePolicy.compareVersions(invalid, "1.0"));
            reject(() -> UpdatePolicy.compareVersions("1.0", invalid));
        }
        String apk = "https://github.com/GowangInc/tern-companion/releases/download/v2.0/Tern-Companion.apk";
        String selected = UpdatePolicy.selectApkAsset(
                new String[] {"source.zip", "Tern-Companion.apk"}, new String[] {"https://example.com/source.zip", apk});
        if (!apk.equals(selected)) throw new AssertionError("Official APK was not selected");
        reject(() -> UpdatePolicy.selectApkAsset(new String[] {"other.apk"}, new String[] {apk}));
        reject(() -> UpdatePolicy.selectApkAsset(new String[] {"tern-companion.apk"}, new String[] {apk}));
        reject(() -> UpdatePolicy.selectApkAsset(new String[] {"Tern-Companion.apk", "Tern-Companion.apk"}, new String[] {apk, apk}));
        reject(() -> UpdatePolicy.selectApkAsset(new String[] {"Tern-Companion.apk"}, new String[] {"http://github.com/file.apk"}));
        reject(() -> UpdatePolicy.selectApkAsset(null, new String[] {apk}));
        reject(() -> UpdatePolicy.selectApkAsset(new String[] {"Tern-Companion.apk"}, new String[0]));
        for (String host : new String[] {"github.com", "api.github.com", "objects.githubusercontent.com",
                "release-assets.githubusercontent.com", "github-releases.githubusercontent.com"}) {
            allowed("https://" + host + "/asset.apk?temporary=synthetic", true);
            allowed("https://" + host + ":443/asset.apk", true);
        }
        allowed("https://GITHUB.COM/file.apk", true);
        for (String invalid : new String[] {null, "", "http://github.com/file.apk", "ftp://github.com/file.apk",
                "https://github.com:444/file.apk", "https://user@github.com/file.apk", "https://github.com.evil.example/file.apk",
                "https://evil.github.com/file.apk", "https://githubusercontent.com/file.apk", "https://github.com./file.apk",
                "https://github.com/file.apk#fragment", "https://github.com\\@example.com/file.apk", "//github.com/file.apk",
                "https://github.com/file.apk\n"}) allowed(invalid, false);
        System.out.println("Updater release-selection and redirect trust checks passed.");
    }

    private static void order(String available, String installed, int expected) {
        if (UpdatePolicy.compareVersions(available, installed) != expected) {
            throw new AssertionError("Version ordering failed");
        }
    }

    private static void allowed(String url, boolean expected) {
        if (UpdatePolicy.isAllowedDownloadUrl(url) != expected) throw new AssertionError("URL policy failed");
    }

    private static void reject(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Invalid update input accepted");
        } catch (IllegalArgumentException expected) {}
    }
}
