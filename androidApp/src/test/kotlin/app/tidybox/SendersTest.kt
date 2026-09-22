package app.tidybox

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendersTest {
    private val allowed = setOf("Riyad Bank", "CIB", "VF-Cash")

    @Test fun `case spaces and dashes are presentation`() {
        assertTrue(Senders.allows(allowed, "RiyadBank"))
        assertTrue(Senders.allows(allowed, "riyad bank "))
        assertTrue(Senders.allows(allowed, "VFCash"))
    }

    @Test fun `a prefix is not a match — CIB must not admit CIB OTP`() {
        assertFalse(Senders.allows(allowed, "CIB OTP"))
        assertFalse(Senders.allows(allowed, "CIB Loyalty"))
        assertFalse(Senders.allows(allowed, null))
    }
}
