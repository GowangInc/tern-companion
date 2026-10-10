package io.github.gowanginc.terncompanion;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** Execute on Android with app_process and the built APK on CLASSPATH. */
public final class QrCheck {
    private static String token() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 64; i++) s.append('0');
        return s.toString();
    }

    public static void main(String[] args) throws Exception {
        String valid = "{\"app\":\"tern-companion\",\"version\":1,\"name\":\"PC\","
                + "\"address\":\"http://100.64.0.42\",\"port\":9260,\"token\":\"" + token() + "\"}";
        Host privateHost = HostQrScanner.decode(valid);
        if (!privateHost.baseUrl().equals("http://100.64.0.42:9260")) throw new AssertionError("Private QR origin changed");
        String https = valid.replace("http://100.64.0.42", "https://tern.example.com")
                .replace("9260", "443").replace("\"name\":\"PC\"", "\"name\":\"PC\",\"accessId\":\"test-id\",\"accessSecret\":\"test-secret\"");
        Host accessHost = HostQrScanner.decode(https);
        if (!accessHost.baseUrl().equals("https://tern.example.com:443") || !accessHost.accessId.equals("test-id"))
            throw new AssertionError("Access QR settings changed");
        Host funnelHost = HostQrScanner.decode(valid.replace("http://100.64.0.42", "https://pc.example.ts.net").replace("9260", "8443"));
        if (!funnelHost.baseUrl().equals("https://pc.example.ts.net:8443") || !funnelHost.accessId.isEmpty())
            throw new AssertionError("Funnel QR requires Cloudflare credentials");
        reject(valid.replace("\"version\":1", "\"version\":2"));
        reject(valid.replace("tern-companion", "different-app"));
        reject(valid.replace("\"port\":9260", "\"port\":\"9260\""));
        reject(valid.replace("\"port\":9260", "\"port\":9260.0"));
        reject(valid.replace("\"port\":9260", "\"port\":9260,\"port\":443"));
        reject(valid.replace("\"port\":9260", "\"port\":0"));
        reject(valid.replace("\"port\":9260", "\"port\":65536"));
        reject(valid.replace("\"name\":\"PC\"", "\"name\":null"));
        reject(valid.replace("\"name\":\"PC\"", "\"name\":\"PC\\nspoof\""));
        reject(valid.replace("\"name\":\"PC\",", ""));
        reject(valid.replace("\"name\":\"PC\"", "\"name\":\"PC\",\"unknown\":true"));
        reject(valid.replace("http://100.64.0.42", "http://8.8.8.8"));
        reject(valid.replace("http://100.64.0.42", "https://tern.example.com"));
        reject(valid + "{}");
        reject("not JSON");
        StringBuilder huge = new StringBuilder(valid);
        while (huge.length() <= 4096) huge.append(' ');
        reject(huge.toString());
        checkImages(valid.replace("http://100.64.0.42", "https://pc.example.ts.net").replace("9260", "8443"));
        System.out.println("Native QR trust-boundary checks passed.");
    }

    private static void checkImages(String valid) throws Exception {
        Bitmap image = Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888);
        try {
            BitMatrix qr = new QRCodeWriter().encode(valid, BarcodeFormat.QR_CODE, 320, 320);
            int[] pixels = new int[320 * 320];
            for (int y = 0; y < 320; y++)
                for (int x = 0; x < 320; x++)
                    pixels[y * 320 + x] = qr.get(x, y) ? Color.BLACK : Color.WHITE;
            image.setPixels(pixels, 0, 320, 0, 0, 320, 320);
            Host host = HostQrScanner.decodeImage(image);
            if (!host.baseUrl().equals("https://pc.example.ts.net:8443") || !host.token.equals(token())
                    || !host.accessId.isEmpty()) throw new AssertionError("QR image pairing settings changed");
            image.eraseColor(Color.WHITE);
            try {
                HostQrScanner.decodeImage(image);
                throw new AssertionError("An image without a QR was accepted");
            } catch (IllegalArgumentException expected) {
                if (expected.getMessage() == null || expected.getMessage().contains(token()))
                    throw new AssertionError("Image error disclosed pairing credentials");
            }
        } finally {
            image.recycle();
        }
    }

    private static void reject(String value) {
        try {
            HostQrScanner.decode(value);
            throw new AssertionError("Unsafe QR accepted");
        } catch (IllegalArgumentException expected) {
            if (expected.getMessage() == null || expected.getMessage().contains(token()))
                throw new AssertionError("QR error disclosed payload data");
        }
    }
}
