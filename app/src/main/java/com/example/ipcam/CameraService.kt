package com.example.ipcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Size
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Keeps a small HTTP server running the whole time and opens/closes the camera on demand.
 * Features: remote on/off, software motion detection with a zone and a bounding box,
 * digital zoom, night-mode brightness, rotation, and mDNS (ipcam.local).
 * Uses only the Android framework, so it needs no external libraries and stores nothing.
 */
class CameraService : Service() {

    companion object {
        const val ACTION_START = "com.example.ipcam.START"
        const val ACTION_STOP = "com.example.ipcam.STOP"
        const val PORT = 8080
        const val MDNS_HOST = "ipcam"   // reachable as http://ipcam.local:8080/

        // ---------------- Settings you can change ----------------
        const val USE_FRONT_CAMERA = false
        const val JPEG_QUALITY = 60
        const val TARGET_WIDTH = 1280
        const val TARGET_HEIGHT = 720
        const val AUTH_USER = ""
        const val AUTH_PASSWORD = ""
        const val PIXEL_DELTA = 20
        // ---------------------------------------------------------

        private const val CHANNEL_ID = "ipcam"
        private const val NOTIF_ID = 1
    }

    private val hub = StreamHub()
    private var server: MjpegServer? = null
    private var mdns: MdnsResponder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var captureBuilder: CaptureRequest.Builder? = null
    private var activeArray: Rect? = null
    private var maxZoom: Float = 1f
    private var minFocusDist: Float = 0f   // diopters at closest focus; 0 = fixed-focus lens
    private var prevSamples: IntArray? = null
    @Volatile private var opening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        acquireWakeLock()
        startBackground()

        hub.onCameraStateChanged = { on ->
            bgHandler?.post { if (on) resumeCamera() else pauseCamera() }
        }
        hub.onZoomChanged = { bgHandler?.post { applyZoomToCamera() } }
        hub.onFocusChanged = { bgHandler?.post { applyFocusToCamera() } }
        hub.onFacingChanged = { bgHandler?.post { switchCamera() } }

        startServer()
        mdns = MdnsResponder(this, MDNS_HOST).also { it.start() }

        if (hub.cameraOn) bgHandler?.post { openCameraDevice() }
        else hub.latestJpeg = placeholderJpeg("Camera OFF")

