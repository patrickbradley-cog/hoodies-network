@file:Suppress("UNCHECKED_CAST")

package com.gap.hoodies_network.request

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.ImageView.ScaleType
import com.gap.hoodies_network.cache.EncryptedCache
import com.gap.hoodies_network.connection.queue.RequestQueue
import com.gap.hoodies_network.core.HoodiesNetworkError
import com.gap.hoodies_network.core.OUT_OF_MEMORY_ERROR_CODE
import com.gap.hoodies_network.core.Response
import java.net.CookieManager
import kotlin.math.min


/**
 * `GET` request whose response body is decoded into a [Bitmap], optionally down-sampled and scaled
 * to fit [maxWidth] x [maxHeight].
 *
 * @param url absolute URL of the image; must not be `null`.
 * @param maxWidth maximum width of the decoded bitmap, or `0` for no limit.
 * @param maxHeight maximum height of the decoded bitmap, or `0` for no limit.
 * @param scaleType how the image is fitted into the maximum bounds.
 * @param decodeConfig bitmap configuration used to decode the image.
 * @param listener listener receiving the decoded bitmap.
 * @param errorListener listener receiving errors, or `null`.
 * @param encryptedCache cache used for this request.
 * @param cookieManager cookie manager, or `null`.
 */
class ImageRequest(
    url: String?,
    private val maxWidth: Int,
    private val maxHeight: Int,
    private val scaleType: ScaleType,
    decodeConfig: Bitmap.Config,
    listener: Response.BitmapResponseListener,
    errorListener: Response.ErrorListener?,
    encryptedCache: EncryptedCache,
    cookieManager: CookieManager?
) : Request<Bitmap?>(
    url!!,
    Method.GET,
    "",
    errorListener,
    encryptedCache,
    cookieManager
) {
    private val bitmapResponseListener: Response.BitmapResponseListener = listener

    private val config: Bitmap.Config = decodeConfig
    private var cachingEnabled = false
    private lateinit var context: Context

    /** Creates an image request that fits the image with [ScaleType.CENTER_INSIDE]. */
    constructor(
        url: String?,
        maxWidth: Int,
        maxHeight: Int,
        config: Bitmap.Config,
        listener: Response.BitmapResponseListener,
        errorListener: Response.ErrorListener?,
        encryptedCache: EncryptedCache,
        cookieManager: CookieManager?
    ) : this(
        url,
        maxWidth,
        maxHeight,
        ScaleType.CENTER_INSIDE,
        config,
        listener,
        errorListener,
        encryptedCache,
        cookieManager
    )

    /**
     * Decodes the response body into a bitmap and stores it with [Response.setBitmap]. Decoding is
     * serialized across all image requests to bound memory usage.
     *
     * @throws HoodiesNetworkError with [OUT_OF_MEMORY_ERROR_CODE] when the bitmap cannot be allocated.
     */
    @Throws(HoodiesNetworkError::class)
    override fun parseNetworkResponse(response: Response<Any>?): Response<Any>? {
        synchronized(sDecodeLock) {
            return try {
                retrieveBitmapFromResponse(response)
            } catch (e: OutOfMemoryError) {
                Log.e("parseNetworkResponse", e.toString())
                throw HoodiesNetworkError(e.message, OUT_OF_MEMORY_ERROR_CODE)
            }
        }
    }

    /** Passes the decoded bitmap of a non-`null` [response] to the bitmap listener. */
    override fun deliverResponse(response: Response<Any>?) {
        if (response != null) {
            bitmapResponseListener.onResponse(response.getBitmap())
        }
    }


    private fun retrieveBitmapFromResponse(response: Response<Any>?): Response<Any>? {
        val data: ByteArray? = response?.getData()
        val bitmap = resizeBitmap(data)
        response?.setBitmap(bitmap)
        return response
    }

    /**
     * Computes one target dimension of the decoded bitmap.
     *
     * @param maxPrimary maximum size of the computed dimension, or `0` for no limit.
     * @param maxSecondary maximum size of the other dimension, or `0` for no limit.
     * @param actualPrimary actual size of the computed dimension.
     * @param actualSecondary actual size of the other dimension.
     * @param scaleType how the image is fitted into the maximum bounds.
     * @return the target size of the primary dimension.
     */
    fun getResizedDimension(
        maxPrimary: Int,
        maxSecondary: Int,
        actualPrimary: Int,
        actualSecondary: Int,
        scaleType: ScaleType
    ): Int { //NO SONAR
        // If ScaleType.FIT_XY fill the whole rectangle, ignore ratio.
        // If primary is unspecified, scale primary to match secondary's scaling ratio.
        return if (scaleType == ScaleType.FIT_XY) {
            if (maxPrimary == 0) {
                actualPrimary
            } else maxPrimary
        } else getResizedValue(maxPrimary, maxSecondary, actualPrimary, actualSecondary, scaleType)
    }

    /**
     * Enqueues [request] on [requestQueue].
     *
     * @param requestQueue queue that executes the request.
     * @param request the request to enqueue.
     */
    fun processRequest(requestQueue: RequestQueue, request: ImageRequest) {
        requestQueue.enqueue(request as Request<Any>)
    }

    private fun resizeBitmap(data: ByteArray?): Bitmap? {
        var bitmap: Bitmap? = null
        if (null != data) {
            val decodeOptions = BitmapFactory.Options()
            //if maxWidth and maxHeight is Zero get Original size of image
            if (maxWidth == 0 && maxHeight == 0) {
                decodeOptions.inPreferredConfig = config
                bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
            } else {
                // If we have to resize this image, first get the natural bounds.
                decodeOptions.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
                val actualWidth = decodeOptions.outWidth
                val actualHeight = decodeOptions.outHeight

                // Then compute the dimensions we would ideally like to decode to.
                val desiredWidth = getResizedDimension(
                    maxWidth, maxHeight, actualWidth, actualHeight, scaleType
                )
                val desiredHeight = getResizedDimension(
                    maxHeight, maxWidth, actualHeight, actualWidth, scaleType
                )

                // Decode to the nearest power of two scaling factor.
                decodeOptions.inJustDecodeBounds = false
                decodeOptions.inSampleSize =
                    findBestSampleSize(actualWidth, actualHeight, desiredWidth, desiredHeight)
                val tempBitmap = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
                bitmap = getResizedBitmap(tempBitmap, desiredWidth, desiredHeight)
            }
        }
        return bitmap
    }

    private fun getResizedBitmap(
        tempBitmap: Bitmap?,
        desiredWidth: Int,
        desiredHeight: Int
    ): Bitmap? {
        // If necessary, scale down to the maximal acceptable size.
        val resizedBitmap: Bitmap?
        if (tempBitmap != null
            && (tempBitmap.width > desiredWidth
                    || tempBitmap.height > desiredHeight)
        ) {
            resizedBitmap = Bitmap.createScaledBitmap(tempBitmap, desiredWidth, desiredHeight, true)
            tempBitmap.recycle()
        } else {
            resizedBitmap = tempBitmap
        }
        return resizedBitmap
    }

    /**
     * Computes the target size of the primary dimension for scale types other than
     * [ScaleType.FIT_XY], preserving the aspect ratio when only one bound is given.
     *
     * @return the target size of the primary dimension.
     * @see getResizedDimension
     */
    fun getResizedValue(
        maxPrimary: Int,
        maxSecondary: Int,
        actualPrimary: Int,
        actualSecondary: Int,
        scaleType: ScaleType
    ): Int {
        if (maxPrimary == 0) {
            val ratio = maxSecondary.toDouble() / actualSecondary.toDouble()
            return (actualPrimary * ratio).toInt()
        }
        return if (maxSecondary == 0) {
            maxPrimary
        } else getResizeValueWithSpecifiedHW(
            maxPrimary,
            maxSecondary,
            actualPrimary,
            actualSecondary,
            scaleType
        )
    }

    /**
     * Computes the target size of the primary dimension when both bounds are given, preserving the
     * aspect ratio. [ScaleType.CENTER_CROP] fills the bounds; other scale types fit inside them.
     *
     * @return the target size of the primary dimension.
     * @see getResizedDimension
     */
    fun getResizeValueWithSpecifiedHW(
        maxPrimary: Int,
        maxSecondary: Int,
        actualPrimary: Int,
        actualSecondary: Int,
        scaleType: ScaleType
    ): Int {
        val ratio = actualSecondary.toDouble() / actualPrimary.toDouble()
        var resized = maxPrimary

        // If ScaleType.CENTER_CROP fill the whole rectangle, preserve aspect ratio.
        if (scaleType == ScaleType.CENTER_CROP) {
            if (resized * ratio < maxSecondary) {
                resized = (maxSecondary / ratio).toInt()
            }
            return resized
        }
        if (resized * ratio > maxSecondary) {
            resized = (maxSecondary / ratio).toInt()
        }
        return resized
    }

    /** Decoding helpers shared by all [ImageRequest]s. */
    companion object {
        private val sDecodeLock = Any()

        /**
         * Returns the largest power-of-two sample size that keeps the decoded image at least as
         * large as the desired size.
         *
         * @return a value suitable for [BitmapFactory.Options.inSampleSize].
         */
        fun findBestSampleSize(
            actualWidth: Int, actualHeight: Int, desiredWidth: Int, desiredHeight: Int
        ): Int {
            val wr = actualWidth.toDouble() / desiredWidth
            val hr = actualHeight.toDouble() / desiredHeight
            val ratio = min(wr, hr)
            var n = 1.0f
            while (n * 2 <= ratio) {
                n *= 2f
            }
            return n.toInt()
        }
    }
}
