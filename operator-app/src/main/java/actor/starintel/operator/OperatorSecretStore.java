package actor.starintel.operator;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keystore-backed bearer key storage for the Operator's own server credential. */
public final class OperatorSecretStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "operator_secrets";
    private static final String PREFS = "operator_secrets";
    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private final SharedPreferences prefs;

    public OperatorSecretStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(String slot, String value) {
        try {
            if (value == null || value.trim().isEmpty()) {
                clear(slot);
                return;
            }
            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
            prefs.edit()
                    .putString(prefKey(slot), Base64.getEncoder().encodeToString(encrypted))
                    .putString(prefKey(slot) + ".iv", Base64.getEncoder().encodeToString(cipher.getIV()))
                    .apply();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not store secret", failure);
        }
    }

    public String read(String slot) {
        String encoded = prefs.getString(prefKey(slot), null);
        String encodedIv = prefs.getString(prefKey(slot) + ".iv", null);
        if (encoded == null || encodedIv == null) return null;
        try {
            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    new GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(encodedIv)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(encoded)), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            return null;
        }
    }

    public void clear(String slot) {
        prefs.edit().remove(prefKey(slot)).remove(prefKey(slot) + ".iv").apply();
    }

    private static String prefKey(String slot) {
        String clean = slot == null ? "" : slot.trim();
        if (!clean.matches("[a-z_.]{1,64}")) throw new IllegalArgumentException("secret slot malformed");
        return "secret." + clean;
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(
                new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
        return generator.generateKey();
    }
}
