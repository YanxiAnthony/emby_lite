package com.example.embylite;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CredentialStore {
    private static final String KEY_ALIAS = "emby_lite_password_key";
    private static final String USERNAME = "savedUsername";
    private static final String PASSWORD = "savedPassword";
    private static final String PASSWORD_IV = "savedPasswordIv";
    private static final String REMEMBER = "rememberCredentials";

    private CredentialStore() {
    }

    static void save(SharedPreferences preferences, String username, String password) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            preferences.edit()
                    .putString(USERNAME, username)
                    .putString(PASSWORD, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(PASSWORD_IV,
                            Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putBoolean(REMEMBER, true)
                    .apply();
        } catch (Exception error) {
            clear(preferences);
        }
    }

    static String username(SharedPreferences preferences) {
        return preferences.getString(USERNAME, "");
    }

    static String password(SharedPreferences preferences) {
        String encryptedText = preferences.getString(PASSWORD, "");
        String ivText = preferences.getString(PASSWORD_IV, "");
        if (encryptedText.isEmpty() || ivText.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            );
            byte[] decrypted = cipher.doFinal(Base64.decode(encryptedText, Base64.NO_WRAP));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception error) {
            clear(preferences);
            return "";
        }
    }

    static boolean hasSavedCredentials(SharedPreferences preferences) {
        return !username(preferences).isEmpty()
                && !preferences.getString(PASSWORD, "").isEmpty();
    }

    static boolean shouldRemember(SharedPreferences preferences) {
        return preferences.getBoolean(REMEMBER, true);
    }

    static void clear(SharedPreferences preferences) {
        preferences.edit()
                .remove(USERNAME)
                .remove(PASSWORD)
                .remove(PASSWORD_IV)
                .putBoolean(REMEMBER, false)
                .apply();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
