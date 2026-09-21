package co.raseed.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Expect(val type: String, val amount: Double, val currency: String, val merchant: String?, val category: String)

/** One line of fixtures/corpus.jsonl. `expect == null` = must NOT become a transaction. */
@Serializable
data class Row(val id: Int, val source: String, val text: String, val expect: Expect?)

private val json = Json { ignoreUnknownKeys = true }

/** The 174-message golden corpus labeled during the spike (docs/spike/RESULT.md). */
val corpus: List<Row> by lazy {
    File("../fixtures/corpus.jsonl").readLines().filter { it.isNotBlank() }.map { json.decodeFromString<Row>(it) }
}
