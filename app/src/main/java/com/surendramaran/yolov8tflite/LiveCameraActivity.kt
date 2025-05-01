package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Surface // Needed for rotation constant
// Import CheckBox or Switch depending on your layout
import android.widget.CheckBox // Or androidx.appcompat.widget.SwitchCompat
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.NavigationIntentActivity.Companion
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding // Verify this binding class matches your layout file name
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH


class LiveCameraActivity : AppCompatActivity(),
    Detector.DetectorListener, // Implement DetectorListener
    TextToSpeech.OnInitListener { // Implement OnInitListener

    // Use ViewBinding

    private val COOLDOWN_MS = 3000L // Cooldown for TTS messages with same content
    private var lastMessages = mutableSetOf<String>() // Track recently spoken messages by content

    // Alert history for spam prevention
    private val HISTORY_EXPIRATION_MS = 6000L // Cooldown for alerting the *same object* (key)


    // Minimum time between any two spoken alerts
    private val FEEDBACK_DELAY_MS = 2000L

    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null
    private lateinit var cameraExecutor: ExecutorService

    // TTS components
    private lateinit var tts: TextToSpeech
    private var ttsInitialized = false
    private val ttsQueue = LinkedList<String>()
    private var isTtsSpeaking = false
    private var lastSpokenUtteranceId: String? = null // Track last spoken ID for debugging

    // Alert history & Timing
    // Use HashMap for alertHistory for clearer type
    private val alertHistory = HashMap<String, Long>() // Map<Key, Timestamp>
    private var lastFeedbackTime = 0L // Timestamp of the last successful TTS speak call

    // Timer Handler for scan duration
    private val scanHandler = Handler(Looper.getMainLooper())
    private var scanDuration = -1L


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        // Inflate layout using ViewBinding
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            Log.e(TAG, "Error inflating layout (activity_main.xml?). Ensure binding class matches.", e)
            Toast.makeText(this, "Layout Error", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Get scan duration from Intent
        scanDuration = intent.getLongExtra("SCAN_DURATION", -1L)
        Log.d(TAG, "Received scan duration: $scanDuration ms")

        initializeDependencies() // Init TTS, Executor, Detector

        // Request permissions and start camera flow
        if (allPermissionsGranted()) {
            Log.d(TAG, "Permissions already granted, starting camera.")
            startCamera()
        } else {
            Log.d(TAG, "Requesting camera permissions.")
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }

        bindListeners() // Setup UI listeners like GPU switch

        // Start the exit timer if duration is valid
        setupExitTimer()
    }

    // --- Initialization ---
    private fun initializeDependencies() {
        initializeTTS()
        initializeCameraExecutor()
        setupDetector() // Starts detector init on background thread
    }

    private fun initializeTTS() {
        Log.d(TAG, "Initializing TTS...")
        tts = TextToSpeech(this, this) // 'this' is the OnInitListener
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language not supported.")
                ttsInitialized = false
            } else {
                ttsInitialized = true
                Log.i(TAG, "TTS Initialized.")
                tts.setOnUtteranceProgressListener(ttsProgressListener)
//                speakOnInit("Obstacle detection started.") // Announce start
            }
        } else {
            Log.e(TAG, "TTS Initialization failed (Status: $status).")
            ttsInitialized = false
        }
    }

    private fun speakOnInit(text: String) {
        // Helper to speak initial messages safely
        if (ttsInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "INIT_${System.currentTimeMillis()}")
        }
    }

    private fun initializeCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "CameraExecutor initialized.")
    }

    private fun setupDetector() {
        Log.d(TAG, "Setting up Detector...")
        cameraExecutor.execute { // Run on background thread
            try {
                // Pass applicationContext to avoid leaks, ensure 'this' listener is correct
                detector = Detector(applicationContext, MODEL_PATH, LABELS_PATH, this)
                Log.i(TAG, "Detector initialized successfully on background thread.")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing detector", e)
                runOnUiThread {
                    Toast.makeText(this, "Detector Error", Toast.LENGTH_SHORT).show()
                    // Optionally finish() if detector is critical and failed
                }
            }
        }
    }

    // --- Camera Setup & Binding ---
    private fun startCamera() {
        Log.d(TAG, "startCamera called.")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.i(TAG, "CameraProvider obtained.")
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get CameraProvider", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: run { Log.e(TAG, "CameraProvider null in bind."); return }
        val displayRotation = binding.viewFinder.display?.rotation ?: Surface.ROTATION_0

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        // Setup Preview use case
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Or RATIO_16_9 if preferred
            .setTargetRotation(displayRotation)
            .build()

        // Setup ImageAnalysis use case
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3) // Match Preview
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(displayRotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) // Format needed by Detector
            .build()

        // --- Set Analyzer (Restored Original Logic) ---
        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val currentDetector = detector // Local val for thread safety/null check
            if (currentDetector == null || isFinishing) { imageProxy.close(); return@setAnalyzer }

            // Convert ImageProxy to Bitmap (Original method)
            val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            // *** Crucial: Use imageProxy.use for automatic closing ***
            imageProxy.use {
                // Rewind buffer before copying
                it.planes[0].buffer.rewind()
                bitmapBuffer.copyPixelsFromBuffer(it.planes[0].buffer)
            }
            // imageProxy is now closed implicitly by .use{}

            // Create rotation matrix (Original method)
            val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }

            // Rotate bitmap (Original method)
            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)

            // Detect (Original method call)
            currentDetector.detect(rotatedBitmap)
        }
        // --- End of Analyzer Logic ---

        try {
            provider.unbindAll() // Essential before rebinding
            camera = provider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            Log.i(TAG, "Camera use cases bound.")
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            Toast.makeText(this, "Camera Binding Error.", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Permission Handling ---
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED // Use 'this' context
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true) {
            Log.i(TAG, "Camera permission granted via launcher.")
            startCamera()
        } else {
            Log.e(TAG, "Camera permission denied via launcher.")
            Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
            finish() // Exit if essential permission denied
        }
    }

    // --- UI Listeners ---
    private fun bindListeners() {
        // Example GPU switch listener from original
        binding.isGpu?.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "GPU switch toggled: $isChecked")
            cameraExecutor.submit { detector?.restart(isGpu = isChecked) }
            // Update background color if needed (check R.color exists)
            // buttonView.setBackgroundColor(...)
        }
    }

    // --- TTS Processing ---
    private val ttsProgressListener = object : UtteranceProgressListener() {
        // ... (Keep existing listener logic from previous version - onDone triggers processNextAlert) ...
        override fun onStart(utteranceId: String?) {
            Log.d(TAG_TTS, "TTS Start: $utteranceId")
            isTtsSpeaking = true // Mark as speaking when starting
        }

        override fun onDone(utteranceId: String?) {
            Log.d(TAG_TTS, "TTS Done: $utteranceId")
            // Check if it's the utterance we were tracking
            if (utteranceId == lastSpokenUtteranceId) {
                isTtsSpeaking = false
            }
            // Always try to process next in case queue has items
            runOnUiThread { processNextAlert() }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.e(TAG_TTS, "TTS Error ($errorCode) for $utteranceId")
            if (utteranceId == lastSpokenUtteranceId) {
                isTtsSpeaking = false
            }
            runOnUiThread { processNextAlert() } // Try next even on error
        }
        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { onError(utteranceId, -1) }
    }


    private fun processNextAlert() {
        // ... (Keep existing logic from previous version - checks queue, cooldown, speaks) ...
        if (!ttsInitialized || isTtsSpeaking || ttsQueue.isEmpty() || isFinishing) { return }
        val message = ttsQueue.poll() ?: return
        if (lastMessages.contains(message)) { processNextAlert(); return } // Skip recent message
        Log.d(TAG, "TTS Speaking: $message")
        lastMessages.add(message)
        isTtsSpeaking = true
        val utteranceId = "obstacle_${System.currentTimeMillis()}"
        Handler(Looper.getMainLooper()).postDelayed({ lastMessages.remove(message) }, COOLDOWN_MS)
        tts.speak(message, TextToSpeech.QUEUE_ADD, null, utteranceId) // Use QUEUE_ADD
    }

    // --- Detection Result Handling ---
    private fun cleanupExpiredHistory() { /* ... Keep existing logic ... */
        val currentTime = System.currentTimeMillis(); alertHistory.entries.removeAll { (_, timestamp) -> currentTime - timestamp > HISTORY_EXPIRATION_MS }
    }

    // Generate Alert Key (Restore original logic - relies on position)
    private fun generateAlertKey(box: BoundingBox): String {
        val positionKey = when {
            box.y2 > 0.8f -> "floor"
            box.cx < 0.3f -> "left" // Use 0.3/0.7 thresholds from original
            box.cx > 0.7f -> "right"
            else -> "center"
        }
        return "${box.clsName}_$positionKey" // Combine class and position
    }

    // Generate Alert Message (Restore original logic)
    private fun generateAlertMessage(box: BoundingBox): String {
        val position = when {
            box.y2 > 0.8f -> "ahead on the floor"
            box.cx < 0.3f -> "on your left"
            box.cx > 0.7f -> "on your right"
            else -> "ahead"
        }
        // Use clsName directly, assuming it's already the display name after mapping
        return "${box.clsName.replace("_", " ")} $position"
    }

    // Should Alert (Restore original logic - uses position key)
    private fun shouldAlert(box: BoundingBox): Boolean {
        val key = generateAlertKey(box) // Uses class + position key
        val currentTime = System.currentTimeMillis()
        val lastAlertTime = alertHistory[key]
        return if (lastAlertTime == null || (currentTime - lastAlertTime > HISTORY_EXPIRATION_MS)) {
            true
        } else {
            false
        }
    }

    private fun updateAlertHistory(box: BoundingBox) {
        val key = generateAlertKey(box) // Uses class + position key
        alertHistory[key] = System.currentTimeMillis()
    }

    // --- Detection Result Handling (Detector.DetectorListener) ---
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread

            binding.inferenceTime?.text = "${inferenceTime}ms"
            DetectionUtils.cleanupExpiredHistory(alertHistory) // Use helper

            // 1. Map to display names & Filter out ignored classes (null display name)
            val displayBoxes = boundingBoxes.mapNotNull { box ->
                DetectionUtils.getDisplayClassName(box.clsName)?.let { displayName ->
                    box.copy(clsName = displayName)
                }
            }

            // 2. Filter further by area threshold & Prioritize
            val prioritizedBoxes = DetectionUtils.filterAndPrioritize(displayBoxes)

            // 3. Select top N for display
            val boxesForDisplay = prioritizedBoxes.take(DetectionUtils.MAX_RESULTS_TO_DISPLAY)

            // 4. Update Overlay with boxes for display
            binding.overlay?.apply {
                setResults(boxesForDisplay)
                invalidate()
            }

            // 5. Select the single most important box to potentially speak
            val boxToSpeak = prioritizedBoxes.firstOrNull() // Get the highest priority one

            // 6. Check if this specific box should be alerted (history cooldown)
            if (boxToSpeak != null && DetectionUtils.shouldAlert(alertHistory, boxToSpeak)) {
                val message = DetectionUtils.generateAlertMessage(boxToSpeak)

                // Add to queue (queue will likely only hold 1 item due to flush/interval)
                // Check queue content *before* adding to prevent exact duplicate utterances
                // if (!ttsQueue.contains(message)) { // This check might be too restrictive with timing
                Log.d(TAG, "Adding to TTS Queue: $message")
                ttsQueue.add(message)
                DetectionUtils.updateAlertHistory(alertHistory, boxToSpeak) // Update history
                // } else {
                // Log.v(TAG, "Message already in queue: $message")
                // }
            }

            // 7. Attempt to process the queue (will speak if conditions met)
            processNextAlert()
        }
    }


    override fun onEmptyDetect() {
        runOnUiThread {
            if (!isFinishing) { binding.overlay?.clear(); binding.overlay?.invalidate() }
        }
    }

    // --- Activity Lifecycle ---
    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume")
        if (!isFinishing) { // Check if activity is finishing
            if (allPermissionsGranted()) {
                // Restart camera if needed (was stopped in onPause for battery)
                startCamera()
            } else {
                // Permissions might have been revoked while paused
                Log.w(TAG, "Permissions not granted onResume, requesting again.")
                // requestPermissionLauncher.launch(REQUIRED_PERMISSIONS) // Avoid re-request loop if user keeps denying
                Toast.makeText(this,"Camera permission needed", Toast.LENGTH_SHORT).show()
                finish() // Exit if permission missing on resume
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "onPause")
        // Stop analysis and unbind to save battery when not visible
        // This is important for the timed exit to work cleanly
        try {
            cameraProvider?.unbindAll()
            Log.i(TAG, "Camera unbound in onPause.")
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding camera in onPause", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy: Removing callbacks and shutting down resources.")
        // Stop the scan timer callbacks forcefully
        scanHandler.removeCallbacksAndMessages(null)

        // Shutdown camera executor
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
            Log.i(TAG, "CameraExecutor shut down.")
        }

        // Close detector
        detector?.close()
        Log.i(TAG, "Detector closed.")

        // Shutdown TTS
        shutdownTTS() // Use helper
    }

    private fun shutdownTTS() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
            Log.i(LiveCameraActivity.TAG, "TTS shut down.")
        }
    }

    // --- Exit Timer ---
    private fun setupExitTimer() {
        if (scanDuration > 0) {
            Log.d(TAG, "Setting up exit timer for $scanDuration ms")
            scanHandler.postDelayed({
                Log.i(TAG, "Scan duration elapsed. Finishing LiveCameraActivity.")
                speakOnInit("Scan complete.") // Announce completion
                // Short delay for TTS before finishing
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing) finish()
                }, 1000) // 1 second delay
            }, scanDuration)
        } else {
            Log.d(TAG, "No valid scan duration, timer not set.")
        }
    }


    // --- Companion Object ---
    companion object {
        private const val TAG = "LiveCameraActivity"
        private const val TAG_TTS = "LiveCameraTTS"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}