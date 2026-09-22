package app.tidybox

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ExportTest {
    private val plain = """{"version":1,"messages":[{"s":"AlRajhiBank","b":"شراء\nمبلغ:SAR 5.00","t":1}]}""".toByteArray()

    @Test
    fun roundTrip() = assertContentEquals(plain, open(seal(plain, "correct horse".toCharArray()), "correct horse".toCharArray()))

    @Test
    fun wrongPassphraseFails() {
        val blob = seal(plain, "correct horse".toCharArray())
        assertFailsWith<Exception> { open(blob, "wrong".toCharArray()) }
    }

    @Test
    fun tamperFails() {
        val blob = seal(plain, "correct horse".toCharArray())
        blob[blob.size - 1] = (blob.last().toInt() xor 1).toByte()
        assertFailsWith<Exception> { open(blob, "correct horse".toCharArray()) }
    }

    @Test
    fun notAnExportFails() = run { assertFailsWith<IllegalArgumentException> { open("hello world, definitely not a tidybox file".toByteArray(), "x".toCharArray()) }; Unit }
}