        return START_STICKY
    }

    /** Resume streaming. Opens the camera the first time, otherwise just restarts the preview. */
    private fun resumeCamera() {
        if (session != null) startRepeating()
        else openCameraDevice()
    }

    /**
     * Pause streaming WITHOUT closing the camera device. On old hardware, closing and
     * reopening the camera corrupts the HAL, so we keep it open and just stop the preview.
     */
    private fun pauseCamera() {
        try { session?.stopRepeating() } catch (_: Exception) {}
        prevSamples = null
        hub.motion = false
        hub.motionLevel = 0
        hub.hasBox = false
        hub.latestJpeg = placeholderJpeg("Camera OFF")
    }

    private fun startRepeating() {
        val s = session ?: return
        val b = captureBuilder ?: return
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (_: Exception) {}
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "BabyCam", NotificationManager.IMPORTANCE_LOW)
            )
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notif = builder
            .setContentTitle("BabyCam running")
            .setContentText("Baby monitor is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ipcam:stream").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun startBackground() {
        bgThread = HandlerThread("camera-bg").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun startServer() {
        server = MjpegServer(PORT, AUTH_USER, AUTH_PASSWORD, hub).also { it.start() }
    }

    private fun chooseCameraId(manager: CameraManager): String? {
        val want = if (hub.useFront) CameraCharacteristics.LENS_FACING_FRONT
                   else CameraCharacteristics.LENS_FACING_BACK
        var fallback: String? = null
        for (id in manager.cameraIdList) {
            if (fallback == null) fallback = id
            val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing == want) return id
        }
        return fallback
    }

    private fun chooseSize(map: android.hardware.camera2.params.StreamConfigurationMap?): Size {
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
        return sizes?.minByOrNull {
            Math.abs(it.width - TARGET_WIDTH) + Math.abs(it.height - TARGET_HEIGHT)
        } ?: Size(TARGET_WIDTH, TARGET_HEIGHT)
    }

    private fun openCameraDevice() {
        if (cameraDevice != null || opening) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            hub.latestJpeg = placeholderJpeg("No camera permission")
            return
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = chooseCameraId(manager) ?: run { hub.latestJpeg = placeholderJpeg("No camera"); return }
        val chars = try { manager.getCameraCharacteristics(id) } catch (_: Exception) { null }
        activeArray = chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        maxZoom = chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        minFocusDist = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val size = chooseSize(chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (hub.cameraOn) onFrame(image)
            } catch (_: Exception) {
            } finally {
                image.close()
            }
        }, bgHandler)
        imageReader = reader

        opening = true
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opening = false
                    cameraDevice = device
                    createSession(device, reader)
                }
                override fun onDisconnected(device: CameraDevice) {
                    opening = false; device.close(); cameraDevice = null
                }
                override fun onError(device: CameraDevice, error: Int) {
                    opening = false; device.close(); cameraDevice = null
                }
            }, bgHandler)
        } catch (_: Exception) {
            opening = false
            hub.latestJpeg = placeholderJpeg("Camera error")
        }
    }

    private fun createSession(device: CameraDevice, reader: ImageReader) {
        try {
            val surface = reader.surface
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            requestBuilder.addTarget(surface)
            captureBuilder = requestBuilder
            applyCropRegion(requestBuilder)
            applyFocus(requestBuilder)
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    if (hub.cameraOn) {
                        try { s.setRepeatingRequest(requestBuilder.build(), null, bgHandler) } catch (_: Exception) {}
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, bgHandler)
        } catch (_: Exception) {
            releaseCamera()
        }
    }

    private fun applyCropRegion(builder: CaptureRequest.Builder) {
        val arr = activeArray ?: return
        val maxZ = if (maxZoom < 1f) 1f else maxZoom
        val z = (hub.zoom / 100f).coerceIn(1f, maxZ)
        val cw = (arr.width() / z).toInt()
        val ch = (arr.height() / z).toInt()
        val x = arr.left + (arr.width() - cw) / 2
        val y = arr.top + (arr.height() - ch) / 2
        builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(x, y, x + cw, y + ch))
    }

    private fun applyZoomToCamera() {
        val s = session ?: return
        val b = captureBuilder ?: return
        applyCropRegion(b)
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (_: Exception) {}
    }

    /** Set autofocus, or lock a manual focus distance (0 = far/infinity, 100 = near). */
    private fun applyFocus(builder: CaptureRequest.Builder) {
        val f = hub.focus
        if (f < 0 || minFocusDist <= 0f) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocusDist * f / 100f)
        }
    }

    private fun applyFocusToCamera() {
        val s = session ?: return
        val b = captureBuilder ?: return
        applyFocus(b)
        try { s.setRepeatingRequest(b.build(), null, bgHandler) } catch (_: Exception) {}
    }

    /**
     * Switch between the back and front lens. Unlike on/off, this is a different physical
     * device, so we must fully close the current camera and open the other one.
     */
    private fun switchCamera() {
        releaseCamera()
        hub.latestJpeg = placeholderJpeg("Switching camera...")
        if (hub.cameraOn) openCameraDevice()
    }

    private fun releaseCamera() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        captureBuilder = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        prevSamples = null
    }

    /** Process one frame: motion detection, brightness, JPEG encoding and rotation. */
    private fun onFrame(image: Image) {
        val width = image.width
        val height = image.height

        // 1) Motion detection (also fills the bounding box).
        val level = computeMotion(image.planes[0].buffer, image.planes[0].rowStride, width, height)
        hub.motionLevel = level
        val trigger = ((100 - hub.sensitivity) * 0.1).coerceAtLeast(0.3)
        hub.motion = level >= trigger
        if (!hub.motion) hub.hasBox = false

        // 2) YUV -> NV21, then optional brightness boost on the luma plane.
        val nv21 = yuv420ToNv21(image.planes, width, height)
        val gain = hub.brightness
        if (gain != 100) {
            val ySize = width * height
            var i = 0
            while (i < ySize) {
                val v = (nv21[i].toInt() and 0xFF) * gain / 100
                nv21[i] = (if (v > 255) 255 else v).toByte()
                i++
            }
        }

        // 3) Encode to JPEG.
        val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuv.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        var jpeg = out.toByteArray()

        // 4) Optional rotation.
        val rot = hub.rotation
        if (rot != 0) jpeg = rotateJpeg(jpeg, rot, JPEG_QUALITY)

        hub.latestJpeg = jpeg
    }

    /**
     * Samples the luma plane inside the user's zone, compares with the previous frame,
     * updates the motion bounding box, and returns the percent of samples that changed.
     */
    private fun computeMotion(yBuffer: ByteBuffer, rowStride: Int, width: Int, height: Int): Int {
        val zx0 = (width * hub.zoneLeft / 100).coerceIn(0, width - 1)
        val zx1 = (width * hub.zoneRight / 100).coerceIn(zx0 + 1, width)
        val zy0 = (height * hub.zoneTop / 100).coerceIn(0, height - 1)
        val zy1 = (height * hub.zoneBottom / 100).coerceIn(zy0 + 1, height)
        val zoneW = zx1 - zx0
        val zoneH = zy1 - zy0
        val stepX = (zoneW / 80).coerceAtLeast(1)
        val stepY = (zoneH / 45).coerceAtLeast(1)
        val cols = (zoneW + stepX - 1) / stepX
        val rows = (zoneH + stepY - 1) / stepY
        val samples = IntArray(cols * rows)

        var idx = 0
        var y = zy0
        while (y < zy1) {
            val base = y * rowStride
            var x = zx0
            while (x < zx1) {
                samples[idx++] = yBuffer.get(base + x).toInt() and 0xFF
                x += stepX
            }
            y += stepY
        }

        val prev = prevSamples
        prevSamples = samples
        if (prev == null || prev.size != idx) { hub.hasBox = false; return 0 }

        var changed = 0
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        var i = 0
        var yy = zy0
        while (yy < zy1) {
            var xx = zx0
            while (xx < zx1) {
                if (i < idx && Math.abs(samples[i] - prev[i]) > PIXEL_DELTA) {
                    changed++
                    if (xx < minX) minX = xx
                    if (xx > maxX) maxX = xx
                    if (yy < minY) minY = yy
                    if (yy > maxY) maxY = yy
                }
                i++
                xx += stepX
            }
            yy += stepY
        }

        if (changed >= 2 && maxX >= minX) {
            hub.boxLeft = minX * 100 / width
            hub.boxTop = minY * 100 / height
            hub.boxRight = (maxX + stepX).coerceAtMost(width) * 100 / width
            hub.boxBottom = (maxY + stepY).coerceAtMost(height) * 100 / height
            hub.hasBox = true
        } else {
            hub.hasBox = false
        }
        return changed * 100 / idx
    }

    private fun placeholderJpeg(text: String): ByteArray {
        val w = 640; val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        p.textSize = 40f
        p.textAlign = Paint.Align.CENTER
        c.drawText(text, w / 2f, h / 2f, p)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bmp.recycle()
        return out.toByteArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        hub.onCameraStateChanged = null
        hub.onZoomChanged = null
        hub.onFocusChanged = null
        hub.onFacingChanged = null
        releaseCamera()
        mdns?.stop()
        server?.stop()
        bgThread?.quitSafely()
        wakeLock?.let { if (it.isHeld) it.release() }
        hub.latestJpeg = null
    }
}

