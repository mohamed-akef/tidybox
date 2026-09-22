package app.tidybox

import app.tidybox.db.TidyBoxDb
import app.tidybox.engine.RulePack
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
 * Contents = raw messages + the user's corrections. Everything else is re-derived on import —
 * which is exactly why this only works while *Keep raw messages* is on. With it off the bodies
 * are already blank, so the file would restore corrections and nothing else; Settings disables
 * Export in that state rather than writing a backup that silently is not one.
 *
 * Format: magic "TDB1" || salt(16) || iv(12) || AES-256-GCM(PBKDF2-HMAC-SHA256(passphrase, salt, 200k) , json)
 */
private const val MAGIC = "TDB1"
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
    require(blob.size > 32 && String(blob, 0, 4) == MAGIC) { "not a Tidy Box export" }
    val salt = blob.copyOfRange(4, 20)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, blob, 20, 12)) }
    return cipher.doFinal(blob, 32, blob.size - 32)
}

fun exportEncrypted(db: TidyBoxDb, passphrase: CharArray): ByteArray {
    val q = db.tidyBoxQueries
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

/**
 * Restore a sealed file. A backup is untrusted input like any other — it may be older than the
 * user's current allowlist, or not theirs at all — so every message goes through [storeMessage]'s
 * allowlist guard and messages from senders they no longer allow are dropped, not restored.
 *
 * @return (messages stored, rules stored); the message count excludes anything the allowlist
 *   dropped. Throws on wrong passphrase or corrupt file.
 */
fun importEncrypted(db: TidyBoxDb, allowed: Set<String>, pack: RulePack, keepRaw: Boolean, blob: ByteArray, passphrase: CharArray): Pair<Int, Int> {
    val json = JSONObject(String(open(blob, passphrase)))
    val q = db.tidyBoxQueries
    val msgs = json.getJSONArray("messages")
    val rules = json.getJSONArray("rules")
    var stored = 0
    db.transaction {
        for (i in 0 until msgs.length()) msgs.getJSONObject(i).let {
            if (storeMessage(db, allowed, it.getString("s"), it.getString("b"), it.getLong("t"))) stored++
        }
        for (i in 0 until rules.length()) rules.getJSONObject(i).let { q.upsertRule(it.getString("k"), it.getString("c")) }
    }
    parsePending(db, pack, keepRaw)
    // Rows parsed just now already picked these up via `overrides`; this is for rows that were
    // already in the database before the import.
    for (i in 0 until rules.length()) rules.getJSONObject(i).let { q.applyRule(it.getString("c"), it.getString("k")) }
    return stored to rules.length()
}
