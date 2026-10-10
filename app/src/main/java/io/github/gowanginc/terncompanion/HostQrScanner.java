package io.github.gowanginc.terncompanion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class HostQrScanner {
    private static final int REQUEST_CODE = 0xc0de;
    private static final int IMAGE_REQUEST_CODE = 0xc0df;
    private static final int MAX_IMAGE_SIDE = 2048;
    private static final int MAX_BYTES = 4096;

    private HostQrScanner() {}

    public static void launch(Activity owner) {
        Intent scan = new ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(PairingCaptureActivity.class)
                .setPrompt("Scan the QR on your PC. Treat it like a password.")
                .setOrientationLocked(false)
                .setBeepEnabled(false)
                .setBarcodeImageEnabled(false)
                .createScanIntent(owner);
        owner.startActivityForResult(scan, REQUEST_CODE);
    }

    public static void launchImage(Activity owner) {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        owner.startActivityForResult(pick, IMAGE_REQUEST_CODE);
    }

    public static boolean handleResult(Activity owner, int requestCode, int resultCode, Intent data,
                                       Executor io, Consumer<Host> onConfirmed) {
        if (requestCode != REQUEST_CODE && requestCode != IMAGE_REQUEST_CODE) return false;
        if (resultCode != Activity.RESULT_OK) return true;
        if (requestCode == IMAGE_REQUEST_CODE) {
            Uri uri = data == null ? null : data.getData();
            if (uri == null) {
                showError(owner, "No image was selected. Choose a saved QR image.");
                return true;
            }
            io.execute(() -> {
                try {
                    Host host = readImage(owner, uri);
                    owner.runOnUiThread(() -> confirm(owner, host, onConfirmed));
                } catch (IOException | SecurityException e) {
                    showError(owner, "Couldn’t open this image. Choose a locally saved PNG or JPEG.");
                } catch (IllegalArgumentException e) {
                    showError(owner, e.getMessage());
                }
            });
            return true;
        }

        final Host host;
        try {
            if (data == null) throw new IllegalArgumentException("The scan returned no QR code. Try again.");
            ScanIntentResult result = ScanIntentResult.parseActivityResult(resultCode, data);
            if (!ScanOptions.QR_CODE.equals(result.getFormatName())) {
                throw new IllegalArgumentException("Scan a Tern Companion QR code generated on your PC.");
            }
            host = decode(result.getContents());
        } catch (IllegalArgumentException e) {
            showError(owner, e.getMessage());
            return true;
        }
        confirm(owner, host, onConfirmed);
        return true;
    }

    private static Host readImage(Activity owner, Uri uri) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = owner.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException();
            BitmapFactory.decodeStream(stream, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IllegalArgumentException("This file isn’t a readable image. Choose a PNG or JPEG QR image.");
        }
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > MAX_IMAGE_SIDE
                || options.outHeight / options.inSampleSize > MAX_IMAGE_SIDE) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        Bitmap bitmap;
        try (InputStream stream = owner.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException();
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) throw new IllegalArgumentException("This image couldn’t be decoded. Choose another QR image.");
        try {
            return decodeImage(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    static Host decodeImage(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        RGBLuminanceSource source = new RGBLuminanceSource(width, height, pixels);
        final String contents;
        try {
            contents = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source)),
                    Collections.singletonMap(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();
        } catch (ReaderException e) {
            throw new IllegalArgumentException("No readable QR code found. Choose a clear image of your Tern Companion pairing QR.");
        }
        return decode(contents);
    }

    private static void showError(Activity owner, String message) {
        owner.runOnUiThread(() -> {
            if (owner.isFinishing() || owner.isDestroyed()) return;
            new AlertDialog.Builder(owner).setTitle("Couldn’t read this QR")
                    .setMessage(message).setPositiveButton("OK", null).show();
        });
    }

    private static void confirm(Activity owner, Host host, Consumer<Host> onConfirmed) {
        if (owner.isFinishing() || owner.isDestroyed()) return;

        String auth = host.accessId.isEmpty()
                ? (host.scheme.equals("https") ? "HTTPS + bridge token" : "Private network + bridge token")
                : "Cloudflare Access + bridge token";
        String message = "PC: " + redact(host.name, host)
                + "\nAddress: " + redact(host.baseUrl(), host)
                + "\nAuthentication: " + auth
                + "\n\nSaving this QR grants control of this PC. Only save it if you trust the PC and generated this QR yourself.";
        new AlertDialog.Builder(owner).setTitle("Save this PC?")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save PC", (dialog, which) -> onConfirmed.accept(host))
                .show();
    }

    static Host decode(String contents) {
        if (contents == null || contents.isEmpty()) {
            throw new IllegalArgumentException("The QR contains no host details.");
        }
        if (contents.length() > MAX_BYTES || contents.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("This QR is too large. Generate a fresh Tern Companion QR on your PC.");
        }

        String app = "", name = "", address = "", token = "", accessId = "", accessSecret = "";
        int version = 0, port = 0, seen = 0;
        try (JsonReader reader = new JsonReader(new StringReader(contents))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                int field;
                switch (reader.nextName()) {
                    case "app": field = 1; app = string(reader); break;
                    case "version": field = 2; version = integer(reader); break;
                    case "name": field = 4; name = string(reader); break;
                    case "address": field = 8; address = string(reader); break;
                    case "port": field = 16; port = integer(reader); break;
                    case "token": field = 32; token = string(reader); break;
                    case "accessId": field = 64; accessId = string(reader); break;
                    case "accessSecret": field = 128; accessSecret = string(reader); break;
                    default: throw new IllegalArgumentException();
                }
                if ((seen & field) != 0) throw new IllegalArgumentException();
                seen |= field;
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT || (seen & 63) != 63) {
                throw new IllegalArgumentException();
            }
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            // JsonReader errors can include payload text. Never expose them or their causes.
            throw new IllegalArgumentException("This QR is malformed or has missing, duplicate or invalid fields.");
        }
        if (!app.equals("tern-companion") || version != 1) {
            throw new IllegalArgumentException("This QR is for a different app or an unsupported Tern Companion version.");
        }
        try {
            // QR addresses are explicit origins, unlike the manual-entry shortcuts accepted by Host.
            if ((!address.startsWith("http://") && !address.startsWith("https://"))
                    || !address.equals(address.trim()) || address.endsWith("/")) {
                throw new IllegalArgumentException();
            }
            Host host = new Host(name, address, port, token, accessId, accessSecret);
            if (!token.equals(host.token)) throw new IllegalArgumentException();
            return host;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("This QR has an invalid PC address, port or credentials. Generate a fresh QR on your PC.");
        }
    }

    private static String string(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new IllegalArgumentException();
        return reader.nextString();
    }

    private static int integer(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.NUMBER) throw new IllegalArgumentException();
        return Integer.parseInt(reader.nextString());
    }

    private static String redact(String value, Host host) {
        String safe = value.replace(host.token, "[hidden]");
        if (!host.accessId.isEmpty()) safe = safe.replace(host.accessId, "[hidden]");
        if (!host.accessSecret.isEmpty()) safe = safe.replace(host.accessSecret, "[hidden]");
        return safe;
    }
}