/** Rotate a JPEG by the given degrees (90/180/270). */
private fun rotateJpeg(jpeg: ByteArray, degrees: Int, quality: Int): ByteArray {
    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    val out = ByteArrayOutputStream()
    rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (rotated != bmp) rotated.recycle()
    bmp.recycle()
    return out.toByteArray()
}

/** Convert a YUV_420_888 frame (as camera planes) to an NV21 byte array for YuvImage. */
private fun yuv420ToNv21(planes: Array<Image.Plane>, width: Int, height: Int): ByteArray {
    val ySize = width * height
    val nv21 = ByteArray(ySize + ySize / 2)

    val yBuffer = planes[0].buffer
    val yRowStride = planes[0].rowStride
    var pos = 0
    if (yRowStride == width) {
        yBuffer.position(0)
        yBuffer.get(nv21, 0, ySize)
        pos = ySize
    } else {
        val row = ByteArray(width)
        for (r in 0 until height) {
            yBuffer.position(r * yRowStride)
            yBuffer.get(row, 0, width)
            System.arraycopy(row, 0, nv21, pos, width)
            pos += width
        }
    }

    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val uRowStride = planes[1].rowStride
    val uPixelStride = planes[1].pixelStride
    val vRowStride = planes[2].rowStride
    val vPixelStride = planes[2].pixelStride

    val chromaHeight = height / 2
    val chromaWidth = width / 2
    var offset = ySize
    for (r in 0 until chromaHeight) {
        for (c in 0 until chromaWidth) {
            nv21[offset++] = vBuffer.get(r * vRowStride + c * vPixelStride)
            nv21[offset++] = uBuffer.get(r * uRowStride + c * uPixelStride)
        }
    }
    return nv21
}
