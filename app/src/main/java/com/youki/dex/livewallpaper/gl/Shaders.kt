package com.youki.dex.livewallpaper.gl

/**
 * Shaders.kt — the GLSL code for the rendering engine.
 *
 * ┌────────────────────────────────────────────────────────────────────────┐
 * │  VERTEX_SHADER:                                                        │
 * │    Receives the coordinates + a single ready-made MVP matrix (computed │
 * │    on the CPU in TransformMatrix.kt) and multiplies it by every point. │
 * │    This gives us Fit/Cover/Stretch/Free Mode all "for free" with no IF │
 * │    inside the shader at all.                                          │
 * │                                                                        │
 * │  FRAGMENT_SHADER:                                                      │
 * │    Reads the video frame from samplerExternalOES (the same technique   │
 * │    ExoPlayer uses with SurfaceTexture) then applies                    │
 * │    Brightness/Contrast/Saturation immediately.                        │
 * └────────────────────────────────────────────────────────────────────────┘
 */
object Shaders {

    const val VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """

    // OES_EGL_image_external is required to read video frames directly from SurfaceTexture
    const val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;

        uniform bool  uColorCorrectionEnabled;
        uniform float uBrightness;   // 0..2   (1 = neutral)
        uniform float uContrast;     // 0..2
        uniform float uSaturation;   // 0..2

        vec3 applyColorCorrection(vec3 color) {
            // Contrast: blend around mid-gray (0.5)
            color = mix(vec3(0.5), color, uContrast);
            // Brightness: direct multiplication
            color *= uBrightness;
            // Saturation: blend between luminance (gray) and the original color
            float luminance = dot(color, vec3(0.299, 0.587, 0.114));
            color = mix(vec3(luminance), color, uSaturation);
            return clamp(color, 0.0, 1.0);
        }

        void main() {
            vec4 texColor = texture2D(sTexture, vTextureCoord);
            if (uColorCorrectionEnabled) {
                texColor.rgb = applyColorCorrection(texColor.rgb);
            }
            gl_FragColor = texColor;
        }
    """

    /**
     * An alternative fragment shader for drawing a solid background color (the empty space in Fit mode).
     * Used with a separate quad drawn behind the video and before scissor/clear is enabled.
     */
    const val SOLID_COLOR_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
        }
    """

    const val SOLID_COLOR_FRAGMENT_SHADER = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """

    // Shaders for static images (using standard sampler2D instead of samplerExternalOES)
    const val IMAGE_VERTEX_SHADER = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = aTextureCoord;
        }
    """

    const val IMAGE_FRAGMENT_SHADER = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D sTexture;

        uniform bool  uColorCorrectionEnabled;
        uniform float uBrightness;   // 0..2   (1 = neutral)
        uniform float uContrast;     // 0..2
        uniform float uSaturation;   // 0..2

        vec3 applyColorCorrection(vec3 color) {
            color = mix(vec3(0.5), color, uContrast);
            color *= uBrightness;
            float luminance = dot(color, vec3(0.299, 0.587, 0.114));
            color = mix(vec3(luminance), color, uSaturation);
            return clamp(color, 0.0, 1.0);
        }

        void main() {
            vec4 texColor = texture2D(sTexture, vTextureCoord);
            if (uColorCorrectionEnabled) {
                texColor.rgb = applyColorCorrection(texColor.rgb);
            }
            gl_FragColor = texColor;
        }
    """
}
