package com.example.ipcam

/** Shared, thread-safe state between the camera service and the HTTP server. */
class StreamHub {
    @Volatile var latestJpeg: ByteArray? = null

    // Camera on/off — the HTTP server keeps running even when the camera is off.
    @Volatile var cameraOn: Boolean = true

    // Motion detection (reported only — nothing is stored)
    @Volatile var motion: Boolean = false
    @Volatile var motionLevel: Int = 0        // percent of the zone that changed (0..100)
    @Volatile var sensitivity: Int = 50       // user setting, 0..100 (higher = more sensitive)

    // Image rotation applied before streaming: 0, 90, 180 or 270
    @Volatile var rotation: Int = 0

    // Image brightness (night mode): luma gain in percent, 100 = normal, up to 300.
    @Volatile var brightness: Int = 100

    // Digital zoom in percent, 100 = 1x. Clamped to the camera's max by the service.
    @Volatile var zoom: Int = 100

    // Manual focus: -1 = autofocus (default). 0..100 = manual lock,
    // 0 = focus far (infinity), 100 = focus near (closest the lens can do).
    @Volatile var focus: Int = -1

    // Which lens to use: false = back camera (default), true = front camera.
    @Volatile var useFront: Boolean = false

    // Motion-detection zone, as percent of the frame (default = whole frame).
    @Volatile var zoneLeft = 0
    @Volatile var zoneTop = 0
    @Volatile var zoneRight = 100
    @Volatile var zoneBottom = 100

    // Bounding box of the current motion, as percent of the frame (valid when hasBox).
    @Volatile var boxLeft = 0
    @Volatile var boxTop = 0
    @Volatile var boxRight = 0
    @Volatile var boxBottom = 0
    @Volatile var hasBox = false

    // The service registers here to actually open/close the camera and re-apply zoom.
    var onCameraStateChanged: ((Boolean) -> Unit)? = null
    var onZoomChanged: (() -> Unit)? = null
    var onFocusChanged: (() -> Unit)? = null
    var onFacingChanged: (() -> Unit)? = null

    fun applyCamera(on: Boolean) {
        if (cameraOn != on) {
            cameraOn = on
            onCameraStateChanged?.invoke(on)
        }
    }

    fun applySensitivity(v: Int) { sensitivity = v.coerceIn(0, 100) }

    fun applyRotation(deg: Int) { rotation = ((deg % 360) + 360) % 360 }

    fun rotate90() { rotation = (rotation + 90) % 360 }

    fun applyBrightness(v: Int) { brightness = v.coerceIn(100, 300) }

    fun applyZoom(v: Int) { zoom = v.coerceIn(100, 500); onZoomChanged?.invoke() }

    fun applyFocus(v: Int) { focus = if (v < 0) -1 else v.coerceIn(0, 100); onFocusChanged?.invoke() }

    fun applyFacing(front: Boolean) {
        if (useFront != front) { useFront = front; onFacingChanged?.invoke() }
    }

    fun toggleFacing() { useFront = !useFront; onFacingChanged?.invoke() }

    fun applyZone(l: Int, t: Int, r: Int, b: Int) {
        var ll = l.coerceIn(0, 100); var tt = t.coerceIn(0, 100)
        var rr = r.coerceIn(0, 100); var bb = b.coerceIn(0, 100)
        if (rr <= ll) { ll = 0; rr = 100 }
        if (bb <= tt) { tt = 0; bb = 100 }
        zoneLeft = ll; zoneTop = tt; zoneRight = rr; zoneBottom = bb
    }
}
