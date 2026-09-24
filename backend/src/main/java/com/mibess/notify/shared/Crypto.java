package com.mibess.notify.shared;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Crypto {
    private final byte[] masterKey;
    private static final SecureRandom RANDOM = new SecureRandom();
    public Crypto(@Value("${notify.encryption-key}") String key) {
        masterKey = Base64.getDecoder().decode(key);
        if (masterKey.length != 32) throw new IllegalArgumentException("MASTER_ENCRYPTION_KEY deve conter 32 bytes em Base64");
    }
    public static String token() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    public static String hash(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (GeneralSecurityException e) { throw new IllegalStateException(e); } }
    public String encrypt(String plain, String context) {
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(masterKey,"AES"),new GCMParameterSpec(128,iv)); c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getEncoder().encodeToString(iv) + "." + Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Falha na criptografia"); }
    }
    public String decrypt(String encrypted, String context) {
        try {
            String[] parts=encrypted.split("\\.");
            if(parts.length!=3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(masterKey,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[1]))); c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(Base64.getDecoder().decode(parts[2])),StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException("Credencial indisponível"); }
    }
    public static boolean signature(byte[] body, String secret, String signature) {
        if (signature==null || !signature.matches("sha256=[0-9a-fA-F]{64}")) return false;
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature.substring(7))); } catch(Exception e) { return false; }
    }
}
