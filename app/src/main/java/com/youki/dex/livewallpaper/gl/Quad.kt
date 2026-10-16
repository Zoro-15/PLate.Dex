package com.youki.dex.livewallpaper.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Quad — a simple quad geometry (4 vertices) covering [-1,1]×[-1,1].
 * Used twice: once to draw the video (with texture coords), and once for the solid background color.
 */
object Quad {

    // x, y, z for each vertex — a triangle strip in this order: top-left, bottom-left, top-right, bottom-right
    private val VERTICES = floatArrayOf(
        -1f,  1f, 0f,
        -1f, -1f, 0f,
         1f,  1f, 0f,
         1f, -1f, 0f
    )

    // Texture coordinates — same vertex order as above
    //
    // Fix for the image/video was upside down — "like a flipped page":
    // The vertex order above is: top-left, bottom-left, top-right, bottom-right.
    // In OpenGL's texture coordinate system, V=0 means the "bottom" of the
    // image and V=1 means the "top" of the image (the opposite of the usual
    // screen system where Y=0 is at the top). The old values used to map V=0
    // to the "top-left" vertex — meaning the bottom of the image got drawn at
    // the top of the screen, flipping the image completely vertically (and
    // text came out upside down, as seen in the user's screenshot). The fix:
    // invert V for each vertex so V=1 (the image's actual top) maps to the
    // "top-left" vertex on screen, making the image appear in the correct
    // orientation — exactly "like a page" should look.
    private val TEX_COORDS = floatArrayOf(
        0f, 1f,
        0f, 0f,
        1f, 1f,
        1f, 0f
    )

    val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(VERTICES); position(0) }

    val texCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(TEX_COORDS); position(0) }

    // Texture coordinates for static Bitmap uploaded via GLUtils.texImage2D
    private val IMAGE_TEX_COORDS = floatArrayOf(
        0f, 0f, // top-left
        0f, 1f, // bottom-left
        1f, 0f, // top-right
        1f, 1f  // bottom-right
    )

    val imageTexCoordBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(IMAGE_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(IMAGE_TEX_COORDS); position(0) }

    const val VERTEX_COUNT = 4
    const val COORDS_PER_VERTEX = 3
    const val TEX_COORDS_PER_VERTEX = 2
}
