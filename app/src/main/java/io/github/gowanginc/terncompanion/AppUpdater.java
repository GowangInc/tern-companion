package io.github.gowanginc.terncompanion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Button;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

/** Anonymous public-release updates. Never reads bridge settings or credentials. */
public final class AppUpdater {
    private static final String LATEST =
            "https://api.github.com/repos/GowangInc/tern-companion/releases/latest";
    private static final String PACKAGE = "io.github.gowanginc.terncompanion";
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static WeakReference<Session> current = new WeakReference<>(null);

    private AppUpdater() {}

    /** Call on the activity's UI thread with its existing background executor. */
    public static void show(Activity owner, Executor io) {
        Session existing = current.get();
        if (existing != null && existing.dialog.isShowing()) return;
        if (owner.isFinishing() || owner.isDestroyed()) return;
        Session session = new Session(owner, io);
        current = new WeakReference<>(session);
        session.open();
    }

    private static final class Release {
        final String tag;
        final String url;
        final long size;
        Release(String tag, String url, long size) {
            this.tag = tag;
            this.url = url;
            this.size = size;
        }
    }

    private static final class UpdateFailure extends Exception {
        UpdateFailure(String message) { super(message); }
    }

    private static final class Session {
        final Activity owner;
        final Executor io;
        final AlertDialog dialog;
        Release release;
        File apk;
        boolean busy;

        Session(Activity owner, Executor io) {
            this.owner = owner;
            this.io = io;
            dialog = new AlertDialog.Builder(owner).setTitle("App updates")
                    .setMessage("Checking the latest public GitHub release…")
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Check again", null).create();
        }

        void open() {
            dialog.show();
            check();
        }

        void state(String message, String label, Runnable action) {
            dialog.setMessage(message);
            Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            button.setText(label);
            button.setEnabled(action != null && !busy);
            button.setOnClickListener(v -> { if (!busy && action != null) action.run(); });
        }

        void post(Runnable action) {
            owner.runOnUiThread(() -> {
                if (!owner.isDestroyed() && !owner.isFinishing() && dialog.isShowing()) action.run();
            });
        }

        void work(String message, String failure, RunnableWithFailure job) {
            busy = true;
            state(message, "Please wait", null);
            try {
                io.execute(() -> {
                    try {
                        job.run();
                    } catch (Exception e) {
                        // Never display server bodies, exception messages or signed redirect URLs.
                        String safe = e instanceof UpdateFailure ? e.getMessage() : failure;
                        post(() -> {
                            busy = false;
                            state(safe + "\n\nYour saved PCs and drafts have not been changed.",
                                    apk == null ? "Check again" : "Retry install",
                                    apk == null ? this::check : this::install);
                        });
                    }
                });
            } catch (RuntimeException e) {
                busy = false;
                state("The background worker is unavailable. Close and reopen App updates.", "Check again", this::check);
            }
        }

        void check() {
            apk = null;
            release = null;
            work("Checking the latest public GitHub release…",
                    "Couldn’t check GitHub. Check your internet connection and try again.", () -> {
                PackageInfo installed = installed(owner);
                Release latest = readRelease();
                int order;
                try {
                    order = UpdatePolicy.compareVersions(latest.tag, installed.versionName);
                } catch (IllegalArgumentException e) {
                    throw new UpdateFailure("The installed version or public release tag is not a stable numeric version. Use the official stable release.");
                }
                if (order <= 0) {
                    post(() -> {
                        busy = false;
                        state("You’re up to date.\nInstalled: " + installed.versionName
                                + "\nLatest stable release: " + latest.tag, "Check again", this::check);
                    });
                    return;
                }
                File cached = new File(updateDirectory(owner), UpdatePolicy.APK_NAME);
                boolean ready = false;
                if (cached.isFile() && cached.length() == latest.size) {
                    try {
                        validateApk(owner, cached, latest);
                        ready = true;
                    } catch (Exception ignored) {
                        // A stale or invalid cache is not installable; an explicit download replaces it.
                    }
                }
                boolean cachedReady = ready;
                post(() -> {
                    busy = false;
                    release = latest;
                    apk = cachedReady ? cached : null;
                    state("Update available: " + latest.tag + "\nInstalled: " + installed.versionName
                                    + "\nDownload: " + ((latest.size + 1048575) / 1048576)
                                    + " MiB\n\nAndroid will ask you to confirm installation. Your saved settings remain in place.",
                            cachedReady ? "Install update" : "Download update",
                            cachedReady ? this::install : this::download);
                });
            });
        }

        void download() {
            Release selected = release;
            if (selected == null) { check(); return; }
            work("Downloading " + selected.tag + "…",
                    "Couldn’t download the APK. Check your connection and try again.", () -> {
                File directory = updateDirectory(owner);
                File partial = File.createTempFile("download-", ".part", directory);
                try {
                    downloadApk(selected, partial);
                    validateApk(owner, partial, selected);
                    File complete = new File(directory, UpdatePolicy.APK_NAME);
                    if (!partial.renameTo(complete)) {
                        throw new UpdateFailure("Couldn’t save the verified update. Check available storage and try again.");
                    }
                    post(() -> {
                        busy = false;
                        apk = complete;
                        state("Download verified: " + selected.tag
                                        + "\n\nReady to install. Android will ask for confirmation; no uninstall is needed.",
                                "Install update", this::install);
                    });
                } finally {
                    if (partial.exists()) partial.delete();
                }
            });
        }

