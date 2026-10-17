package com.youki.dex.utils

import android.app.WallpaperManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import com.youki.dex.livewallpaper.data.WallpaperPreferences
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

object BlurUtils {

    /**
     * StackBlur algorithm by Mario Klingemann.
     * High performance, zero native dependencies, compatible with all Android versions (Android 10+).
     */
    fun fastBlur(sentBitmap: Bitmap, radius: Int): Bitmap? {
        if (radius < 1) return null
        val w = sentBitmap.width
        val h = sentBitmap.height
        if (w <= 0 || h <= 0) return null

        val bitmap = try {
            sentBitmap.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: Throwable) {
            return null
        }

        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (idx in 0 until 256 * divsum) {
            dv[idx] = idx / divsum
        }

        yw = 0
        yi = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        for (curY in 0 until h) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            for (curR in -radius..radius) {
                p = pix[yi + minOf(wm, max(curR, 0))]
                sir = stack[curR + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - Math.abs(curR)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (curR > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
            }
            stackpointer = radius

            for (curX in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curY == 0) {
                    vmin[curX] = minOf(curX + radius + 1, wm)
                }
                p = pix[yw + vmin[curX]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
            }
            yw += w
        }

        for (curX in 0 until w) {
            rinsum = 0
            ginsum = 0
            binsum = 0
            routsum = 0
            goutsum = 0
            boutsum = 0
            rsum = 0
            gsum = 0
            bsum = 0
            yp = -radius * w
            for (curR in -radius..radius) {
                yi = max(0, yp) + curX
                sir = stack[curR + radius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - Math.abs(curR)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (curR > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (curR < hm) {
                    yp += w
                }
            }
            yi = curX
            stackpointer = radius
            for (curY in 0 until h) {
                pix[yi] = (-0x1000000 and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (curX == 0) {
                    vmin[curY] = minOf(curY + r1, hm) * w
                }
                p = curX + vmin[curY]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }
}

/**
 * Manages sampling and caching the active wallpaper in a blurred form for dock pills.
 */
object WallpaperBlurManager {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var blurredWallpaper: Bitmap? = null
        private set

    @Volatile
    var screenWidth: Int = 1920
        private set

    @Volatile
    var screenHeight: Int = 1080
        private set

    fun updateDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            screenWidth = width
            screenHeight = height
        }
    }

    fun updateWallpaperBlur(context: Context, onComplete: (() -> Unit)? = null) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                val (path, _) = WallpaperPreferences.getActiveWallpaper(appContext)
                var rawBitmap: Bitmap? = null

                if (!path.isNullOrBlank()) {
                    try {
                        val uri = Uri.parse(path)
                        rawBitmap = if (uri.scheme == "file" || uri.scheme == null) {
                            val f = File(uri.path ?: path)
                            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
                        } else {
                            appContext.contentResolver.openInputStream(uri)?.use {
                                BitmapFactory.decodeStream(it)
                            }
                        }
                    } catch (e: Throwable) {}
                }

                if (rawBitmap == null) {
                    try {
                        val wm = WallpaperManager.getInstance(appContext)
                        val d = wm.drawable
                        if (d is BitmapDrawable) {
                            rawBitmap = d.bitmap
                        }
                    } catch (e: Throwable) {}
                }

                if (rawBitmap != null) {
                    // Downsample to ~1/8 of screen size for instant fast blur (2ms)
                    val targetW = (screenWidth / 8).coerceAtLeast(120)
                    val targetH = (screenHeight / 8).coerceAtLeast(80)
                    val scaled = Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true)
                    val blurred = BlurUtils.fastBlur(scaled, 12) ?: scaled
                    blurredWallpaper = blurred
                }
            } catch (e: Throwable) {
                // Ignore decoding errors safely
            }

            mainHandler.post {
                onComplete?.invoke()
            }
        }
    }
}

/**
 * A composite drawable that draws:
 * 1. Sampled real wallpaper blur mapped to the view's screen position (Android 10+ compatible)
 * 2. Translucent bubble tint color
 * 3. 1dp frosted glass highlight stroke
 */
class FrostedPillDrawable(
    private val targetView: View,
    var cornerRadius: Float,
    var bubbleColor: Int,
    var strokeColor: Int,
    var enableBlur: Boolean = true
) : Drawable() {

    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val colorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val boundsF = RectF()
    private val strokeBoundsF = RectF()
    private val loc = IntArray(2)
    private val shaderMatrix = Matrix()

    private var currentShaderBitmap: Bitmap? = null
    private var bitmapShader: BitmapShader? = null

    override fun draw(canvas: Canvas) {
        boundsF.set(bounds)
        if (boundsF.isEmpty) return

        val strokeWidth = strokePaint.strokeWidth
        val halfStroke = strokeWidth / 2f
        strokeBoundsF.set(
            boundsF.left + halfStroke,
            boundsF.top + halfStroke,
            boundsF.right - halfStroke,
            boundsF.bottom - halfStroke
        )

        // 1. Wallpaper sampling blur (Android 10+)
        val blurredBmp = WallpaperBlurManager.blurredWallpaper
        if (enableBlur && blurredBmp != null && !blurredBmp.isRecycled) {
            if (currentShaderBitmap !== blurredBmp) {
                currentShaderBitmap = blurredBmp
                bitmapShader = BitmapShader(blurredBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                blurPaint.shader = bitmapShader
            }

            targetView.getLocationOnScreen(loc)
            val screenX = loc[0].toFloat()
            val screenY = loc[1].toFloat()

            val sw = WallpaperBlurManager.screenWidth.toFloat()
            val sh = WallpaperBlurManager.screenHeight.toFloat()
            val bw = blurredBmp.width.toFloat()
            val bh = blurredBmp.height.toFloat()

            if (sw > 0 && sh > 0 && bw > 0 && bh > 0) {
                shaderMatrix.reset()
                shaderMatrix.postScale(sw / bw, sh / bh)
                shaderMatrix.postTranslate(-screenX, -screenY)
                bitmapShader?.setLocalMatrix(shaderMatrix)
            }

            canvas.drawRoundRect(boundsF, cornerRadius, cornerRadius, blurPaint)
        }

        // 2. Translucent bubble tint
        colorPaint.color = bubbleColor
        canvas.drawRoundRect(boundsF, cornerRadius, cornerRadius, colorPaint)

        // 3. Frosted highlight stroke
        if (strokeWidth > 0 && Color.alpha(strokeColor) > 0) {
            strokePaint.color = strokeColor
            canvas.drawRoundRect(strokeBoundsF, cornerRadius - halfStroke, cornerRadius - halfStroke, strokePaint)
        }
    }

    fun updateStyle(color: Int, stroke: Int, strokeWidthPx: Float, radius: Float, blur: Boolean) {
        bubbleColor = color
        strokeColor = stroke
        strokePaint.strokeWidth = strokeWidthPx
        cornerRadius = radius
        enableBlur = blur
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        colorPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        colorPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
