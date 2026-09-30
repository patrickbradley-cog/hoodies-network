package com.gap.hoodies_network

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gap.hoodies_network.cache.EncryptedCache
import com.gap.hoodies_network.cache.configuration.CacheEnabled
import com.gap.hoodies_network.cache.persistentstorage.CacheDatabase
import com.gap.hoodies_network.cache.persistentstorage.CachedData
import com.gap.hoodies_network.cookies.PersistentCookieJar
import com.gap.hoodies_network.cookies.persistentstorage.EncryptedCookie
import com.gap.hoodies_network.cookies.persistentstorage.EncryptedCookieDatabase
import com.gap.hoodies_network.core.Failure
import com.gap.hoodies_network.core.HoodiesNetworkClient
import com.gap.hoodies_network.core.Success
import com.gap.hoodies_network.crypto.AesGcm
import com.gap.hoodies_network.keystore.CacheKeyManager
import com.gap.hoodies_network.mockwebserver.ServerManager
import com.gap.hoodies_network.testObjects.CallResponse
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpCookie
import java.net.URI
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Proves the v1 on-disk encryption format stays compatible in both directions:
 * rows written by the pre-WS06 code (key generation, IV generation and AES-GCM copied verbatim from main @ b105af9)
 * are decrypted by the current library, and rows written by the current library are decrypted by the pre-WS06 code.
 */
