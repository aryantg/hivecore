package com.hivecore.node.vision

import com.hivecore.engine.pixels.Png
import com.hivecore.engine.pixels.ImageCodec
import com.hivecore.engine.pixels.Img
import com.hivecore.engine.pixels.Pixels
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

// The only Android code in this package: decode JPEG/PNG with EXIF orientation applied, encode JPEG. PNG encoding
// uses the pure [Png] writer so PNG outputs are byte-identical on every phone (§4A.3 IMG_PREPROC: byte-exact).
// (androidx.exifinterface is not a dependency, so the framework ExifInterface is used.)

class ImageIo(
    /** larger images are subsampled at decode time (power-of-two inSampleSize) to bound memory */
    private val maxDecodeSide: Int = 4096,
) : ImageCodec {

    override fun decode(file: File): Img {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image: ${file.name}" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDecodeSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(file.path, opts) ?: throw IllegalArgumentException("decode failed: ${file.name}")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val img = Img(bmp.width, bmp.height, px)
        bmp.recycle()
        val orientation = runCatching {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return Pixels.applyExif(img, orientation)
    }

    override fun encode(img: Img, format: String, quality: Int): ByteArray {
        if (format.equals("png", ignoreCase = true)) return Png.encode(img)
        val bmp = Bitmap.createBitmap(img.px, img.w, img.h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        bmp.recycle()
        return out.toByteArray()
    }
}
