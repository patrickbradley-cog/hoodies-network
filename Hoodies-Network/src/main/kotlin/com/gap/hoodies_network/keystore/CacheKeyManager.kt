package com.gap.hoodies_network.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Owns the AES key that encrypts the response cache and the persistent cookie store.
 *
 * The key lives in the AndroidKeyStore under a fixed alias, is non-exportable, and is created on first use.
 * Changing the alias or deleting the key makes every previously encrypted row unreadable.
 */
class CacheKeyManager {
    companion object {
        internal const val KEY_ALIAS = "HoodiesNetworkCacheKey"
        internal const val KEY_SIZE_BITS = 256
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        private var key: SecretKey? = null

        /**
         * Returns the cache key, generating it in the AndroidKeyStore if it does not exist yet.
         * The key is cached in memory after the first call.
         */
        @Synchronized
        fun getKey(): SecretKey {
            key?.let { return it }

            val keystore = loadKeyStore()
            if (!keystore.containsAlias(KEY_ALIAS))
                generateNewKey()

            val secretKey = keystore.getKey(KEY_ALIAS, null) as SecretKey
            key = secretKey
            return secretKey
        }

        /**
         * Drops the in-memory copy so the next [getKey] reads the AndroidKeyStore again.
         */
        @VisibleForTesting
        @Synchronized
        internal fun clearCachedKey() {
            key = null
        }

        private fun loadKeyStore(): KeyStore =
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        /**
         * Generates the AES-256-GCM key.
         *
         * Randomized encryption stays disabled because callers supply their own 12-byte IV
         * (see [com.gap.hoodies_network.crypto.AesGcm.genIV]); keys created before the key size was set explicitly
         * used the platform default and remain valid.
         */
        private fun generateNewKey() {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false)
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }
}
