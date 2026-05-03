package com.am2.am2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Camera
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.am2.am2.databinding.ActivityVideoBinding
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.sqrt

@Suppress("DEPRECATION")
class VideoActivity : BaseActivity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private lateinit var binding: ActivityVideoBinding
    private var camera: Camera? = null
    private var isStreaming = false
    private lateinit var prefs: SharedPreferences
    private var pttHardwareKey: Int = -1
    private var pttToggleEnabled = false
    private var lastFrameTime = 0L
    private val FRAME_INTERVAL = 200L

    private var currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK
    private var hasFrontCamera = false
    private var hasBackCamera = false
    
    private val videoProcessingExecutor = Executors.newSingleThreadExecutor()
    private val decodingExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)
        pttHardwareKey = prefs.getInt("ptt_key", -1)
        pttToggleEnabled = prefs.getBoolean("ptt_toggle", false)

        detectCameras()
        binding.svLocalPreview.holder.addCallback(this)

        setupObservers()
        setupListeners()
        applyUiVisibility()

        checkAndRequestPermissions()
        
        setupBackPressedHandling()
    }

    private fun setupBackPressedHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitToMain()
            }
        })
    }

    private fun exitToMain() {
        if (WebSocketManager.isPtpVideo.value == true) {
            WebSocketManager.endPtp()
        }
        stopVideoStream()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    private fun detectCameras() {
        val numberOfCameras = Camera.getNumberOfCameras()
        val info = Camera.CameraInfo()
        for (i in 0 until numberOfCameras) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) hasFrontCamera = true
            else if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) hasBackCamera = true
        }
        currentCameraId = if (hasFrontCamera) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
        binding.btnSwitchCamera.visibility = if (numberOfCameras > 1) View.VISIBLE else View.GONE
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 2001)
    }

    private fun checkCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun applyUiVisibility() {
        val dm = resources.displayMetrics
        val widthInches = dm.widthPixels.toDouble() / dm.xdpi
        val heightInches = dm.heightPixels.toDouble() / dm.ydpi
        val screenInches = sqrt(widthInches * widthInches + heightInches * heightInches)

        val showVirtualPttPref = prefs.getBoolean("show_virtual_ptt", true)
        binding.layoutPttContainer.visibility = if (showVirtualPttPref && screenInches >= 3.0) View.VISIBLE else View.GONE
    }

    private fun updatePttUi() {
        if (isFinishing) return

        val isMeTalking = WebSocketManager.isTalking.value ?: false
        val speakers = WebSocketManager.activeSpeakersList.value
        val hasSpeakers = !speakers.isNullOrEmpty()
        val hasStreamers = WebSocketManager.activeVideoStreamers.value?.isNotEmpty() ?: false
        val isRxOnly = WebSocketManager.isRxOnly.value ?: false
        val isPtp = WebSocketManager.ptpTargetId.value != null
        val voxEnabled = prefs.getBoolean("vox_enabled", false)

        binding.btnPttVideo.isPressed = isMeTalking
        binding.btnPttVideo.isActivated = (hasSpeakers || hasStreamers) && !isMeTalking

        val iconColor = when {
            isMeTalking -> Color.RED
            hasSpeakers || hasStreamers -> Color.GREEN
            else -> Color.GRAY
        }
        binding.btnPttIcon.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN)

        binding.btnPttVideo.alpha = if ((isRxOnly || voxEnabled) && !isPtp) 0.5f else 1.0f

        if (isMeTalking || hasSpeakers || hasStreamers) {
            if (binding.btnPttBackground.animation == null) {
                binding.btnPttBackground.startAnimation(AnimationUtils.loadAnimation(this, R.anim.rotate_infinite))
            }
        } else {
            binding.btnPttBackground.clearAnimation()
        }
    }

    private fun setupObservers() {
        WebSocketManager.activeVideoStreamers.observe(this) { streamers ->
            if (streamers.isNotEmpty()) {
                binding.ivIncomingVideo.visibility = View.VISIBLE
                binding.cvLocalPreview.visibility = View.GONE
                binding.tvStreamerName.text = "LIVE: " + streamers.last()
            } else {
                binding.ivIncomingVideo.visibility = View.GONE
                binding.cvLocalPreview.visibility = View.VISIBLE
                val ptpName = WebSocketManager.ptpTargetName.value
                binding.tvStreamerName.text = if (ptpName != null) "PRIVATE $ptpName" else "PREVIEW"
                binding.ivIncomingVideo.setImageBitmap(null)
            }
            updatePttUi()
        }

        WebSocketManager.isTalking.observe(this) { talking ->
            if (!talking && isStreaming) {
                isStreaming = false
                WebSocketManager.stopVideoStreaming()
            }
            updatePttUi()
        }

        WebSocketManager.activeSpeakersList.observe(this) { updatePttUi() }
        WebSocketManager.isRxOnly.observe(this) { updatePttUi() }

        WebSocketManager.incomingVideoFrame.observe(this) { pair ->
            if (isFinishing) return@observe
            val data = pair.second
            decodingExecutor.execute {
                try {
                    val rawBitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return@execute
                    runOnUiThread { 
                        if (!isFinishing) binding.ivIncomingVideo.setImageBitmap(rawBitmap) 
                        else rawBitmap.recycle()
                    }
                } catch (e: Exception) { Log.e("VideoActivity", "Decoding error", e) }
            }
        }

        WebSocketManager.ptpTargetId.observe(this) { id ->
            if (id == null && WebSocketManager.isPtpVideo.value == true) {
                Toast.makeText(this, "Panggilan video berakhir", Toast.LENGTH_SHORT).show()
                exitToMain()
            }
            updatePttUi()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupListeners() {
        binding.btnPttVideo.setOnTouchListener { v, event ->
            val isRxOnly = WebSocketManager.isRxOnly.value == true
            val isPtpActive = WebSocketManager.ptpTargetId.value != null
            val voxEnabled = prefs.getBoolean("vox_enabled", false)

            if ((isRxOnly || voxEnabled) && !isPtpActive) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val msg = if (voxEnabled) "Mode VOX Aktif: Tombol PTT Dinonaktifkan" else "Mode RX Only"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (pttToggleEnabled) {
                        if (!isStreaming) { v.isPressed = true; startPtt() } 
                        else { v.isPressed = false; stopPtt() }
                    } else { v.isPressed = true; startPtt() }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!pttToggleEnabled) { v.isPressed = false; stopPtt() }
                }
            }
            true
        }
        binding.btnSwitchCamera.setOnClickListener { switchCamera() }
    }

    private fun startPtt() {
        startVideoStream()
    }

    private fun stopPtt() {
        stopVideoStream()
    }

    private fun switchCamera() {
        if (Camera.getNumberOfCameras() < 2) return
        currentCameraId = if (currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
        releaseCamera()
        openCamera()
    }

    private fun openCamera() {
        if (!checkCameraPermission()) return
        try {
            camera = Camera.open(currentCameraId)
            setCameraDisplayOrientation()
            camera?.setPreviewDisplay(binding.svLocalPreview.holder)
            camera?.setPreviewCallback(this)
            camera?.startPreview()
        } catch (e: Exception) { Log.e("VideoActivity", "Camera error", e) }
    }

    private fun releaseCamera() {
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        } catch (e: Exception) {}
        camera = null
    }

    private fun startVideoStream() {
        if (isStreaming) return
        isStreaming = true
        WebSocketManager.startVideoStreaming()
        startService(Intent(this, PTTService::class.java).apply { action = PTTService.ACTION_START_PTT })
    }

    private fun stopVideoStream() {
        if (!isStreaming) return
        isStreaming = false
        WebSocketManager.stopVideoStreaming()
        startService(Intent(this, PTTService::class.java).apply { action = PTTService.ACTION_STOP_PTT })
    }

    private fun setCameraDisplayOrientation() {
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(currentCameraId, info)
        val rotation = windowManager.defaultDisplay.rotation
        var degrees = 0
        when (rotation) {
            Surface.ROTATION_0 -> degrees = 0
            Surface.ROTATION_90 -> degrees = 90
            Surface.ROTATION_180 -> degrees = 180
            Surface.ROTATION_270 -> degrees = 270
        }
        var result = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            (info.orientation + degrees) % 360
            (360 - ((info.orientation + degrees) % 360)) % 360
        } else (info.orientation - degrees + 360) % 360
        camera?.setDisplayOrientation(result)
        binding.svLocalPreview.scaleX = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) -1f else 1f
    }

    override fun surfaceCreated(holder: SurfaceHolder) { openCamera() }
    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
        if (holder.surface == null) return
        try {
            camera?.stopPreview()
            setCameraDisplayOrientation()
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()
        } catch (e: Exception) {}
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { releaseCamera() }

    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        if (!isStreaming || data == null || isFinishing) return
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < FRAME_INTERVAL) return
        lastFrameTime = now

        videoProcessingExecutor.execute {
            var bitmap: Bitmap? = null
            var processedBitmap: Bitmap? = null
            try {
                val parameters = camera?.parameters ?: return@execute
                val width = parameters.previewSize.width
                val height = parameters.previewSize.height
                val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
                val options = BitmapFactory.Options().apply { if (width > 1280) inSampleSize = 2 }
                bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size(), options) ?: return@execute
                val matrix = Matrix()
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(currentCameraId, info)
                matrix.postRotate(info.orientation.toFloat())
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) matrix.postScale(-1f, 1f)
                val scale = 480f / Math.max(bitmap.width, bitmap.height).toFloat()
                if (scale < 1.0f) matrix.postScale(scale, scale)
                processedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                val finalOut = ByteArrayOutputStream()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) processedBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 70, finalOut)
                else @Suppress("DEPRECATION") processedBitmap.compress(Bitmap.CompressFormat.WEBP, 70, finalOut)
                WebSocketManager.sendVideoFrame(finalOut.toByteArray())
            } catch (e: Exception) { Log.e("VideoActivity", "Frame error", e)
            } finally { bitmap?.recycle(); processedBitmap?.recycle() }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == pttHardwareKey && pttHardwareKey != -1 && event?.repeatCount == 0) {
            val isRxOnly = WebSocketManager.isRxOnly.value == true
            val isPtpActive = WebSocketManager.ptpTargetId.value != null
            val voxEnabled = prefs.getBoolean("vox_enabled", false)

            if ((isRxOnly || voxEnabled) && !isPtpActive) {
                val msg = if (voxEnabled) "Mode VOX Aktif: Tombol PTT Dinonaktifkan" else "Mode RX Only"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                return true
            }

            if (pttToggleEnabled) { if (!isStreaming) startVideoStream() else stopVideoStream() } 
            else startVideoStream()
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, 0)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == pttHardwareKey && pttHardwareKey != -1) {
            val isRxOnly = WebSocketManager.isRxOnly.value == true
            val isPtpActive = WebSocketManager.ptpTargetId.value != null

            if ((isRxOnly || prefs.getBoolean("vox_enabled", false)) && !isPtpActive) return true

            if (!pttToggleEnabled) {
                stopVideoStream()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        updatePttUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVideoStream()
        videoProcessingExecutor.shutdown()
        decodingExecutor.shutdown()
        binding.ivIncomingVideo.setImageBitmap(null)
    }
}
