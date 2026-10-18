package dev.playday3008.llmhealthbridge.send

import android.graphics.Bitmap
import android.graphics.ImageDecoder

import kotlin.math.max
import kotlin.math.roundToInt

import java.io.File
import java.io.IOException

/** Decodes a photo and writes a metadata-free, orientation-corrected, downscaled JPEG. */
internal object PhotoReencoder {
    private const val MAX_EDGE = 2048
    private const val QUALITY = 90

    /** Returns true when [out] was written; false when [src] cannot be decoded or [out] written. */
    fun reencode(src: File, out: File): Boolean = try {
        val bitmap = decode(src)
        try {
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        } finally {
            bitmap.recycle()
        }
    } catch (_: IOException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private fun scaleFor(width: Int, height: Int): Float {
        val longEdge = max(width, height)
        return if (longEdge > MAX_EDGE) MAX_EDGE.toFloat() / longEdge else 1f
    }

    /** ImageDecoder applies the EXIF orientation itself. */
    private fun decode(src: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(src)) { decoder, info, _ ->
            val scale = scaleFor(info.size.width, info.size.height)
            if (scale < 1f) {
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1)
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
}
