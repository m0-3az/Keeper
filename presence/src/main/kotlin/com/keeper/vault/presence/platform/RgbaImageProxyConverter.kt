package com.keeper.vault.presence.platform

import androidx.camera.core.ImageProxy
import com.keeper.vault.contracts.platform.KeeperException
import com.keeper.vault.contracts.presence.FaceImage
import java.nio.ByteBuffer

/**
 * Copies a CameraX RGBA_8888 [ImageProxy] into the contract frame type.
 *
 * CameraX documents its RGBA plane as A,R,G,B per pixel; [FaceImage] uses R,G,B,A. The copy is
 * mandatory because CameraX recycles the underlying buffer after the analyzer returns.
 */
internal object RgbaImageProxyConverter {
    fun convert(image: ImageProxy): FaceImage {
        if (image.format != android.graphics.PixelFormat.RGBA_8888) {
            throw KeeperException.Presence("Camera frame is not RGBA_8888")
        }
        val plane = image.planes.firstOrNull()
            ?: throw KeeperException.Presence("Camera frame has no pixel plane")
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (width <= 0 || height <= 0 || pixelStride <= 0) {
            throw KeeperException.Presence("Camera frame has invalid dimensions")
        }
        val source: ByteBuffer = plane.buffer
        val base = source.position()
        val rgba = ByteArray(width * height * 4)
        var output = 0
        for (y in 0 until height) {
            val row = base + y * rowStride
            for (x in 0 until width) {
                val pixel = row + x * pixelStride
                if (pixel + 3 >= source.limit()) {
                    throw KeeperException.Presence("Camera frame row exceeds its buffer")
                }
                val alpha = source.get(pixel).toInt() and 0xFF
                val red = source.get(pixel + 1).toInt() and 0xFF
                val green = source.get(pixel + 2).toInt() and 0xFF
                val blue = source.get(pixel + 3).toInt() and 0xFF
                rgba[output] = red.toByte()
                rgba[output + 1] = green.toByte()
                rgba[output + 2] = blue.toByte()
                rgba[output + 3] = alpha.toByte()
                output += 4
            }
        }
        return FaceImageBitmap.fromRgba(
            width = width,
            height = height,
            rotationDegrees = image.imageInfo.rotationDegrees,
            rgba = rgba,
        )
    }
}
