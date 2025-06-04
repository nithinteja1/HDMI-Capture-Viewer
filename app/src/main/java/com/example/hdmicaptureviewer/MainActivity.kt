package com.example.hdmicaptureviewer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.MultiCameraActivity
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView

class MainActivity : MultiCameraActivity(), ICameraStateCallBack {

    companion object {
        private const val TAG = "HDMICaptureViewer"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var cameraView: AspectRatioTextureView
    private lateinit var statusText: TextView
    private lateinit var qualityText: TextView
    private lateinit var connectButton: Button
    private lateinit var qualityButton: Button
    private lateinit var container: ViewGroup
    private lateinit var qualityOverlay: LinearLayout
    private lateinit var fpsText: TextView
    private lateinit var msText: TextView

    private var isPreview = false
    private var connectedCamera: MultiCameraClient.ICamera? = null
    private var performanceMode = 0 // 0=1080p, 1=720p, 2=480p

    // FPS monitoring - simplified
    private var frames = 0
    private var startTime = 0L
    private var lastMs = 0L
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private data class CameraMode(
        val name: String,
        val width: Int,
        val height: Int,
        val targetFps: Int
    )

    private val cameraModes = listOf(
        CameraMode("1080p@60fps", 1920, 1080, 60),
        CameraMode("720p@60fps", 1280, 720, 60),
        CameraMode("480p@60fps", 640, 480, 60)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        optimizeForPerformance()
        checkPermissions()
    }

    private fun optimizeForPerformance() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // MultiCameraActivity callbacks
    override fun onCameraAttached(camera: MultiCameraClient.ICamera) {
        Log.d(TAG, "Camera attached: ${camera.getUsbDevice().deviceName}")
        runOnUiThread {
            val deviceName = camera.getUsbDevice().productName ?: "Unknown"
            statusText.text = "Device detected: $deviceName"
            connectButton.isEnabled = false
        }
    }

    override fun onCameraDetached(camera: MultiCameraClient.ICamera) {
        Log.d(TAG, "Camera detached: ${camera.getUsbDevice().deviceName}")
        runOnUiThread {
            statusText.text = "Device disconnected"
            connectButton.isEnabled = false
            qualityButton.isEnabled = true
            if (isPreview) {
                updateUI(false)
            }
        }
        if (connectedCamera == camera) {
            connectedCamera = null
        }
    }

    override fun onCameraConnected(camera: MultiCameraClient.ICamera) {
        Log.d(TAG, "Camera connected: ${camera.getUsbDevice().deviceName}")
        connectedCamera = camera
        camera.setCameraStateCallBack(this)

        runOnUiThread {
            val mode = cameraModes[performanceMode]
            statusText.text = "Mode: ${mode.name} - Ready"
            connectButton.isEnabled = true
            qualityButton.isEnabled = true
        }
    }

    override fun onCameraDisConnected(camera: MultiCameraClient.ICamera) {
        Log.d(TAG, "Camera disconnected: ${camera.getUsbDevice().deviceName}")
        runOnUiThread {
            if (isPreview) {
                updateUI(false)
            }
            val mode = cameraModes[performanceMode]
            statusText.text = "Mode: ${mode.name} - Ready"
            // Keep connect button enabled - camera should still be available
            connectButton.isEnabled = true
            qualityButton.isEnabled = true
        }
        // Keep camera reference - it should still be usable for reconnection
    }

    // ICameraStateCallBack implementation
    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        Log.d(TAG, "onCameraState: $code, msg: $msg")
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.d(TAG, "Camera opened successfully")
                runOnUiThread {
                    val mode = cameraModes[performanceMode]
                    statusText.text = "Streaming: ${mode.name}"
                    updateUI(true)
                    resetFpsCounters()
                }
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.d(TAG, "Camera closed")
                runOnUiThread {
                    val mode = cameraModes[performanceMode]
                    statusText.text = "Mode: ${mode.name} - Ready"
                    updateUI(false)
                    resetFpsDisplay()
                }
            }
            ICameraStateCallBack.State.ERROR -> {
                val errorMsg = msg ?: "Unknown error"
                Log.e(TAG, "Camera error: $errorMsg")
                runOnUiThread {
                    statusText.text = "Camera error: $errorMsg"
                    Toast.makeText(this@MainActivity, "Camera error: $errorMsg", Toast.LENGTH_LONG).show()
                    updateUI(false)
                    resetFpsDisplay()
                }
            }
        }
    }

    // MultiCameraActivity abstract methods
    override fun generateCamera(ctx: Context, device: UsbDevice): MultiCameraClient.ICamera {
        return CameraUVC(ctx, device)
    }

    override fun getRootView(layoutInflater: LayoutInflater): View {
        val view = layoutInflater.inflate(R.layout.activity_main, null, false)
        initViewsFromInflatedView(view)
        return view
    }

    private fun initViewsFromInflatedView(view: View) {
        cameraView = view.findViewById(R.id.camera_view)
        statusText = view.findViewById(R.id.status_text)
        qualityText = view.findViewById(R.id.quality_text)
        connectButton = view.findViewById(R.id.connect_button)
        qualityButton = view.findViewById(R.id.quality_button)
        container = view.findViewById(R.id.container)
        qualityOverlay = view.findViewById(R.id.quality_overlay)
        fpsText = view.findViewById(R.id.fps_text)
        msText = view.findViewById(R.id.ms_text)

        updateAspectRatio()
        updateQualityDisplay()

        connectButton.setOnClickListener {
            Log.d(TAG, "Connect button clicked, isPreview: $isPreview")
            if (isPreview) {
                Log.d(TAG, "Stopping preview...")
                stopPreview()
            } else {
                Log.d(TAG, "Starting camera...")
                openCamera()
            }
        }

        qualityButton.setOnClickListener {
            toggleQualityOverlay()
        }

        view.findViewById<Button>(R.id.quality_1080p).setOnClickListener {
            selectQuality(0)
        }
        view.findViewById<Button>(R.id.quality_720p).setOnClickListener {
            selectQuality(1)
        }
        view.findViewById<Button>(R.id.quality_480p).setOnClickListener {
            selectQuality(2)
        }

        val initialMode = cameraModes[performanceMode]
        statusText.text = "Mode: ${initialMode.name} - Ready"

        startFpsMonitoring()
    }

    private fun toggleQualityOverlay() {
        if (!isPreview) {
            val isVisible = qualityOverlay.visibility == View.VISIBLE
            qualityOverlay.visibility = if (isVisible) View.GONE else View.VISIBLE
        } else {
            Toast.makeText(this, "Stop streaming to change quality", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectQuality(qualityIndex: Int) {
        Log.d(TAG, "selectQuality($qualityIndex) called, isPreview: $isPreview")
        if (!isPreview) {
            performanceMode = qualityIndex
            updateAspectRatio()
            updateQualityDisplay()
            qualityOverlay.visibility = View.GONE

            val mode = cameraModes[performanceMode]
            Log.d(TAG, "Quality changed to: ${mode.name}")
            Toast.makeText(this, "Quality: ${mode.name}", Toast.LENGTH_SHORT).show()
            statusText.text = "Mode: ${mode.name} - Ready"
            Log.d(TAG, "Quality selection completed, connect button enabled: ${connectButton.isEnabled}")
        } else {
            Log.w(TAG, "Cannot change quality while previewing")
        }
    }

    private fun updateQualityDisplay() {
        val mode = cameraModes[performanceMode]
        qualityText.text = "Quality: ${mode.name}"
    }

    private fun updateAspectRatio() {
        val mode = cameraModes[performanceMode]
        cameraView.setAspectRatio(mode.width, mode.height)
    }

    private fun startFpsMonitoring() {
        // Start FPS update timer
        val fpsTimer = object : Runnable {
            override fun run() {
                updateFpsDisplay()
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.post(fpsTimer)

        // Monitor camera frames
        cameraView.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "Surface texture available: ${width}x${height}")
            }

            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "Surface texture size changed: ${width}x${height}")
            }

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                return true
            }

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                onFrameUpdate()
            }
        }
    }

    private fun onFrameUpdate() {
        if (isPreview) {
            frames++
            val currentTime = System.currentTimeMillis()

            if (lastMs > 0) {
                val frameTime = currentTime - lastMs
                runOnUiThread {
                    msText.text = "MS: $frameTime"
                }
            }
            lastMs = currentTime
        }
    }

    private fun updateFpsDisplay() {
        val currentTime = System.currentTimeMillis()
        if (startTime > 0 && isPreview) {
            val timeDiff = currentTime - startTime
            if (timeDiff > 0) {
                val fps = (frames * 1000f / timeDiff).toInt()
                fpsText.text = "FPS: $fps"
            }
        }
        startTime = currentTime
        frames = 0
    }

    private fun resetFpsCounters() {
        frames = 0
        startTime = System.currentTimeMillis()
        lastMs = 0L
        fpsText.text = "FPS: --"
        msText.text = "MS: --"
    }

    private fun resetFpsDisplay() {
        fpsText.text = "FPS: --"
        msText.text = "MS: --"
    }

    private fun getCameraRequest(): CameraRequest {
        val mode = cameraModes[performanceMode]
        Log.d(TAG, "Creating camera request: ${mode.width}x${mode.height}@${mode.targetFps}fps")
        return CameraRequest.Builder()
            .setPreviewWidth(mode.width)
            .setPreviewHeight(mode.height)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_AUTO)
            .setAspectRatioShow(true)
            .create()
    }

    private fun openCamera() {
        Log.d(TAG, "openCamera() called, connectedCamera = $connectedCamera")
        Log.d(TAG, "Connect button enabled: ${connectButton.isEnabled}, isPreview: $isPreview")

        connectedCamera?.let { camera ->
            val mode = cameraModes[performanceMode]
            Log.d(TAG, "Opening camera with ${mode.name} (${mode.width}x${mode.height})")

            try {
                val request = getCameraRequest()
                Log.d(TAG, "Camera request created, calling openCamera...")

                camera.openCamera(cameraView, request)
                Log.d(TAG, "openCamera call completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error opening camera: ${e.message}", e)
                Toast.makeText(this, "Error opening camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } ?: run {
            Log.w(TAG, "connectedCamera is null!")
            Toast.makeText(this, "No device connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPreview() {
        Log.d(TAG, "stopPreview() called")
        connectedCamera?.let { camera ->
            Log.d(TAG, "Closing camera...")
            camera.closeCamera()
        } ?: run {
            Log.w(TAG, "No camera to close")
        }
    }

    private fun updateUI(previewing: Boolean) {
        Log.d(TAG, "updateUI(previewing: $previewing)")
        runOnUiThread {
            isPreview = previewing
            connectButton.text = if (previewing) "Disconnect" else "Connect"
            connectButton.isEnabled = true // Always keep enabled when not null camera
            qualityButton.isEnabled = !previewing
            if (previewing) {
                qualityOverlay.visibility = View.GONE
            }
            Log.d(TAG, "UI updated - isPreview: $isPreview, button text: ${connectButton.text}, button enabled: ${connectButton.isEnabled}")
        }
    }
}