        void install() {
            File candidate = apk;
            Release selected = release;
            if (candidate == null || selected == null) { check(); return; }
            work("Verifying the APK before installation…",
                    "Couldn’t validate the cached APK. Close App updates, then download it again.", () -> {
                validateApk(owner, candidate, selected);
                post(() -> {
                    busy = false;
                    if (!owner.getPackageManager().canRequestPackageInstalls()) {
                        state("Android requires permission to install updates from Tern Companion. Open Settings, enable ‘Allow from this source’, then return here and tap Resume install.",
                                "Open Settings", () -> {
                            try {
                                owner.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + owner.getPackageName())));
                                state("After allowing this source in Android Settings, return here and tap Resume install.",
                                        "Resume install", this::install);
                            } catch (RuntimeException e) {
                                state("Android could not open this setting. Enable ‘Install unknown apps’ for Tern Companion in system Settings, then return here.",
                                        "Resume install", this::install);
                            }
                        });
                        return;
                    }
                    try {
                        Uri uri = FileProvider.getUriForFile(owner, owner.getPackageName() + ".updates", candidate);
                        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.setClipData(ClipData.newRawUri("Tern Companion update", uri));
                        owner.startActivity(intent);
                        state("Android’s installer is open. Confirm the update there. If you cancel, you can try again here.",
                                "Resume install", this::install);
                    } catch (RuntimeException e) {
                        state("Android could not open the installer. Check that a package installer is enabled, then try again.",
                                "Retry install", this::install);
                    }
                });
            });
        }
    }

    private interface RunnableWithFailure { void run() throws Exception; }

    private static File updateDirectory(Activity owner) throws UpdateFailure {
        File directory = new File(owner.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new UpdateFailure("Couldn’t create update storage. Free some space and try again.");
        }
        return directory;
    }

    private static Release readRelease() throws Exception {
        HttpURLConnection connection = connect(LATEST, true);
        try {
            long declared = connection.getContentLengthLong();
            if (declared > MAX_METADATA_BYTES) throw new UpdateFailure("GitHub returned oversized release information.");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (body.size() + count > MAX_METADATA_BYTES) {
                        throw new UpdateFailure("GitHub returned oversized release information.");
                    }
                    body.write(buffer, 0, count);
                }
            }
            if (declared >= 0 && body.size() != declared) throw new UpdateFailure("GitHub’s release information was truncated. Try again.");
            try {
                JSONTokener parser = new JSONTokener(new String(body.toByteArray(), StandardCharsets.UTF_8));
                Object parsed = parser.nextValue();
                if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) {
                    throw new UpdateFailure("GitHub returned malformed release information. Try again later.");
                }
                JSONObject json = (JSONObject) parsed;
                if (!Boolean.FALSE.equals(json.get("draft")) || !Boolean.FALSE.equals(json.get("prerelease"))) {
                    throw new UpdateFailure("The latest release is not a published stable release.");
                }
                Object tagValue = json.get("tag_name");
                if (!(tagValue instanceof String)) throw new UpdateFailure("GitHub returned an invalid version tag.");
                String tag = (String) tagValue;
                UpdatePolicy.compareVersions(tag, "0.0");
                JSONArray assets = json.getJSONArray("assets");
                String[] names = new String[assets.length()];
                String[] urls = new String[assets.length()];
                long size = -1;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    Object name = asset.get("name");
                    if (!(name instanceof String)) throw new UpdateFailure("GitHub returned malformed APK asset information.");
                    names[i] = (String) name;
                    if (UpdatePolicy.APK_NAME.equals(names[i])) {
                        Object url = asset.get("browser_download_url");
                        Object bytes = asset.get("size");
                        if (!(url instanceof String) || !(bytes instanceof Integer || bytes instanceof Long)) {
                            throw new UpdateFailure("GitHub returned malformed APK asset information.");
                        }
                        urls[i] = (String) url;
                        size = ((Number) bytes).longValue();
                    }
                }
                String url = UpdatePolicy.selectApkAsset(names, urls);
                if (size <= 0 || size > UpdatePolicy.MAX_APK_BYTES) {
                    throw new UpdateFailure("The release APK size is invalid or exceeds the 150 MiB safety limit.");
                }
                return new Release(tag, url, size);
            } catch (IllegalArgumentException e) {
                throw new UpdateFailure("The stable release tag or official Tern-Companion.apk asset is invalid, missing or duplicated. Check again later.");
            } catch (org.json.JSONException e) {
                throw new UpdateFailure("GitHub returned malformed release information. Try again later.");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection connect(String address, boolean metadata) throws Exception {
        String next = address;
        for (int redirects = 0; redirects <= UpdatePolicy.MAX_REDIRECTS; redirects++) {
            if (!UpdatePolicy.isAllowedDownloadUrl(next)) {
                throw new UpdateFailure("The update download redirected outside trusted GitHub HTTPS hosts.");
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(next).openConnection();
            boolean keep = false;
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "Tern-Companion-Updater");
                connection.setRequestProperty("Accept", metadata ? "application/vnd.github+json" : "application/octet-stream");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                if (code == 200) {
                    String encoding = connection.getContentEncoding();
                    if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
                        throw new UpdateFailure("GitHub returned an unsupported download encoding.");
                    }
                    keep = true;
                    return connection;
                }
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || redirects == UpdatePolicy.MAX_REDIRECTS) {
                        throw new UpdateFailure("The update download has missing or too many redirects.");
                    }
                    try {
                        next = new URI(next).resolve(new URI(location)).toString();
                    } catch (Exception e) {
                        throw new UpdateFailure("GitHub returned an invalid download redirect.");
                    }
                    continue;
                }
                if (metadata && code == 404) throw new UpdateFailure("No public stable release is published yet. Check again later.");
                if (code == 403 || code == 429) throw new UpdateFailure("GitHub temporarily refused the request or rate-limited it. Try again later.");
                throw new UpdateFailure("GitHub could not serve the update (HTTP " + code + "). Try again later.");
            } finally {
                if (!keep) connection.disconnect();
            }
        }
        throw new UpdateFailure("The update download has too many redirects.");
    }

    private static void downloadApk(Release release, File destination) throws Exception {
        HttpURLConnection connection = connect(release.url, false);
        try {
            long declared = connection.getContentLengthLong();
            if (declared > UpdatePolicy.MAX_APK_BYTES || (declared >= 0 && declared != release.size)) {
                throw new UpdateFailure("The APK download size does not match the release. Check again later.");
            }
            long total = 0;
            long deadline = android.os.SystemClock.elapsedRealtime() + 5 * 60 * 1000;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > release.size || total > UpdatePolicy.MAX_APK_BYTES) {
                        throw new UpdateFailure("The APK download exceeds its declared size. It was not saved.");
                    }
                    if (android.os.SystemClock.elapsedRealtime() > deadline) {
                        throw new UpdateFailure("The APK download timed out. Try again on a faster connection.");
                    }
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (total != release.size) throw new UpdateFailure("The APK download was truncated. Download it again.");
        } finally {
            connection.disconnect();
        }
    }

    @SuppressWarnings("deprecation")
    private static PackageInfo installed(Activity owner) throws Exception {
        if (!PACKAGE.equals(owner.getPackageName())) {
            throw new UpdateFailure("This updater only supports the neutral public Tern Companion app. Install the public app separately first.");
        }
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        return owner.getPackageManager().getPackageInfo(owner.getPackageName(), flags);
    }

    @SuppressWarnings("deprecation")
    private static void validateApk(Activity owner, File apk, Release release) throws Exception {
        if (!apk.isFile() || apk.length() != release.size || apk.length() > UpdatePolicy.MAX_APK_BYTES) {
            throw new UpdateFailure("The cached APK is incomplete. Close App updates and download it again.");
        }
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = owner.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = installed(owner);
        if (candidate == null || !PACKAGE.equals(candidate.packageName)) {
            throw new UpdateFailure("The downloaded file is not a valid APK for this app. Installation was blocked.");
        }
        long availableCode = Build.VERSION.SDK_INT >= 28 ? candidate.getLongVersionCode() : candidate.versionCode;
        long installedCode = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        if (availableCode <= installedCode) {
            throw new UpdateFailure("The APK is not newer than the installed app. Downgrades and reinstalls are blocked; check again.");
        }
        try {
            if (UpdatePolicy.compareVersions(release.tag, candidate.versionName) != 0
                    || UpdatePolicy.compareVersions(candidate.versionName, installed.versionName) <= 0) {
                throw new UpdateFailure("The APK version does not match the newer public release. Installation was blocked.");
            }
        } catch (IllegalArgumentException e) {
            throw new UpdateFailure("The APK version is not a stable public version. Installation was blocked.");
        }
        boolean compatible;
        if (Build.VERSION.SDK_INT >= 28) {
            // Pin the current signer set. History alone does not expose rotation capabilities,
            // so a release signed only by a historical/rotated key is not pre-approved here.
            compatible = candidate.signingInfo != null && installed.signingInfo != null
                    && sameSigners(candidate.signingInfo.getApkContentsSigners(),
                            installed.signingInfo.getApkContentsSigners());
        } else {
            compatible = sameSigners(candidate.signatures, installed.signatures);
        }
        if (!compatible) throw new UpdateFailure("The APK’s signing certificate is incompatible with this installation. Installation was blocked; use a release signed by the same publisher.");
    }

    private static boolean contains(Signature[] signatures, Signature wanted) {
        if (signatures == null || wanted == null) return false;
        for (Signature signature : signatures) if (wanted.equals(signature)) return true;
        return false;
    }

    private static boolean sameSigners(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return false;
        for (Signature signer : a) if (!contains(b, signer)) return false;
        for (Signature signer : b) if (!contains(a, signer)) return false;
        return true;
    }
}
