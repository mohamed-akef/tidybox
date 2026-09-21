package co.raseed

import co.raseed.db.RaseedDb
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup story (design §3, §7): an encrypted file the user keeps wherever they like.
 * Contents = raw messages + the user's corrections. Everything else is re-derived on import.
 *
 * Format: magic "RSD1" || salt(16) || iv(12) || AES-256-GCM(PBKDF2-HMAC-SHA256(passphrase, salt, 200k) , json)
 */
private const val MAGIC = "RSD1"
private const val ITER = 200_000

private fun key(passphrase: CharArray, salt: ByteArray) = SecretKeySpec(
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(passphrase, salt, ITER, 256)).encoded, "AES",
)

/** Pure: plaintext → sealed blob. Random salt and IV each call. */
fun seal(plain: ByteArray, passphrase: CharArray): ByteArray {
    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase, salt)) }
    return MAGIC.toByteArray() + salt + cipher.iv + cipher.doFinal(plain)
}

/** Pure: sealed blob → plaintext. Throws on wrong passphrase, tamper, or wrong magic. */
fun open(blob: ByteArray, passphrase: CharArray): ByteArray {
    require(blob.size > 32 && String(blob, 0, 4) == MAGIC) { "not a Raseed export" }
    val salt = blob.copyOfRange(4, 20)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, blob, 20, 12)) }
    return cipher.doFinal(blob, 32, blob.size - 32)
}

fun exportEncrypted(db: RaseedDb, passphrase: CharArray): ByteArray {
    val q = db.raseedQueries
    val json = JSONObject().apply {
        put("version", 1)
        put("messages", JSONArray().apply {
            q.allMessages().executeAsList().forEach { put(JSONObject().put("s", it.sender).put("b", it.body).put("t", it.received_at)) }
        })
        put("rules", JSONArray().apply {
            q.rules().executeAsList().forEach { put(JSONObject().put("k", it.merchant_key).put("c", it.category)) }
        })
    }.toString().toByteArray()
    return seal(json, passphrase)
}

/** @return (messages stored, rules stored). Throws on wrong passphrase or corrupt file. */
fun importEncrypted(db: RaseedDb, blob: ByteArray, passphrase: CharArray): Pair<Int, Int> {
    val json = JSONObject(String(open(blob, passphrase)))
    val q = db.raseedQueries
    val msgs = json.getJSONArray("messages")
    val rules = json.getJSONArray("rules")
    db.transaction {
        for (i in 0 until msgs.length()) msgs.getJSONObject(i).let { storeMessage(db, it.getString("s"), it.getString("b"), it.getLong("t")) }
        for (i in 0 until rules.length()) rules.getJSONObject(i).let { q.upsertRule(it.getString("k"), it.getString("c")) }
    }
    parsePending(db)
    for (i in 0 until rules.length()) rules.getJSONObject(i).let { q.applyRule(it.getString("c"), it.getString("k")) }
    return msgs.length() to rules.length()
}
