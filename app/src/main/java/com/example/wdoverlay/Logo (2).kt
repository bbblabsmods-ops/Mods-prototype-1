package com.example.wdoverlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Base64

/** The BBBlabs logo, embedded in the code so no extra image file is needed. */
object Logo {
    private val DATA = listOf(
        "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",
        "8j8O7gVutW8jqAAAAABJRU5ErkJggg=="
    ).joinToString("")

    /** The logo, or null if the image data can't be read (the app then just skips the logo). */
    val bitmap: Bitmap? by lazy {
        try {
            val bytes = Base64.decode(DATA, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Draws the logo as a faint background.
 * fit = true shows the whole logo centered; fit = false fills the area (cropping the edges).
 */
class LogoBackground(private val fit: Boolean, alphaValue: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = alphaValue }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bmp = Logo.bitmap ?: return
        val sx = b.width().toFloat() / bmp.width
        val sy = b.height().toFloat() / bmp.height
        val s = if (fit) minOf(sx, sy) else maxOf(sx, sy)
        val w = bmp.width * s
        val h = bmp.height * s
        val l = b.left + (b.width() - w) / 2f
        val t = b.top + (b.height() - h) / 2f
        canvas.save()
        canvas.clipRect(b)
        canvas.drawBitmap(bmp, null, RectF(l, t, l + w, t + h), paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
