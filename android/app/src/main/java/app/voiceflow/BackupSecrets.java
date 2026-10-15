package app.voiceflow;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-bound password storage used only for the user's optional scheduled backups. */
final class BackupSecrets {
    private static final String ALIAS="voiceflow_backup_password", PREFS="voiceflow_backup_secrets";
    private static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)) {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    static synchronized void set(Context c,char[] password) throws Exception {
        BackupArchive.checkPassword(password);
        ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(password));
        byte[] plain=new byte[encoded.remaining()];encoded.get(plain);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
            String secret=Base64.encodeToString(cipher.doFinal(plain),Base64.NO_WRAP),iv=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP);
            Map<String,?> before=snapshot(c);
            if(!prefs(c).edit().putString("secret",secret).putString("iv",iv).commit()) {
                writeRaw(c,before);throw new IOException("Could not remember the backup password on this phone.");
            }
        } finally {Arrays.fill(plain,(byte)0);if(encoded.hasArray())Arrays.fill(encoded.array(),(byte)0);}
    }
    static synchronized char[] get(Context c) throws Exception {
        SharedPreferences pref=prefs(c);String secret=pref.getString("secret","");
        if(secret.isEmpty())return null;
        byte[] plain=null;char[] password=null;
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(pref.getString("iv",""),Base64.NO_WRAP)));plain=cipher.doFinal(Base64.decode(secret,Base64.NO_WRAP));
            CharBuffer decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain));password=new char[decoded.remaining()];decoded.get(password);
            if(decoded.hasArray())Arrays.fill(decoded.array(),'\0');BackupArchive.checkPassword(password);return password;
        } catch(Exception error) {if(password!=null)Arrays.fill(password,'\0');throw new IOException("Enter your backup password again to enable automatic backups.");}
        finally {if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    // Ciphertext snapshots let connection changes roll back even if an old key cannot decrypt.
    static synchronized Map<String,?> snapshot(Context c){return Collections.unmodifiableMap(new HashMap<String,Object>(prefs(c).getAll()));}
    static synchronized void restore(Context c,Map<String,?> state) throws IOException {
        if(state==null)throw new IOException("The previous backup password state is unavailable.");
        for(Map.Entry<String,?> item:state.entrySet())if((!item.getKey().equals("secret") && !item.getKey().equals("iv")) || !(item.getValue() instanceof String))throw new IOException("The saved backup password state is invalid.");
        Map<String,?> before=snapshot(c);if(!writeRaw(c,state)){writeRaw(c,before);throw new IOException("Could not save the backup password settings.");}
    }
    private static boolean writeRaw(Context c,Map<String,?> state){SharedPreferences.Editor edit=prefs(c).edit().clear();for(Map.Entry<String,?> item:state.entrySet())edit.putString(item.getKey(),(String)item.getValue());return edit.commit();}
    static synchronized void clear(Context c){try{restore(c,Collections.<String,Object>emptyMap());}catch(IOException error){throw new IllegalStateException("Could not remove the remembered backup password.");}}
    private BackupSecrets(){}
}
