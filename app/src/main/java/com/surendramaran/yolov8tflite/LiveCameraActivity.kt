package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.*


class LiveCameraActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tts: TextToSpeech
    private var lastSpokenTime = 0L

    private val ttsQueue = LinkedList<String>()
    private var isTtsSpeaking = false
    private val COOLDOWN_MS = 3000 // 3 seconds cooldown
    private var lastMessages = mutableSetOf<String>()

    // History tracking
    private val alertHistory = mutableMapOf<String, Long>()
    private val HISTORY_EXPIRATION_MS = 8000L // 8 seconds



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeTTS()
        initializeCamera()
        bindListeners()
    }

    private fun initializeTTS() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.ERROR) {
                tts.language = Locale.US
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        runOnUiThread {
                            isTtsSpeaking = false
                            processNextAlert()
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        runOnUiThread {
                            isTtsSpeaking = false
                            processNextAlert()
                        }
                    }
                })
            } else {
                Log.e(TAG, "TextToSpeech initialization failed")
            }
        }
    }



    private fun initializeCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width,
                imageProxy.height,
                Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
    }

    private fun bindListeners() {
        binding.apply {
            isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
                cameraExecutor.submit {
                    detector?.restart(isGpu = isChecked)
                }
                if (isChecked) {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.orange))
                } else {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.gray))
                }
            }
        }
    }

    private fun processNextAlert() {
        if (!isTtsSpeaking && ttsQueue.isNotEmpty()) {
            val message = ttsQueue.poll()
            lastMessages.add(message)
            isTtsSpeaking = true

            Handler(Looper.getMainLooper()).postDelayed({
                lastMessages.remove(message)
            }, COOLDOWN_MS.toLong())

            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, message)
        }
    }

    private fun generateAlertMessage(box: BoundingBox): String {
        val position = when {
            box.y2 > 0.8f -> "ahead on the floor"
            box.cx < 0.3f -> "on your left"
            box.cx > 0.7f -> "on your right"
            else -> "ahead"
        }
        return "${box.clsName.replace("_", " ")} $position"
    }

    private fun cleanupExpiredHistory() {
        val currentTime = System.currentTimeMillis()
        alertHistory.entries.removeAll { (_, timestamp) ->
            currentTime - timestamp > HISTORY_EXPIRATION_MS
        }
    }

    private fun generateAlertKey(box: BoundingBox): String {
        val positionKey = when {
            box.y2 > 0.8f -> "floor"
            box.cx < 0.3f -> "left"
            box.cx > 0.7f -> "right"
            else -> "center"
        }
        return "${box.clsName}_$positionKey"
    }

    private fun shouldAlert(box: BoundingBox): Boolean {
        val key = generateAlertKey(box)
        val currentTime = System.currentTimeMillis()

        return when {
            !alertHistory.containsKey(key) -> true
            currentTime - alertHistory[key]!! > HISTORY_EXPIRATION_MS -> true
            else -> false
        }
    }

    private fun updateAlertHistory(box: BoundingBox) {
        val key = generateAlertKey(box)
        alertHistory[key] = System.currentTimeMillis()
    }

//    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
//        runOnUiThread {
//            binding.inferenceTime.text = "${inferenceTime}ms"
//            cleanupExpiredHistory()
//
//            val prioritized = DetectionUtils.filterAndPrioritize(boundingBoxes)
//                .take(1)
//                .filter { shouldAlert(it) }
//
//            binding.overlay.apply {
//                setResults(prioritized)
//                invalidate()
//            }
//
//            prioritized.forEach { box ->
//                val message = generateAlertMessage(box)
//                if (!ttsQueue.contains(message) && !lastMessages.contains(message)) {
//                    ttsQueue.add(message)
//                    updateAlertHistory(box)
//                }
//            }
//
//            processNextAlert()
//        }
//    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            cleanupExpiredHistory()

            // Add class name filtering
            val filteredBoxes = boundingBoxes.map { box ->
                val displayName = DetectionUtils.getDisplayClassName(box.clsName)
                box.copy(clsName = displayName)
            }

            val prioritized = DetectionUtils.filterAndPrioritize(filteredBoxes)
                .take(1)
                .filter { shouldAlert(it) }

            binding.overlay.apply {
                setResults(prioritized)
                invalidate()
            }

            prioritized.forEach { box ->
                val message = DetectionUtils.generateAlertMessage(box)
                if (!ttsQueue.contains(message) && !lastMessages.contains(message)) {
                    ttsQueue.add(message)
                    updateAlertHistory(box)
                }
            }

            processNextAlert()
        }
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()
    }
}