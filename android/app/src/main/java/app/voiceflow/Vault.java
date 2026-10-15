package app.voiceflow;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Vault {
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias("voiceflow_api")) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("voiceflow_api", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey("voiceflow_api", null);
    }
    /** Encrypt first; the caller can write this together with endpoint/settings in one commit. */
    static final class PreparedKey {
        final String secret, iv;
        private PreparedKey(String secret, String iv) { this.secret=secret; this.iv=iv; }
    }
    static PreparedKey prepare(String value) throws Exception {
        synchronized(Store.class) {
        if(value==null || value.trim().isEmpty()) return new PreparedKey(null,null);
        byte[] plain=value.trim().getBytes("UTF-8");
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
            return new PreparedKey(Base64.encodeToString(cipher.doFinal(plain),Base64.NO_WRAP),Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP));
        } finally { java.util.Arrays.fill(plain,(byte)0); }
        }
    }
    static void write(SharedPreferences.Editor editor, PreparedKey prepared) {
        if(prepared.secret==null) editor.remove("api_secret").remove("api_iv");
        else editor.putString("api_secret",prepared.secret).putString("api_iv",prepared.iv);
    }
    static void set(Context c, String value) throws Exception {
        synchronized(Store.class) {
            SharedPreferences.Editor editor=Store.prefs(c).edit();write(editor,prepare(value));editor.apply();
        }
    }
    static String get(Context c) {
        synchronized(Store.class) {
        try {
            String secret = Store.get(c, "api_secret", ""); if (secret.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(Store.get(c, "api_iv", ""), Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(secret, Base64.NO_WRAP)), "UTF-8");
        } catch (Exception e) { return ""; }
        }
    }
}
