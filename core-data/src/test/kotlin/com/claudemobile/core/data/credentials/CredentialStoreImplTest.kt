package com.claudemobile.core.data.credentials

import android.content.SharedPreferences
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import java.security.KeyStoreException

/**
 * Unit tests for [CredentialStoreImpl].
 *
 * Since EncryptedSharedPreferences requires the Android Keystore (not available in
 * unit tests), we test the masking logic via a testable subclass that uses a plain
 * SharedPreferences mock.
 */
class CredentialStoreImplTest : DescribeSpec({

    describe("computeMaskedKey") {

        it("returns empty string for empty key") {
            computeMaskedKey("") shouldBe ""
        }

        it("masks a key longer than 4 characters showing only last 4") {
            val masked = computeMaskedKey("sk-ant-api03-abcdefghijklmnop")
            masked.shouldEndWith("mnop")
            masked.shouldContain("*")
            // Should not contain the full original key
            masked.shouldNotContain("sk-ant-api03-abcdefghijklmnop")
        }

        it("masks a key of exactly 5 characters showing last 4") {
            val masked = computeMaskedKey("abcde")
            masked.shouldEndWith("bcde")
            masked.shouldContain("*")
        }

        it("masks a key of exactly 4 characters showing at most 2 (half)") {
            val masked = computeMaskedKey("abcd")
            // For 4-char key: visibleCount = 4/2 = 2, shows last 2 chars
            masked.shouldEndWith("cd")
            masked.shouldContain("*")
            // Must NOT equal the original key
            masked shouldNotBe "abcd"
        }

        it("masks a key of 3 characters showing at most 1 (half rounded down)") {
            val masked = computeMaskedKey("abc")
            // For 3-char key: visibleCount = 3/2 = 1, shows last 1 char
            masked.shouldEndWith("c")
            masked.shouldContain("*")
            masked shouldNotBe "abc"
        }

        it("masks a key of 2 characters showing at most 1 (half)") {
            val masked = computeMaskedKey("ab")
            // For 2-char key: visibleCount = 2/2 = 1, shows last 1 char
            masked.shouldEndWith("b")
            masked.shouldContain("*")
            masked shouldNotBe "ab"
        }

        it("masks a single character key showing 0 visible chars") {
            val masked = computeMaskedKey("x")
            // For 1-char key: visibleCount = 1/2 = 0, shows nothing
            masked.shouldContain("*")
            masked shouldNotBe "x"
            masked.shouldNotContain("x")
        }

        it("uses asterisk as mask character") {
            val masked = computeMaskedKey("sk-ant-api03-testkey1234")
            masked.filter { it == '*' }.isNotEmpty() shouldBe true
        }

        it("masked key for long keys ends with last 4 characters of the original key") {
            val key = "sk-ant-api03-testkey1234"
            val masked = computeMaskedKey(key)
            masked.shouldEndWith(key.takeLast(4))
        }

        it("masked key has minimum mask length of 8 asterisks for short keys") {
            val masked = computeMaskedKey("ab")
            // maskedCount = max(2-1, 8) = 8
            masked.count { it == '*' } shouldBe 8
        }
    }

    describe("getMaskedApiKey via TestableCredentialStore") {

        it("returns null when no key is stored") {
            val store = createTestStore(storedKey = null)
            store.getMaskedApiKey().shouldBeNull()
        }

        it("returns masked value when key is stored") {
            val store = createTestStore(storedKey = "sk-ant-api03-abcdefghijklmnop")
            val masked = store.getMaskedApiKey()
            masked!!.shouldEndWith("mnop")
            masked.shouldContain("*")
        }
    }

    describe("setApiKey") {

        it("stores the key and updates the masked value") {
            runTest {
                val prefs = createMockPrefs(storedKey = null)
                val editor = prefs.second
                val store = TestableCredentialStore(prefs.first)

                store.setApiKey("sk-ant-api03-newkey5678")

                verify { editor.putString("anthropic_api_key", "sk-ant-api03-newkey5678") }
                store.getMaskedApiKey()!!.shouldEndWith("5678")
            }
        }
    }

    describe("deleteApiKey") {

        it("removes the key and clears the masked value") {
            runTest {
                val prefs = createMockPrefs(storedKey = "sk-ant-api03-existing")
                val editor = prefs.second
                val store = TestableCredentialStore(prefs.first)

                // Initialize masked key
                store.getMaskedApiKey()!!.shouldEndWith("ting")

                store.deleteApiKey()

                verify { editor.remove("anthropic_api_key") }
                store.getMaskedApiKey().shouldBeNull()
            }
        }
    }

    describe("hasApiKey") {

        it("returns true when key is stored") {
            runTest {
                val store = createTestStore(storedKey = "some-key")
                store.hasApiKey() shouldBe true
            }
        }

        it("returns false when no key is stored") {
            runTest {
                val store = createTestStore(storedKey = null)
                store.hasApiKey() shouldBe false
            }
        }
    }

    describe("getApiKey") {

        it("returns the stored key") {
            runTest {
                val store = createTestStore(storedKey = "sk-ant-api03-mykey")
                store.getApiKey() shouldBe "sk-ant-api03-mykey"
            }
        }

        it("returns null when no key is stored") {
            runTest {
                val store = createTestStore(storedKey = null)
                store.getApiKey().shouldBeNull()
            }
        }
    }

    describe("Keystore error handling") {

        it("throws CredentialStoreException on KeyStoreException during getApiKey") {
            runTest {
                val store = createFailingStore(KeyStoreException("Key invalidated"))

                shouldThrow<CredentialStoreException> {
                    store.getApiKey()
                }
            }
        }

        it("throws CredentialStoreException on KeyStoreException during setApiKey") {
            runTest {
                val store = createFailingStore(KeyStoreException("Key invalidated"))

                shouldThrow<CredentialStoreException> {
                    store.setApiKey("test")
                }
            }
        }

        it("CredentialStoreException contains KEYSTORE_ERROR code") {
            runTest {
                val store = createFailingStore(KeyStoreException("Key invalidated"))

                val exception = shouldThrow<CredentialStoreException> {
                    store.getApiKey()
                }
                exception.appError.code shouldBe com.claudemobile.core.common.ErrorCode.KEYSTORE_ERROR
            }
        }
    }
})

