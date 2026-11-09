package com.keeper.vault.presence.platform

import android.graphics.Bitmap
import android.graphics.Matrix
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.presence.FaceImage
import com.keeper.vault.contracts.presence.FaceImageFormat

/**
 * Pixel helpers shared by the ML Kit detector and the MobileFaceNet embedder.
 *
 * [FaceImage] carries RGBA_8888 bytes in R,G,B,A order. Bitmap expects packed ARGB ints, so the
 * conversion is explicit rather than a raw buffer copy. Rotation metadata is applied here so the
 * detector and the embedder always see the same upright coordinate system.
 */
internal object FaceImageBitmap {
    fun toUprightBitmap(image: FaceImage): Bitmap {
        if (image.format != FaceImageFormat.RGBA_8888) {
            throw KeeperException.Presence("Only RGBA_8888 frames are supported by the adapters")
        }
        val expected = image.width * image.height * 4
        if (image.data.size < expected) {
            throw KeeperException.Presence("RGBA frame is smaller than its declared size")
        }
        val pixels = IntArray(image.width * image.height)
        var offset = 0
        for (index in pixels.indices) {
            val red = image.data[offset].toInt() and 0xFF
            val green = image.data[offset + 1].toInt() and 0xFF
            val blue = image.data[offset + 2].toInt() and 0xFF
            val alpha = image.data[offset + 3].toInt() and 0xFF
            pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            offset += 4
        }
        val upright = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        if (image.rotationDegrees == 0) return upright
        return try {
            Bitmap.createBitmap(
                upright,
                0,
                0,
                upright.width,
                upright.height,
                Matrix().apply { postRotate(image.rotationDegrees.toFloat()) },
                true,
            )
        } finally {
            upright.recycle()
        }
    }

    fun fromRgba(
        width: Int,
        height: Int,
        rotationDegrees: Int,
        rgba: ByteArray,
    ): FaceImage = FaceImage(
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        format = FaceImageFormat.RGBA_8888,
        data = rgba,
    )
}
