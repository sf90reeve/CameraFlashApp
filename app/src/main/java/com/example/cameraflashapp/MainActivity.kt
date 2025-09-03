package com.example.cameraflashapp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var textureView: TextureView
    private lateinit var patientIdInput: EditText
    private lateinit var circleOverlay: CircleOverlay
    private lateinit var overlayToggleBtn: Button
    private lateinit var captureBtn: Button
    private lateinit var galleryBtn: Button
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var focusSeekBar: SeekBar

    private var overlayEnabled = false
    private val piUrl = "http://10.238.221.95:5000/flash"

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var cameraId: String? = null
    private var previewSize: Size? = null
    private var captureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null

    private lateinit var imageReader: ImageReader
    private var currentZoomRect: Rect? = null

    private val backgroundHandlerThread = HandlerThread("CameraBackground").apply { start() }
    private val backgroundHandler = Handler(backgroundHandlerThread.looper)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openCamera() else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.previewView)
        patientIdInput = findViewById(R.id.patientIdInput)
        overlayToggleBtn = findViewById(R.id.filterToggleBtn)
        captureBtn = findViewById(R.id.captureBtn)
        galleryBtn = findViewById(R.id.galleryBtn)
        zoomSeekBar = findViewById(R.id.zoomSeekBar)

        // Circle overlay
        val rootLayout: FrameLayout = findViewById(R.id.overlayContainer)
        circleOverlay = CircleOverlay(this)
        rootLayout.addView(circleOverlay)
        circleOverlay.visibility = View.GONE

        // Manual focus bar
        focusSeekBar = SeekBar(this).apply {
            max = 100
            progress = 0
        }
        (findViewById<ConstraintLayout>(R.id.rootLayout)).addView(focusSeekBar)
        val params = focusSeekBar.layoutParams as ConstraintLayout.LayoutParams
        params.width = ConstraintLayout.LayoutParams.MATCH_PARENT
        params.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
        params.bottomToTop = R.id.captureBtn
        focusSeekBar.layoutParams = params

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = cameraManager.cameraIdList.first { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            textureView.surfaceTextureListener = surfaceTextureListener
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        captureBtn.setOnClickListener {
            triggerExternalFlash()
            Handler(Looper.getMainLooper()).postDelayed({
                takePhoto()
            }, 200)
        }

        galleryBtn.setOnClickListener { openGallery() }

        overlayToggleBtn.setOnClickListener {
            overlayEnabled = !overlayEnabled
            circleOverlay.visibility = if (overlayEnabled) View.VISIBLE else View.GONE
            overlayToggleBtn.text = if (overlayEnabled) "Overlay ON" else "Overlay OFF"
        }

        // Zoom control
        zoomSeekBar.max = 100
        zoomSeekBar.progress = 0
        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)
                    val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
                    val zoomFactor = 1f + (progress / 100f) * (maxZoom - 1f)

                    val rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
                    val cropW = (rect.width() / zoomFactor).toInt()
                    val cropH = (rect.height() / zoomFactor).toInt()
                    val cropX = (rect.width() - cropW) / 2
                    val cropY = (rect.height() - cropH) / 2
                    val zoomRect = Rect(cropX, cropY, cropX + cropW, cropY + cropH)

                    currentZoomRect = zoomRect
                    captureRequestBuilder?.set(CaptureRequest.SCALER_CROP_REGION, zoomRect)
                    captureSession?.setRepeatingRequest(captureRequestBuilder!!.build(), null, backgroundHandler)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Manual focus control
        focusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val minFocus = cameraManager.getCameraCharacteristics(cameraId!!)
                        .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    val newFocusValue = (progress / 100f) * minFocus
                    captureRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    captureRequestBuilder?.set(CaptureRequest.LENS_FOCUS_DISTANCE, newFocusValue)
                    captureSession?.setRepeatingRequest(captureRequestBuilder!!.build(), null, backgroundHandler)
                    Log.d(TAG, "Manual focus set: $newFocusValue")
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // Surface listener
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
    }

    private fun openCamera() {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)
            previewSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                .getOutputSizes(SurfaceTexture::class.java)[0]

            imageReader = ImageReader.newInstance(previewSize!!.width, previewSize!!.height,
                ImageFormat.JPEG, 1)
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                val buffer: ByteBuffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                saveImage(bytes)
                image.close()
            }, backgroundHandler)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            cameraManager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed: ${e.message}", e)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startPreview()
        }
        override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
        override fun onError(camera: CameraDevice, error: Int) { camera.close(); cameraDevice = null }
    }

    private fun startPreview() {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize!!.width, previewSize!!.height)
        val surface = Surface(texture)

        captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequestBuilder!!.addTarget(surface)

        cameraDevice!!.createCaptureSession(listOf(surface, imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    captureRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    captureSession!!.setRepeatingRequest(captureRequestBuilder!!.build(), null, backgroundHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "Preview failed", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)
    }

    private fun takePhoto() {
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader.surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

            currentZoomRect?.let {
                captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, it)
            }

            // keep current manual focus
            val focusMode = captureRequestBuilder?.get(CaptureRequest.CONTROL_AF_MODE)
            if (focusMode != null) captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, focusMode)

            val focusDist = captureRequestBuilder?.get(CaptureRequest.LENS_FOCUS_DISTANCE)
            if (focusDist != null) captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist)

            captureSession?.capture(captureBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto failed: ${e.message}", e)
        }
    }

    // ✅ Safe MediaStore-based save
    private fun saveImage(bytes: ByteArray) {
        val patientId = patientIdInput.text.toString().trim()
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val fileName = if (patientId.isNotEmpty()) "${patientId}_$timeStamp.jpg" else "IMG_$timeStamp.jpg"

        // Decode to bitmap
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Fix orientation
        val characteristics = cameraManager.getCameraCharacteristics(cameraId!!)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deviceRotation = (windowManager.defaultDisplay.rotation * 90) % 360
        val rotation = (sensorOrientation - deviceRotation + 360) % 360

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

        // ✅ Save via MediaStore
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraFlashApp")
            }
        }

        val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { outStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outStream)
            }
            runOnUiThread {
                Toast.makeText(this, "Saved: $fileName", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerExternalFlash() {
        Thread {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(piUrl).get().build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    Log.d(TAG, "Flash response: ${response.code} ${response.message} $body")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Flash request failed: ${e.message}", e)
            }
        }.start()
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try { startActivity(intent) }
        catch (e: Exception) { Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraDevice?.close()
        backgroundHandlerThread.quitSafely()
    }

    inner class CircleOverlay(context: Context) : View(context) {
        private val outlinePaint = Paint().apply {
            color = 0xFF00FF00.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = Math.min(width, height) / 2.25f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, radius, outlinePaint)
        }
    }
}
