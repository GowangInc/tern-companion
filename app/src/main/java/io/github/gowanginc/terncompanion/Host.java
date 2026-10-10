package io.github.gowanginc.terncompanion;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/** A private bridge, Tailscale HTTPS bridge or Access-protected HTTPS bridge. */
final class Host {
    final String name;
    final String scheme;
    final String host;
    final int port;
    final String token;
    final String accessId;
    final String accessSecret;

    Host(String name, String address, int port, String token, String accessId, String accessSecret) {
        this.name = name.trim();
        if (this.name.isEmpty() || this.name.length() > 80) {
            throw new IllegalArgumentException("Enter a PC name of 1–80 characters.");
        }
        for (int i = 0; i < this.name.length(); i++) {
            if (Character.isISOControl(this.name.charAt(i))) {
                throw new IllegalArgumentException("The PC name must be a single line.");
            }
        }
        this.accessId = accessId.trim();
        this.accessSecret = accessSecret.trim();
        if (this.accessId.contains("\n") || this.accessId.contains("\r")
                || this.accessSecret.contains("\n") || this.accessSecret.contains("\r")
                || this.accessId.length() > 256 || this.accessSecret.length() > 256) {
            throw new IllegalArgumentException("Invalid Cloudflare Access credentials.");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1–65535.");
        this.port = port;
        this.token = validateToken(token);
        String value = address.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Enter the PC address.");
        if (!value.contains("://")) value = "http://" + value;
        try {
            URI uri = new URI(value);
            String protocol = uri.getScheme();
            String hostname = uri.getHost();
            if (protocol == null || (!protocol.equalsIgnoreCase("http") && !protocol.equalsIgnoreCase("https"))
                    || hostname == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || (uri.getRawPath() != null
                    && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                    || uri.getPort() != -1) {
                throw new IllegalArgumentException("Enter a host or HTTPS address, without a path, port or query.");
            }
            this.scheme = protocol.toLowerCase(Locale.ROOT);
            this.host = hostname.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Enter a valid PC address.");
        }
        boolean privateHost = isPrivateHost(this.host);
        if (!privateHost && !this.scheme.equals("https")) {
            throw new IllegalArgumentException("Public hosts require HTTPS; enter https://hostname.");
        }
        if (!privateHost && (this.accessId.isEmpty() || this.accessSecret.isEmpty())) {
            throw new IllegalArgumentException("Public HTTPS hosts require both Cloudflare Access credentials.");
        }
        if (this.accessId.isEmpty() != this.accessSecret.isEmpty()) {
            throw new IllegalArgumentException("Enter both Cloudflare Access credentials.");
        }
        if (!this.accessId.isEmpty() && !this.scheme.equals("https")) {
            throw new IllegalArgumentException("Cloudflare Access credentials require HTTPS.");
        }
    }

    private static boolean isPrivateHost(String value) {
        if (value.equals("localhost") || value.endsWith(".ts.net")) return true;
        String[] octets = value.split("\\.");
        if (octets.length != 4) return false;
        int[] n = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (!Pattern.matches("[0-9]{1,3}", octets[i])) return false;
                n[i] = Integer.parseInt(octets[i]);
                if (n[i] > 255) return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return n[0] == 10 || n[0] == 127 || n[0] == 192 && n[1] == 168
                || n[0] == 172 && n[1] >= 16 && n[1] <= 31
                || n[0] == 100 && n[1] >= 64 && n[1] <= 127;
    }

    private static String validateToken(String input) {
        String t = input.trim();
        if (t.length() != 64 || !Pattern.matches("[0-9a-f]+", t)) {
            throw new IllegalArgumentException("Paste the 64-character bridge token exactly.");
        }
        return t;
    }

    String baseUrl() {
        return scheme + "://" + host + ":" + port;
    }
}
