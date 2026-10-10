package io.github.gowanginc.terncompanion;

/** Run with javac + java; no Android runtime or test dependencies needed. */
public final class HostCheck {
    private static final String TOKEN = "0".repeat(64);

    public static void main(String[] args) {
        expectUrl("100.64.0.42", 9260, "", "", "http://100.64.0.42:9260");
        expectUrl("https://TERN.EXAMPLE.COM", 443, "test-id", "test-secret", "https://tern.example.com:443");
        expectUrl("192.168.1.10", 1, "", "", "http://192.168.1.10:1");
        expectUrl("pc.example.ts.net", 65535, "", "", "http://pc.example.ts.net:65535");
        reject("8.8.8.8", 9260, TOKEN, "", "");
        reject("172.15.0.1", 9260, TOKEN, "", "");
        reject("100.63.255.255", 9260, TOKEN, "", "");
        reject("192.168.999.1", 9260, TOKEN, "", "");
        reject("https://tern.example.com", 443, TOKEN, "", "");
        reject("https://tern.example.com", 443, TOKEN, "test-id", "");
        reject("https://tern.example.com", 443, TOKEN, "", "test-secret");
        reject("100.64.0.42", 9260, TOKEN, "test-id", "test-secret");
        reject("https://tern.example.com/path", 443, TOKEN, "test-id", "test-secret");
        reject("https://tern.example.com?token=x", 443, TOKEN, "test-id", "test-secret");
        reject("https://user:pass@tern.example.com", 443, TOKEN, "test-id", "test-secret");
        reject("https://tern.example.com:443", 443, TOKEN, "test-id", "test-secret");
        reject("https://tern.example.com", 443, TOKEN, "test\r\nid", "test-secret");
        reject("100.64.0.42", 0, TOKEN, "", "");
        reject("100.64.0.42", 65536, TOKEN, "", "");
        reject("100.64.0.42", 9260, "a".repeat(63), "", "");
        reject("100.64.0.42", 9260, "g".repeat(64), "", "");
        try {
            new Host("PC\nspoof", "100.64.0.42", 9260, TOKEN, "", "");
            throw new AssertionError("Control character in display name accepted");
        } catch (IllegalArgumentException expected) {}
        System.out.println("Host trust-boundary checks passed.");
    }

    private static void expectUrl(String address, int port, String id, String secret, String expected) {
        Host host = new Host("PC", address, port, TOKEN, id, secret);
        if (!host.baseUrl().equals(expected)) throw new AssertionError("Origin normalization failed");
    }

    private static void reject(String address, int port, String token, String id, String secret) {
        try {
            new Host("PC", address, port, token, id, secret);
            throw new AssertionError("Unsafe host settings accepted");
        } catch (IllegalArgumentException expected) {}
    }
}
