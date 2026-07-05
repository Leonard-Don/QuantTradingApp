package io.github.leonarddon.quanttrading.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {

    @Test
    fun verify_acceptsTheOriginalPassword() {
        val stored = PasswordHasher.hash("passw0rd")
        assertTrue(PasswordHasher.verify("passw0rd", stored))
    }

    @Test
    fun verify_rejectsAWrongPassword() {
        val stored = PasswordHasher.hash("passw0rd")
        assertFalse(PasswordHasher.verify("wrong-password", stored))
    }

    @Test
    fun hash_isSaltedSoEachCallDiffersButBothStillVerify() {
        val first = PasswordHasher.hash("passw0rd")
        val second = PasswordHasher.hash("passw0rd")
        assertNotEquals(first, second)
        assertTrue(PasswordHasher.verify("passw0rd", first))
        assertTrue(PasswordHasher.verify("passw0rd", second))
    }

    @Test
    fun hash_storesSaltAndDigestAsHexAndNeverThePlaintext() {
        val stored = PasswordHasher.hash("passw0rd")
        val parts = stored.split(":")
        assertEquals(2, parts.size)
        assertEquals(32, parts[0].length) // 16-byte random salt
        assertEquals(64, parts[1].length) // 32-byte PBKDF2 digest
        assertFalse(stored.contains("passw0rd"))
        assertTrue(parts.all { part -> part.all { it in "0123456789abcdef" } })
    }

    @Test
    fun verify_rejectsMalformedStoredValues() {
        assertFalse(PasswordHasher.verify("passw0rd", ""))
        assertFalse(PasswordHasher.verify("passw0rd", "no-colon-here"))
        assertFalse(PasswordHasher.verify("passw0rd", "nothex:nothex"))
        assertFalse(PasswordHasher.verify("passw0rd", "abcd:"))
    }

    @Test
    fun verify_rejectsATamperedDigest() {
        val stored = PasswordHasher.hash("passw0rd")
        val (salt, digest) = stored.split(":")
        val lastChar = digest.last()
        val tampered = digest.dropLast(1) + if (lastChar == '0') '1' else '0'
        assertFalse(PasswordHasher.verify("passw0rd", "$salt:$tampered"))
    }
}
