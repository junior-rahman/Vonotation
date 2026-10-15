package app.voiceflow;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Portable password encryption. Android Keystore keys are never part of this file. */
final class BackupArchive {
    static final int MAX_BYTES=8*1024*1024;
    private static final byte[] MAGIC={'V','F','L','O','W','B','K','P'};
    private static final int VERSION=1, ITERATIONS=210000, HEADER_BYTES=48, TAG_BYTES=16;
    private static final SecureRandom RANDOM=new SecureRandom();

    static void checkPassword(char[] password) throws IOException {
        if(password==null || password.length<8) throw new IOException("Use a backup password with at least 8 characters.");
        if(password.length>1024) throw new IOException("The backup password is too long.");
    }

    static byte[] encode(JSONObject payload,char[] password) throws Exception {
        if(payload==null) throw new IOException("There is no backup data.");
        byte[] plain=payload.toString().getBytes(StandardCharsets.UTF_8);
        try { return encodeUtf8(plain,password); }
        finally { Arrays.fill(plain,(byte)0); }
    }

    static JSONObject decode(byte[] bytes,char[] password) throws Exception {
        byte[] plain=decodeUtf8(bytes,password);
        try {
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString();
            checkJsonShape(json);
            return new JSONObject(json);
        } catch(org.json.JSONException | java.nio.charset.CharacterCodingException error) {
            throw new IOException("The backup data is not readable.");
        } finally { Arrays.fill(plain,(byte)0); }
    }

    // Byte helpers keep the actual archive implementation covered by the plain Java tests.
    static byte[] encodeUtf8(byte[] plain,char[] password) throws Exception {
        checkPassword(password);
        if(plain==null || plain.length>MAX_BYTES-HEADER_BYTES-TAG_BYTES) throw new IOException("The backup is too large.");
        byte[] salt=new byte[16],iv=new byte[12];RANDOM.nextBytes(salt);RANDOM.nextBytes(iv);
        byte[] header=ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(VERSION).putInt(ITERATIONS).put(salt).put(iv).putInt(plain.length+TAG_BYTES).array();
        byte[] key=derive(password,salt);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(header);
            return ByteBuffer.allocate(header.length+plain.length+TAG_BYTES).put(header).put(cipher.doFinal(plain)).array();
        } finally { Arrays.fill(key,(byte)0); }
    }

    static byte[] decodeUtf8(byte[] bytes,char[] password) throws Exception {
        checkPassword(password);
        if(bytes==null || bytes.length<HEADER_BYTES+TAG_BYTES || bytes.length>MAX_BYTES) throw new IOException("The backup file is incomplete or too large.");
        ByteBuffer input=ByteBuffer.wrap(bytes);byte[] magic=new byte[8];input.get(magic);
        if(!Arrays.equals(magic,MAGIC)) throw new IOException("Choose an encrypted VonoTation backup file.");
        if(input.getInt()!=VERSION) throw new IOException("This backup version is not supported by this app.");
        if(input.getInt()!=ITERATIONS) throw new IOException("The backup header is not valid.");
        byte[] salt=new byte[16],iv=new byte[12];input.get(salt);input.get(iv);
        int length=input.getInt();if(length<TAG_BYTES || length!=bytes.length-HEADER_BYTES) throw new IOException("The backup file is incomplete.");
        byte[] key=derive(password,salt);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(bytes,0,HEADER_BYTES);
            try { return cipher.doFinal(bytes,HEADER_BYTES,length); }
            catch(AEADBadTagException error) { throw new IOException("The backup password is incorrect, or the file was changed."); }
        } finally { Arrays.fill(key,(byte)0); }
    }

    private static byte[] derive(char[] password,byte[] salt) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password,salt,ITERATIONS,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
    private static void checkJsonShape(String json) throws IOException {
        // A valid schema has only a few levels and at most 100 small entry objects.
        // Bound parser recursion/allocation before parsing a password-authenticated imported file.
        int depth=0,separators=0;boolean quoted=false,escaped=false;
        for(int i=0;i<json.length();i++) {
            char value=json.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(value=='\\')escaped=true;else if(value=='"')quoted=false;continue;}
            if(value=='"')quoted=true;
            else if(value=='{' || value=='['){if(++depth>16)throw new IOException("The backup data is too complex.");}
            else if(value=='}' || value==']')depth--;
            else if(value==',' && ++separators>2048)throw new IOException("The backup contains too many data fields.");
        }
    }
    private BackupArchive(){}
}
