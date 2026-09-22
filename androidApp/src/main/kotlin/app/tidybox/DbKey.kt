package app.tidybox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE = "AndroidKeyStore"
private const val ALIAS = "tidybox-db"
private const val PREFS = "tidybox-db-key"
private const val PREF_WRAPPED = "wrapped" // base64(iv || ciphertext)

/**
 * 32 random bytes, generated once, stored only as an AES-GCM ciphertext under a non-exportable
 * AndroidKeyStore key. The prefs file is excluded from backup (data_extraction_rules.xml), and
 * even copied it is useless without this device's Keystore.
 */
fun dbPassphrase(context: Context): ByteArray {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val key = keystoreKey()
    prefs.getString(PREF_WRAPPED, null)?.let { b64 ->
        val blob = Base64.decode(b64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
    val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val blob = cipher.iv + cipher.doFinal(passphrase)
    prefs.edit().putString(PREF_WRAPPED, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    return passphrase
}

private fun keystoreKey(): SecretKey {
    val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    gen.init(
        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
    )
    return gen.generateKey()
}
