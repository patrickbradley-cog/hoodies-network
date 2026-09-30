package com.gap.hoodies_network.crypto

import com.gap.hoodies_network.keystore.CacheKeyManager
import java.security.InvalidAlgorithmParameterException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM primitive used for the encrypted response cache and the persistent cookie store.
 *
 * On-disk format v1 (must stay readable by every library version):
 * - transformation `AES/GCM/NoPadding`, 128-bit authentication tag appended to the ciphertext
 * - 12-byte (96-bit) IV from [SecureRandom], stored Base64-encoded next to the ciphertext
 * - no associated data
 * - key: AndroidKeyStore alias `HoodiesNetworkCacheKey`, see [CacheKeyManager]
 */
internal object AesGcm {
    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val TAG_LENGTH_BITS = 128
    const val IV_LENGTH_BYTES = 12

    private val secureRandom = SecureRandom()

    /**
     * Encrypts or decrypts [input] with [iv].
     *
     * @param cipherMode [Cipher.ENCRYPT_MODE] or [Cipher.DECRYPT_MODE]
     * @throws InvalidAlgorithmParameterException if [iv] is not [IV_LENGTH_BYTES] long
     * @throws javax.crypto.AEADBadTagException if the ciphertext or IV was modified or the key changed
     */
    fun runAES(input: ByteArray, iv: ByteArray, cipherMode: Int, key: SecretKey = CacheKeyManager.getKey()): ByteArray {
        if (iv.size != IV_LENGTH_BYTES)
            throw InvalidAlgorithmParameterException("AES-GCM IV must be $IV_LENGTH_BYTES bytes, was ${iv.size}")

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(cipherMode, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(input)
    }

    /**
     * Returns a fresh [IV_LENGTH_BYTES]-byte (96-bit) IV from [SecureRandom].
     * Never reuse an IV with the same key: GCM loses confidentiality and integrity on IV reuse.
     */
    fun genIV(): ByteArray {
        val ivBytes = ByteArray(IV_LENGTH_BYTES)
        secureRandom.nextBytes(ivBytes)
        return ivBytes
    }
}
