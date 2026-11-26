package com.wled.tv.model

/**
 * Global screen capture resolution and target streaming frame rate settings.
 */
data class CaptureSettings(
    val fps: Int = 3,                    // Default 3 FPS (1, 2, 3, 4, 5, 6, 7, 15, 30, 60)
    val captureWidth: Int = 80,         // Capture buffer width (80, 160, 320, 480)
    val captureHeight: Int = 45         // Capture buffer height (45, 90, 180, 270)
) {
    val resolutionLabel: String
        get() = when (captureWidth) {
            80 -> "80x45 (Ultra)"
            160 -> "160x90 (Eco)"
            480 -> "480x270 (High)"
            else -> "320x180 (Std)"
        }
}