@RunWith(AndroidJUnit4::class)
class CryptoCompatTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cacheDb = Room.databaseBuilder(context, CacheDatabase::class.java, "HoodiesNetworkCache").build()
    private val cookieDb = Room.databaseBuilder(context, EncryptedCookieDatabase::class.java, COOKIE_INSTANCE).build()
    private val client = HoodiesNetworkClient.Builder().baseUrl("http://localhost:6969/").build()

    @Before
    fun setUp() {
        ServerManager.setup(context)
        cacheDb.clearAllTables()
        cookieDb.clearAllTables()
    }

    @After
    fun tearDown() {
        ServerManager.stop()
        cacheDb.clearAllTables()
        cookieDb.clearAllTables()
        cacheDb.close()
        cookieDb.close()
    }

    @Test
    fun cacheWrittenByLegacyCryptoIsReadByCurrentLibrary() {
        runBlocking {
            installLegacyKey()
            val testData = UUID.randomUUID().toString()
            val legacyValue = "\"legacy-$testData\""
            val json = """{"headers":{},"origin":"legacy","data":${Gson().toJson(legacyValue)},"url":"$PATCH_URL"}"""

            val iv = LegacyV1Crypto.genIV()
            cacheDb.cacheDao().insert(
                CachedData(
                    0,
                    PATCH_URL,
                    "\"$testData\"".hashCode(),
                    OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond(),
                    b64(LegacyV1Crypto.runAES(json.encodeToByteArray(), iv, Cipher.ENCRYPT_MODE)),
                    b64(iv)
                )
            )

            when (val result = client.patch<String, CallResponse>("patch", testData, cacheConfiguration = encryptedCache())) {
                is Success -> assertEquals(legacyValue, result.value.data)
                is Failure -> throw result.reason
            }
        }
    }

    @Test
    fun cacheWrittenByCurrentLibraryIsReadByLegacyCrypto() {
        runBlocking {
            resetKey()
            val testData = UUID.randomUUID().toString()

            when (val result = client.patch<String, CallResponse>("patch", testData, cacheConfiguration = encryptedCache())) {
                is Success -> {
                    val bodyHash = "\"$testData\"".hashCode()
                    while (cacheDb.cacheDao().get(PATCH_URL, bodyHash) == null)
                        delay(100)
                    val row = cacheDb.cacheDao().get(PATCH_URL, bodyHash)!!
                    val iv = unb64(row.iv!!)
                    assertEquals(AesGcm.IV_LENGTH_BYTES, iv.size)

                    val plaintext = LegacyV1Crypto.runAES(unb64(row.data), iv, Cipher.DECRYPT_MODE).decodeToString()
                    assertEquals(result.value.data, Gson().fromJson(plaintext, CallResponse::class.java).data)
                }
                is Failure -> throw result.reason
            }
        }
    }

    @Test
    fun cookiesWrittenByLegacyCryptoAreReadByCurrentLibrary() {
        installLegacyKey()
        val cookie = HttpCookie("legacy-${UUID.randomUUID()}", UUID.randomUUID().toString()).apply { version = 0 }
        val cookieJson = Gson().toJson(cookie)
        val iv = LegacyV1Crypto.genIV()
        cookieDb.encryptedCookieDao().insert(
            EncryptedCookie(0, "http://localhost", b64(LegacyV1Crypto.runAES(cookieJson.encodeToByteArray(), iv, Cipher.ENCRYPT_MODE)), b64(iv), cookie.hashCode())
        )

        val row = cookieDb.encryptedCookieDao().getAll().single()
        assertEquals(cookieJson, AesGcm.runAES(unb64(row.cookie!!), unb64(row.iv!!), Cipher.DECRYPT_MODE).decodeToString())

        Log.i(TAG, "legacy cookie plaintext = $cookieJson")
        val cookies = PersistentCookieJar(COOKIE_INSTANCE, context).getCookiesForHost(URI("http://localhost"))
        assertEquals(1, cookies.size)
        assertEquals(cookieJson, Gson().toJson(cookies[0]))
    }

    @Test
    fun cookiesWrittenByCurrentLibraryAreReadByLegacyCrypto() {
        resetKey()
        val cookie = HttpCookie("current-${UUID.randomUUID()}", UUID.randomUUID().toString()).apply { version = 0 }
        PersistentCookieJar(COOKIE_INSTANCE, context).addCookieForHost(URI("http://localhost"), cookie)

        val row = cookieDb.encryptedCookieDao().getAll().single()
        val iv = unb64(row.iv!!)
        assertEquals(AesGcm.IV_LENGTH_BYTES, iv.size)
        assertEquals(Gson().toJson(cookie), LegacyV1Crypto.runAES(unb64(row.cookie!!), iv, Cipher.DECRYPT_MODE).decodeToString())
    }

    @Test
    fun cryptoPackageIsInterchangeableWithEncryptedCache() {
        resetKey()
        val plaintext = UUID.randomUUID().toString().encodeToByteArray()

        val iv = AesGcm.genIV()
        val fromCrypto = AesGcm.runAES(plaintext, iv, Cipher.ENCRYPT_MODE)
        assertArrayEquals(fromCrypto, EncryptedCache.runAES(plaintext, iv, Cipher.ENCRYPT_MODE))
        assertArrayEquals(plaintext, EncryptedCache.runAES(fromCrypto, iv, Cipher.DECRYPT_MODE))

        val legacyIv = EncryptedCache.genIV()
        assertEquals(AesGcm.IV_LENGTH_BYTES, legacyIv.size)
        assertArrayEquals(plaintext, AesGcm.runAES(EncryptedCache.runAES(plaintext, legacyIv, Cipher.ENCRYPT_MODE), legacyIv, Cipher.DECRYPT_MODE))
        assertEquals(plaintext.size + AesGcm.TAG_LENGTH_BITS / 8, fromCrypto.size)

        try {
            AesGcm.runAES(plaintext, ByteArray(16), Cipher.ENCRYPT_MODE)
            fail("16-byte IV must be rejected")
        } catch (expected: InvalidAlgorithmParameterException) {
        }
    }

    @Test
    fun newKeyIsAes256GcmAndLegacyKeyIsReused() {
        resetKey()
        val newKeyInfo = keyInfo(CacheKeyManager.getKey())
        assertEquals(256, newKeyInfo.keySize)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), newKeyInfo.blockModes)
        assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), newKeyInfo.encryptionPaddings)

        installLegacyKey()
        val legacyKeyInfo = keyInfo(CacheKeyManager.getKey())
        Log.i(TAG, "legacy (platform default) key size = ${legacyKeyInfo.keySize}")
        assertEquals(keyInfo(LegacyV1Crypto.key()).keySize, legacyKeyInfo.keySize)
    }

    private fun encryptedCache() = CacheEnabled(encryptionEnabled = true, applicationContext = context)

    private fun resetKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(LegacyV1Crypto.keyAlias)
        CacheKeyManager.clearCachedKey()
    }

    private fun installLegacyKey() {
        resetKey()
        LegacyV1Crypto.generateNewKey()
    }

    private fun keyInfo(key: SecretKey): KeyInfo =
        SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(value: String) = Base64.getDecoder().decode(value)

    /**
     * Verbatim copy of the key generation and AES code in CacheKeyManager / EncryptedCache at main @ b105af9.
     */
    private object LegacyV1Crypto {
        const val keyAlias = "HoodiesNetworkCacheKey"

        fun generateNewKey() {
            val keyGenerator = KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(false)
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }

        fun key(): SecretKey {
            val keystore = KeyStore.getInstance("AndroidKeyStore")
            keystore.load(null)
            val secretKeyEntry = keystore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry
            return secretKeyEntry.secretKey
        }

        fun runAES(input: ByteArray, iv: ByteArray, cipherMode: Int): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(cipherMode, key(), GCMParameterSpec(128, iv))
            return cipher.doFinal(input)
        }

        fun genIV(): ByteArray {
            val r = SecureRandom()
            val ivBytes = ByteArray(12)
            r.nextBytes(ivBytes)

            return ivBytes
        }
    }

    companion object {
        private const val TAG = "CryptoCompatTest"
        private const val PATCH_URL = "http://localhost:6969/patch"
        private const val COOKIE_INSTANCE = "cryptoCompatCookies"
    }
}