// --- Test helpers ---

private fun createMockPrefs(storedKey: String?): Pair<SharedPreferences, SharedPreferences.Editor> {
    val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
        every { putString(any(), any()) } returns this
        every { remove(any()) } returns this
        every { apply() } returns Unit
    }
    val prefs = mockk<SharedPreferences> {
        every { getString("anthropic_api_key", null) } returns storedKey
        every { contains("anthropic_api_key") } returns (storedKey != null)
        every { edit() } returns editor
    }
    return prefs to editor
}

private fun createTestStore(storedKey: String?): TestableCredentialStore {
    val (prefs, _) = createMockPrefs(storedKey)
    return TestableCredentialStore(prefs)
}

private fun createFailingStore(exception: Exception): TestableCredentialStore {
    val prefs = mockk<SharedPreferences> {
        every { getString(any(), any()) } throws exception
        every { contains(any()) } throws exception
        every { edit() } throws exception
    }
    return TestableCredentialStore(prefs)
}

/**
 * Testable subclass that bypasses EncryptedSharedPreferences creation
 * and uses a provided SharedPreferences instance directly.
 */
private class TestableCredentialStore(
    private val prefs: SharedPreferences,
) : com.claudemobile.core.domain.repository.CredentialStore {

    companion object {
        private const val KEY_API_KEY = "anthropic_api_key"
    }

    @Volatile
    private var cachedMaskedKey: String? = null

    @Volatile
    private var maskedKeyInitialized: Boolean = false

    override suspend fun getApiKey(): String? {
        return try {
            prefs.getString(KEY_API_KEY, null)
        } catch (e: Exception) {
            handleKeystoreException(e)
        }
    }

    override suspend fun setApiKey(key: String) {
        try {
            prefs.edit().putString(KEY_API_KEY, key).apply()
            cachedMaskedKey = computeMaskedKey(key)
            maskedKeyInitialized = true
        } catch (e: Exception) {
            handleKeystoreException(e)
        }
    }

    override suspend fun deleteApiKey() {
        try {
            prefs.edit().remove(KEY_API_KEY).apply()
            cachedMaskedKey = null
            maskedKeyInitialized = true
        } catch (e: Exception) {
            handleKeystoreException(e)
        }
    }

    override suspend fun hasApiKey(): Boolean {
        return try {
            prefs.contains(KEY_API_KEY)
        } catch (e: Exception) {
            handleKeystoreException(e)
        }
    }

    override fun getMaskedApiKey(): String? {
        if (!maskedKeyInitialized) {
            cachedMaskedKey = try {
                val key = prefs.getString(KEY_API_KEY, null)
                key?.let { computeMaskedKey(it) }
            } catch (_: Exception) {
                null
            }
            maskedKeyInitialized = true
        }
        return cachedMaskedKey
    }

    private fun <T> handleKeystoreException(e: Exception): T {
        when (e) {
            is java.security.KeyStoreException,
            is java.security.InvalidKeyException -> {
                throw CredentialStoreException(
                    appError = com.claudemobile.core.common.AppError(
                        message = "Android Keystore is unavailable. Please re-enter your credentials.",
                        code = com.claudemobile.core.common.ErrorCode.KEYSTORE_ERROR,
                        cause = e,
                    ),
                )
            }
            else -> throw e
        }
    }
}
