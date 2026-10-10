package io.github.gowanginc.terncompanion;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Saved PCs, encrypted at rest with an Android Keystore AES-256-GCM key. */
final class HostStore {
    private static final String PREFS = "tern_hosts";
    private static final String KEY = "hosts";
    private static final String ALIAS = "tern_host_store_key";
    private static final int IV_LEN = 12;

    private final SharedPreferences prefs;

    HostStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<Host> load() throws Exception {
        ArrayList<Host> out = new ArrayList<>();
        String encoded = prefs.getString(KEY, null);
        if (encoded == null) return out;
        byte[] blob = Base64.decode(encoded, Base64.NO_WRAP);
        if (blob.length <= IV_LEN) throw new IllegalStateException("corrupt");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, blob, 0, IV_LEN));
        JSONArray arr = new JSONArray(new String(cipher.doFinal(blob, IV_LEN, blob.length - IV_LEN), StandardCharsets.UTF_8));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            out.add(new Host(o.getString("name"), o.optString("scheme", "http") + "://" + o.getString("host"),
                    o.getInt("port"), o.getString("token"),
                    o.optString("accessId", ""), o.optString("accessSecret", "")));
        }
        return out;
    }

    void save(List<Host> hosts) throws Exception {
        JSONArray arr = new JSONArray();
        for (Host h : hosts) {
            arr.put(new JSONObject()
                    .put("name", h.name).put("scheme", h.scheme).put("host", h.host)
                    .put("port", h.port).put("token", h.token)
                    .put("accessId", h.accessId).put("accessSecret", h.accessSecret));
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] enc = cipher.doFinal(arr.toString().getBytes(StandardCharsets.UTF_8));
        byte[] blob = ByteBuffer.allocate(cipher.getIV().length + enc.length)
                .put(cipher.getIV()).put(enc).array();
        if (!prefs.edit().putString(KEY, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
            throw new IllegalStateException("commit failed");
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return gen.generateKey();
    }
}
