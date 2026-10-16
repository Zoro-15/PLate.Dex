package com.youki.dex.livewallpaper.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.exoplayer.ExoPlayer
import com.youki.dex.livewallpaper.data.WallpaperConfig
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * WallpaperGLRenderer — the core of the live wallpaper engine.
 *
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │  The flow:                                                              │
 * │   ExoPlayer ─(writes frames)→ SurfaceTexture ─(GL_TEXTURE_EXTERNAL_OES)→ │
 * │   Fragment Shader ─(applies MVP + colors)→ glDrawArrays → the screen    │
 * │                                                                          │
 * │  All of this happens directly on the GPU, so the CPU stays nearly idle │
 * │  = longer battery life.                                                 │
 * └────────────────────────────────────────────────────────────────────────┘
 *
 * This class is deliberately "dumb": it doesn't read from the database itself,
 * it only receives a ready [WallpaperConfig] via [updateConfig] from the
 * Service, which owns the coroutine scope and watches for changes. This
 * clearly separates "rendering" from "state management."
 */
class WallpaperGLRenderer(
    private val onSurfaceReady: (Surface) -> Unit,
    private val onFrameAvailable: (() -> Unit)? = null
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "WallpaperGLRenderer"
        private const val MAX_TEXTURE_DIMENSION = 3840

        private fun openStream(context: Context, uri: Uri): java.io.InputStream? {
            return try {
                if (uri.scheme == "file" || uri.scheme.isNullOrEmpty()) {
                    val rawPath = uri.path ?: uri.toString()
                    val decodedPath = try { Uri.decode(rawPath) } catch (_: Exception) { rawPath }
                    val file = java.io.File(decodedPath)
                    if (file.exists() && file.isFile) {
                        file.inputStream()
                    } else {
                        context.contentResolver.openInputStream(uri)
                    }
                } else {
                    context.contentResolver.openInputStream(uri)
                }
            } catch (_: Exception) {
                try {
                    context.contentResolver.openInputStream(uri)
                } catch (_: Exception) {
                    null
                }
            }
        }

        fun decodeSampledBitmap(context: Context, uri: Uri): Bitmap? {
            return try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

                var sampleSize = 1
                while (options.outWidth / sampleSize > MAX_TEXTURE_DIMENSION ||
                    options.outHeight / sampleSize > MAX_TEXTURE_DIMENSION) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                var bitmap = openStream(context, uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOptions)
                } ?: return null

                val rotation = getExifRotation(context, uri)
                if (rotation != 0) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    if (rotated != bitmap) {
                        bitmap.recycle()
                        bitmap = rotated
                    }
                }
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode bitmap from $uri", e)
                null
            }
        }

        private fun getExifRotation(context: Context, uri: Uri): Int {
            return try {
                openStream(context, uri)?.use { stream ->
                    val exif = android.media.ExifInterface(stream)
                    when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } ?: 0
            } catch (_: Exception) {
                0
            }
        }
    }

    // FIX: onSurfaceCreated() is required to run on the GLThread (a requirement
    // from GLSurfaceView itself), but the code consuming onSurfaceReady (like
    // ExoPlayer.setVideoSurface) rejects any call from off the Main Thread. We
    // hop the callback over to Main via this Handler before it reaches any
    // external logic.
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── GL handles ────────────────────────────────────────────────────────────
    private var videoProgram = 0
    private var bgProgram    = 0
    private var imageProgram = 0

    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uMVPLoc      = 0
    private var uSTMatrixLoc = 0
    private var uTextureLoc  = 0
    private var uCCEnabledLoc = 0
    private var uBrightnessLoc = 0
    private var uContrastLoc   = 0
    private var uSaturationLoc = 0

    private var imgAPositionLoc = 0
    private var imgATexCoordLoc = 0
    private var imgUMVPLoc      = 0
    private var imgUTextureLoc  = 0
    private var imgUCCEnabledLoc = 0
    private var imgUBrightnessLoc = 0
    private var imgUContrastLoc   = 0
    private var imgUSaturationLoc = 0

    private var bgAPositionLoc = 0
    private var bgMVPLoc       = 0
    private var bgColorLoc     = 0

    private var oesTextureId = 0
    private var imageTextureId = 0
    @Volatile var isImageMode = false

    private val pendingBitmapLock = Object()
    private var pendingBitmap: Bitmap? = null
    private var currentBitmap: Bitmap? = null

    private lateinit var surfaceTexture: SurfaceTexture
    private val stMatrix = FloatArray(16)

    // ── State, made easy for the Service to update without touching GL directly ────────────────────
    @Volatile var config: WallpaperConfig = WallpaperConfig.default("")
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    @Volatile var screenWidth = 0
    @Volatile var screenHeight = 0

    private var frameAvailable = false
    private val frameLock = Object()

    fun updateConfig(newConfig: WallpaperConfig) { config = newConfig }

    fun updateVideoSize(w: Int, h: Int) { videoWidth = w; videoHeight = h }

    fun setImageBitmap(bitmap: Bitmap) {
        synchronized(pendingBitmapLock) {
            pendingBitmap = bitmap
            isImageMode = true
        }
        updateVideoSize(bitmap.width, bitmap.height)
    }

    fun setImageUri(context: Context, uri: Uri, onLoaded: ((Int, Int) -> Unit)? = null) {
        Thread {
            val bmp = decodeSampledBitmap(context, uri)
            if (bmp != null) {
                setImageBitmap(bmp)
                mainHandler.post { onLoaded?.invoke(bmp.width, bmp.height) }
            }
        }.start()
    }

    fun clearImage() {
        synchronized(pendingBitmapLock) {
            pendingBitmap = null
            currentBitmap = null
            isImageMode = false
        }
    }

    private fun uploadImageTexture(bitmap: Bitmap) {
        if (imageTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(imageTextureId), 0)
            imageTextureId = 0
        }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        imageTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        currentBitmap = bitmap
        isImageMode = true
        videoWidth = bitmap.width
        videoHeight = bitmap.height
    }

    /**
     * FIX (black screen until app restart when switching wallpapers): when the
     * user changes the active video from the Editor while the wallpaper's GL
     * surface is already alive, onSurfaceCreated() is NOT called again by the
     * system (it only runs once, when the wallpaper first attaches) — so the
     * *same* SurfaceTexture instance keeps being reused across videos. The old
     * ExoPlayer is torn down and a new one is prepared on that same
     * SurfaceTexture (see YoukiGLWallpaperService.observeActiveConfig), but
     * frameAvailable stays at whatever it was left at from the previous video.
     * If the new player's very first onFrameAvailable callback lands in a way
     * that races with this state (or is briefly delayed), onDrawFrame() just
     * keeps re-displaying nothing new — a black frame — until something else
     * forces a fresh render, which in practice only happened on a full app/
     * wallpaper restart (a real onSurfaceCreated rebirth).
     *
     * The fix: explicitly clear the stale frame flag right before a new video
     * is prepared, so the renderer doesn't hold onto pre-switch state.
     * Combined with requestRender() being called again right after
     * VideoEngine.prepare() (see startVideo()), this guarantees a fresh draw
     * is attempted as soon as the new player's first real frame arrives,
     * instead of only reacting when the old frameAvailable flag has been left
     * in a way that produces one.
     */
    fun clearCurrentFrame() {
        synchronized(frameLock) { frameAvailable = false }
    }

    // ── GLSurfaceView.Renderer ───────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, eglConfig: EGLConfig?) {
        // Resolved issue: Freeze/Glitch — GLSurfaceView EGL context recreation.
        // GLSurfaceView recreates the EGL context on every onResume() — meaning
        // onSurfaceCreated() gets called again after every pause/resume (e.g.
        // the user presses Home and comes back, or opens Settings and exits).
        //
        // The problem was: released=true left over from the previous session
        // makes onDrawFrame() return immediately → a white/frozen screen with
        // nothing drawn on it. Also the old SurfaceTexture is tied to an
        // oesTextureId that's already been deleted from the previous GPU context.
        //
        // The fix: onSurfaceCreated() = a full "rebirth" — we reinitialize
        // every GL resource and reset released=false before any drawing.
        //
        // Note: releasing the old surfaceTexture here is safe because we're on
        // the GL thread and no other GL thread is running at the same time
        // (GLSurfaceView guarantees this).
        released = false
        if (::surfaceTexture.isInitialized) {
            try {
                surfaceTexture.setOnFrameAvailableListener(null)
                surfaceTexture.release()
            } catch (_: Exception) {}
        }
        if (videoProgram != 0) { GLES20.glDeleteProgram(videoProgram); videoProgram = 0 }
        if (bgProgram != 0) { GLES20.glDeleteProgram(bgProgram); bgProgram = 0 }
        if (imageProgram != 0) { GLES20.glDeleteProgram(imageProgram); imageProgram = 0 }
        if (oesTextureId != 0) { GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0); oesTextureId = 0 }
        if (imageTextureId != 0) { GLES20.glDeleteTextures(1, intArrayOf(imageTextureId), 0); imageTextureId = 0 }

        videoProgram = GLProgram.build(Shaders.VERTEX_SHADER, Shaders.FRAGMENT_SHADER)
        aPositionLoc  = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        aTexCoordLoc  = GLES20.glGetAttribLocation(videoProgram, "aTextureCoord")
        uMVPLoc       = GLES20.glGetUniformLocation(videoProgram, "uMVPMatrix")
        uSTMatrixLoc  = GLES20.glGetUniformLocation(videoProgram, "uSTMatrix")
        uTextureLoc   = GLES20.glGetUniformLocation(videoProgram, "sTexture")
        uCCEnabledLoc = GLES20.glGetUniformLocation(videoProgram, "uColorCorrectionEnabled")
        uBrightnessLoc = GLES20.glGetUniformLocation(videoProgram, "uBrightness")
        uContrastLoc   = GLES20.glGetUniformLocation(videoProgram, "uContrast")
        uSaturationLoc = GLES20.glGetUniformLocation(videoProgram, "uSaturation")

        imageProgram = GLProgram.build(Shaders.IMAGE_VERTEX_SHADER, Shaders.IMAGE_FRAGMENT_SHADER)
        imgAPositionLoc  = GLES20.glGetAttribLocation(imageProgram, "aPosition")
        imgATexCoordLoc  = GLES20.glGetAttribLocation(imageProgram, "aTextureCoord")
        imgUMVPLoc       = GLES20.glGetUniformLocation(imageProgram, "uMVPMatrix")
        imgUTextureLoc   = GLES20.glGetUniformLocation(imageProgram, "sTexture")
        imgUCCEnabledLoc = GLES20.glGetUniformLocation(imageProgram, "uColorCorrectionEnabled")
        imgUBrightnessLoc = GLES20.glGetUniformLocation(imageProgram, "uBrightness")
        imgUContrastLoc   = GLES20.glGetUniformLocation(imageProgram, "uContrast")
        imgUSaturationLoc = GLES20.glGetUniformLocation(imageProgram, "uSaturation")

        bgProgram = GLProgram.build(Shaders.SOLID_COLOR_VERTEX_SHADER, Shaders.SOLID_COLOR_FRAGMENT_SHADER)
        bgAPositionLoc = GLES20.glGetAttribLocation(bgProgram, "aPosition")
        bgMVPLoc       = GLES20.glGetUniformLocation(bgProgram, "uMVPMatrix")
        bgColorLoc     = GLES20.glGetUniformLocation(bgProgram, "uColor")

        oesTextureId = createOESTexture()
        surfaceTexture = SurfaceTexture(oesTextureId)
        surfaceTexture.setOnFrameAvailableListener {
            synchronized(frameLock) { frameAvailable = true }
            // FIX: before, the Service's periodic loop (gated by isVisible +
            // an FPS limiter) was the only source of requestRender() — if the
            // onVisibilityChanged call was delayed or broke, the first frame
            // would draw and then everything would go completely still (this
            // was one cause of the visible "infinite loading"). Now every new
            // video frame from ExoPlayer requests its own immediate render,
            // regardless of any external loop.
            onFrameAvailable?.invoke()
        }

        if (isImageMode && currentBitmap != null && !currentBitmap!!.isRecycled) {
            uploadImageTexture(currentBitmap!!)
        } else if (currentBitmap == null && pendingBitmap == null) {
            isImageMode = false
        }

        val surface = Surface(surfaceTexture)
        mainHandler.post { onSurfaceReady(surface) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        screenWidth = width
        screenHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        // FIX: if release() gets called from another thread (onDestroy) at the
        // same time the GL thread is trying to draw,
        // surfaceTexture.updateTexImage() on a released object =
        // IllegalStateException/crash. We check released first.
        if (released) return

        synchronized(pendingBitmapLock) {
            val bmp = pendingBitmap
            if (bmp != null && !bmp.isRecycled) {
                uploadImageTexture(bmp)
                pendingBitmap = null
            }
        }

        if (!isImageMode) {
            synchronized(frameLock) {
                if (frameAvailable) {
                    surfaceTexture.updateTexImage()
                    surfaceTexture.getTransformMatrix(stMatrix)
                    frameAvailable = false
                }
            }
        }

        // ── Step 1: clear the screen with the defined background color ─────
        val bg = parseColor(config.backgroundColor)
        GLES20.glClearColor(bg[0], bg[1], bg[2], bg[3])
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Redraw a solid quad with the same color (guarantees coverage even if the video is partially transparent)
        drawBackgroundQuad(bg)

        // ── Step 2: draw the frame on top with the computed MVP matrix ─────────────
        if (isImageMode && imageTextureId != 0) {
            drawImageFrame()
        } else if (videoWidth > 0 && videoHeight > 0) {
            drawVideoFrame()
        }
    }

    // ── Drawing ─────────────────────────────────────────────────────────────────

    private fun drawBackgroundQuad(color: FloatArray) {
        GLES20.glUseProgram(bgProgram)
        GLES20.glEnableVertexAttribArray(bgAPositionLoc)
        Quad.vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            bgAPositionLoc, Quad.COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, Quad.vertexBuffer
        )
        GLES20.glUniformMatrix4fv(bgMVPLoc, 1, false, TransformMatrix.identity(), 0)
        GLES20.glUniform4fv(bgColorLoc, 1, color, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, Quad.VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(bgAPositionLoc)
    }

    private fun drawImageFrame() {
        GLES20.glUseProgram(imageProgram)

        GLES20.glEnableVertexAttribArray(imgAPositionLoc)
        Quad.vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            imgAPositionLoc, Quad.COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, Quad.vertexBuffer
        )

        GLES20.glEnableVertexAttribArray(imgATexCoordLoc)
        Quad.imageTexCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(
            imgATexCoordLoc, Quad.TEX_COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, Quad.imageTexCoordBuffer
        )

        val mvp = TransformMatrix.build(config, videoWidth, videoHeight, screenWidth, screenHeight)
        GLES20.glUniformMatrix4fv(imgUMVPLoc, 1, false, mvp, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
        GLES20.glUniform1i(imgUTextureLoc, 0)

        GLES20.glUniform1i(imgUCCEnabledLoc, if (config.colorCorrectionEnabled) 1 else 0)
        GLES20.glUniform1f(imgUBrightnessLoc, config.brightness)
        GLES20.glUniform1f(imgUContrastLoc,   config.contrast)
        GLES20.glUniform1f(imgUSaturationLoc,  config.saturation)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, Quad.VERTEX_COUNT)

        GLES20.glDisableVertexAttribArray(imgAPositionLoc)
        GLES20.glDisableVertexAttribArray(imgATexCoordLoc)
    }

    private fun drawVideoFrame() {
        GLES20.glUseProgram(videoProgram)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        Quad.vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(
            aPositionLoc, Quad.COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, Quad.vertexBuffer
        )

        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        Quad.texCoordBuffer.position(0)
        GLES20.glVertexAttribPointer(
            aTexCoordLoc, Quad.TEX_COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, 0, Quad.texCoordBuffer
        )

        val mvp = TransformMatrix.build(config, videoWidth, videoHeight, screenWidth, screenHeight)
        GLES20.glUniformMatrix4fv(uMVPLoc, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixLoc, 1, false, stMatrix, 0)

        // ── Phase 3: pass the color values to the shader ────────────────────────────
        GLES20.glUniform1i(uCCEnabledLoc, if (config.colorCorrectionEnabled) 1 else 0)
        GLES20.glUniform1f(uBrightnessLoc, config.brightness)
        GLES20.glUniform1f(uContrastLoc, config.contrast)
        GLES20.glUniform1f(uSaturationLoc, config.saturation)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, Quad.VERTEX_COUNT)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val id = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id
    }

    /** Converts "#AARRGGBB" into [r,g,b,a] with 0f..1f values as OpenGL needs them */
    private fun parseColor(hex: String): FloatArray {
        return try {
            val clean = hex.removePrefix("#")
            val argb = clean.toLong(16)
            val a = ((argb shr 24) and 0xFF) / 255f
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8)  and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            floatArrayOf(r, g, b, a)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid color hex: $hex, falling back to black")
            floatArrayOf(0f, 0f, 0f, 1f)
        }
    }

    // Fix for Crash — Use-After-Release + leaked GL resources:
    // The old release() only released the SurfaceTexture and ignored:
    //   1. oesTextureId — a texture registered on the GPU that stays reserved forever
    //   2. videoProgram / bgProgram — GL programs that never get deleted
    //   3. surfaceTexture.setOnFrameAvailableListener — stays active and could
    //      fire a callback after release → onFrameAvailable gets called on
    //      released objects = crash
    //
    // The fix: release everything in the correct order:
    //   First: disconnect the listener so no new callback can arrive
    //   Second: release the SurfaceTexture
    //   Third: delete the GL resources (must be on the same EGL thread — but
    //   here WallpaperGLEngine already calls release() from the GL thread via surfaceDestroyed)
    @Volatile private var released = false

    fun release() {
        if (released) return
        released = true
        if (::surfaceTexture.isInitialized) {
            // Disconnect the listener first — prevents any callback after release
            surfaceTexture.setOnFrameAvailableListener(null)
            surfaceTexture.release()
        }
        // Delete the GL resources if the program is still valid
        if (videoProgram != 0) { GLES20.glDeleteProgram(videoProgram); videoProgram = 0 }
        if (bgProgram != 0) { GLES20.glDeleteProgram(bgProgram); bgProgram = 0 }
        if (imageProgram != 0) { GLES20.glDeleteProgram(imageProgram); imageProgram = 0 }
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
        if (imageTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(imageTextureId), 0)
            imageTextureId = 0
        }
        currentBitmap = null
        pendingBitmap = null
    }
}
