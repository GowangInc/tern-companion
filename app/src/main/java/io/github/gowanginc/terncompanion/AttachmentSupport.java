package io.github.gowanginc.terncompanion;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Picker/ContentResolver mechanics for Tern Companion attachments: document
 * metadata, bounded size measurement and sampled thumbnails. No UI, no network,
 * no storage permissions — everything goes through the per-document read grant
 * Android hands back from {@code ACTION_OPEN_DOCUMENT}.
 */
final class AttachmentSupport {
    static final int MAX_FILES = 8;
    static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final int COPY_BUFFER = 8 * 1024;

    private AttachmentSupport() {}

    /** One picked document as reported by the content resolver. */
    static final class Doc {
        final Uri uri;
        final String name;
        final String mime;
        final long size;
        final boolean knownSize;

        Doc(Uri uri, String name, String mime, long size, boolean knownSize) {
            this.uri = uri;
            this.name = name;
            this.mime = mime == null ? "" : mime;
            this.size = size;
            this.knownSize = knownSize;
        }
    }

    /** Resolve up to {@link #MAX_FILES} uris in order; unreadable files fail visibly on send. */
    static List<Doc> resolve(Context ctx, List<Uri> uris) {
        ArrayList<Doc> out = new ArrayList<>();
        if (ctx == null || uris == null) return out;
        ContentResolver resolver = ctx.getContentResolver();
        for (Uri uri : uris) {
            if (uri == null || out.size() >= MAX_FILES) continue;
            String name = null;
            long size = -1;
            boolean known = false;
            if ("content".equals(uri.getScheme())) {
                Cursor cursor = null;
                try {
                    cursor = resolver.query(uri,
                            new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                            null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                            size = cursor.getLong(sizeColumn);
                            known = size >= 0;
                        }
                    }
                } catch (Exception ignored) {
                    // Fall through to a generated name and unknown size.
                } finally {
                    if (cursor != null) try { cursor.close(); } catch (Exception ignored) {}
                }
            }
            if (name == null || name.trim().isEmpty()) {
                String tail = uri.getLastPathSegment();
                name = tail == null || tail.trim().isEmpty() ? "attachment" : tail;
            }
            name = sanitize(name);
            String mime = null;
            try { mime = resolver.getType(uri); } catch (Exception ignored) {}
            if (mime == null || mime.trim().isEmpty()) mime = guessMime(name);
            out.add(new Doc(uri, name, mime, known ? size : -1, known));
        }
        return out;
    }

    /** Count bytes when the resolver reported no size; -1 as soon as the stream exceeds cap. */
    static long measureBytes(Context ctx, Uri uri, long cap) {
        if (ctx == null || uri == null || cap < 0) return -1;
        InputStream in = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return -1;
            byte[] buffer = new byte[COPY_BUFFER];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > cap) return -1;
            }
            return total;
        } catch (Exception e) {
            return -1;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** Lowercased extension without the dot, or "" when there is none. */
    static String extension(String name) {
        if (name == null) return "";
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        if (dot <= slash || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** True for raster image mimes safe to preview inline. */
    static boolean isPreviewableImage(String mime) {
        if (mime == null) return false;
        String value = mime.trim().toLowerCase(Locale.ROOT);
        return "image/png".equals(value) || "image/jpeg".equals(value)
                || "image/webp".equals(value) || "image/gif".equals(value);
    }

    /** Sampled, memory-bounded thumbnail. Callers invoke this off the UI thread. */
    static Bitmap thumbnail(Context ctx, Uri uri, int maxPx) {
        if (ctx == null || uri == null || maxPx <= 0) return null;
        ContentResolver resolver = ctx.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream probe = null;
        try {
            probe = resolver.openInputStream(uri);
            if (probe == null) return null;
            BitmapFactory.decodeStream(probe, null, bounds);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        } finally {
            if (probe != null) try { probe.close(); } catch (Exception ignored) {}
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        while (longest / (sample * 2) >= maxPx) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        InputStream in = null;
        try {
            in = resolver.openInputStream(uri);
            if (in == null) return null;
            return BitmapFactory.decodeStream(in, null, options);
        } catch (Exception e) {
            return null;
        } catch (OutOfMemoryError e) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }


    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        String cleaned = sb.toString().trim();
        if (cleaned.isEmpty()) cleaned = "attachment";
        if (cleaned.length() > 180) cleaned = cleaned.substring(cleaned.length() - 180);
        return cleaned;
    }

    private static String guessMime(String name) {
        switch (extension(name)) {
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "pdf": return "application/pdf";
            case "txt": return "text/plain";
            default: return "";
        }
    }
}
