package com.claudemobile.core.data.credentials

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.claudemobile.core.common.AppError
import com.claudemobile.core.common.ErrorCode
import com.claudemobile.core.domain.repository.CredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.InvalidKeyException
import java.security.KeyStoreException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CredentialStore] implementation backed by EncryptedSharedPreferences
 * with an AES256-GCM master key stored in the Android Keystore.
 *
 * Handles Keystore unavailability (e.g., key invalidation after device unlock change)
 * by throwing a recoverable [CredentialStoreException].
 */
@Singleton
public class CredentialStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : CredentialStore {

    internal companion object {
        const val PREFS_FILE_NAME = "claude_mobile_credentials"
        const val KEY_API_KEY = "anthropic_api_key"
        const val MASK_VISIBLE_SUFFIX_LENGTH = 4
        const val MASK_CHAR = '*'
        const val MASK_MIN_LENGTH = 8
    }

    /**
     * Cached masked key value. Updated on set/delete to avoid decrypting
     * the full key just for display purposes. Lazily initialized on first access.
     */
    @Volatile
    private var cachedMaskedKey: String? = null

    @Volatile
    private var maskedKeyInitialized: Boolean = false

    private val encryptedPrefs: SharedPreferences by lazy {
        createEncryptedPreferences()
    }

    override suspend fun getApiKey(): String? {
        return try {
            encryptedPrefs.getString(KEY_API_KEY, null)
        } catch (e: Exception) {
            handleKeystoreException(e)
        }
    }

    override suspend fun setApiKey(key: String) {
        try {
            encryptedPrefs.edit().putString(KEY_API_KEY, key).apply()
            cachedMaskedKey = computeMaskedKey(key)
            maskedKeyInitialized = true
        } catch (e: Exception) {
            handleKeystoreException(e)
        }
    }

    override suspend fun deleteApiKey() {
        try {
            encryptedPrefs.edit().remove(KEY_API_KEY).apply()
            cachedMaskedKey = null
            maskedKeyInitialized = true
        } catch (e: Exception) {
            handleKeystoreException(e)
        }
    }

    override suspend fun hasApiKey(): Boolean {
        return try {
            encryptedPrefs.contains(KEY_API_KEY)
        } catch (e: Exception) {
            handleKeystoreException(e)
        }
    }

    override fun getMaskedApiKey(): String? {
        if (!maskedKeyInitialized) {
            cachedMaskedKey = try {
                val key = encryptedPrefs.getString(KEY_API_KEY, null)
                key?.let { computeMaskedKey(it) }
            } catch (_: Exception) {
                null
            }
            maskedKeyInitialized = true
        }
        return cachedMaskedKey
    }

    private fun createEncryptedPreferences(): SharedPreferences {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            when (e) {
                is KeyStoreException,
                is InvalidKeyException,
                is KeyPermanentlyInvalidatedException -> {
                    throw CredentialStoreException(
                        appError = AppError(
                            message = "Android Keystore is unavailable. Please re-enter your credentials.",
                            code = ErrorCode.KEYSTORE_ERROR,
                            cause = e,
                        ),
                    )
                }
                else -> throw e
            }
        }
    }

    private fun <T> handleKeystoreException(e: Exception): T {
        when (e) {
            is KeyStoreException,
            is InvalidKeyException,
            is KeyPermanentlyInvalidatedException,
            is CredentialStoreException -> {
                throw CredentialStoreException(
                    appError = AppError(
                        message = "Android Keystore is unavailable. Please re-enter your credentials.",
                        code = ErrorCode.KEYSTORE_ERROR,
                        cause = e,
                    ),
                )
            }
            else -> throw e
        }
    }
}

/**
 * Computes a masked representation of an API key.
 *
 * Rules:
 * - Shows at most the last [CredentialStoreImpl.MASK_VISIBLE_SUFFIX_LENGTH] characters
 * - Replaces the rest with [CredentialStoreImpl.MASK_CHAR]
 * - The masked result must NEVER equal the original key (prevents full key exposure for short keys)
 * - For keys with 4 or fewer characters, only a portion is shown to avoid revealing the full key
 */
internal fun computeMaskedKey(key: String): String {
    if (key.isEmpty()) return ""

    val visibleCount = when {
        // For very short keys (≤4 chars), show fewer characters to avoid revealing the full key
        key.length <= CredentialStoreImpl.MASK_VISIBLE_SUFFIX_LENGTH -> {
            // Show at most half the characters (rounded down), minimum 0
            (key.length / 2).coerceAtMost(CredentialStoreImpl.MASK_VISIBLE_SUFFIX_LENGTH)
        }
        else -> CredentialStoreImpl.MASK_VISIBLE_SUFFIX_LENGTH
    }

    val maskedCount = (key.length - visibleCount).coerceAtLeast(CredentialStoreImpl.MASK_MIN_LENGTH)
    val maskedPortion = CredentialStoreImpl.MASK_CHAR.toString().repeat(maskedCount)
    val visiblePortion = key.takeLast(visibleCount)

    return maskedPortion + visiblePortion
}

/**
 * Recoverable exception indicating that the Android Keystore is unavailable,
 * typically due to key invalidation after a device unlock change.
 * Callers should prompt the user to re-enter their credentials.
 */
public class CredentialStoreException(
    public val appError: AppError,
) : RuntimeException(appError.message, appError.cause)
