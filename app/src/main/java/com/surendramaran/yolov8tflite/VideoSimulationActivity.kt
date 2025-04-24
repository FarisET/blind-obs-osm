package com.surendramaran.yolov8tflite

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityVideoSimulationBinding
import java.util.LinkedList
import java.util.Locale
import kotlin.math.min

class VideoSimulationActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityVideoSimulationBinding
    private lateinit var detector: Detector
    private var videoUri: Uri? = null
    private var isProcessing = false
    private lateinit var handler: Handler
    private var frameExtractor: Runnable? = null

    // TTS components
    private lateinit var tts: TextToSpeech
    private val ttsQueue = LinkedList<String>()
    private var isTtsSpeaking = false
    private val COOLDOWN_MS = 3000
    private val alertHistory = mutableMapOf<String, Long>()
    private val HISTORY_EXPIRATION_MS = 8000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoSimulationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handler = Handler(Looper.getMainLooper())
        setupDetector()
        setupFilePicker()
        setupControls()
        initTTS()
    }

    private fun initTTS() {
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
            }
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



    private fun cleanupExpiredHistory() {
        val currentTime = System.currentTimeMillis()
        alertHistory.entries.removeAll { currentTime - it.value > HISTORY_EXPIRATION_MS }
    }

    private fun processNextAlert() {
        if (!isTtsSpeaking && ttsQueue.isNotEmpty()) {
            val message = ttsQueue.poll()
            isTtsSpeaking = true
            tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, message)
        }
    }


    private fun setupDetector() {
        detector = Detector(
            baseContext,
            MODEL_PATH,
            LABELS_PATH,
            this
        )

    }

    private fun setupFilePicker() {
        binding.btnSelectVideo.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "video/*"
            }
            startActivityForResult(intent, REQUEST_PICK_VIDEO)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_VIDEO && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                videoUri = uri
                setupVideoPlayer(uri)
            }
        }
    }

    private fun setupVideoPlayer(uri: Uri) {
        binding.videoView.setVideoURI(uri)
        binding.videoView.setOnPreparedListener { mediaPlayer ->
            binding.progressBar.visibility = View.GONE
            mediaPlayer.isLooping = true
            binding.videoView.start()
        }
    }

    private fun setupControls() {
        binding.btnToggleProcessing.setOnClickListener {
            isProcessing = !isProcessing
            if (isProcessing) {
                startProcessing()
                binding.btnToggleProcessing.text = "Stop Processing"
            } else {
                stopProcessing()
                binding.btnToggleProcessing.text = "Start Processing"
            }
        }
    }

    private fun startProcessing() {
        videoUri?.let { uri ->
            try {
                val mediaRetriever = MediaMetadataRetriever().apply {
                    setDataSource(this@VideoSimulationActivity, uri)
                }

                frameExtractor = object : Runnable {
                    override fun run() {
                        if (!isProcessing) return

                        try {
                            val currentPos = binding.videoView.currentPosition
                            mediaRetriever.getFrameAtTime(
                                currentPos * 1000L,
                                MediaMetadataRetriever.OPTION_CLOSEST
                            )?.let { rawFrame ->
                                // Convert to ARGB_8888 if needed
                                val convertedBitmap = if (rawFrame.config != Bitmap.Config.ARGB_8888) {
                                    rawFrame.copy(Bitmap.Config.ARGB_8888, false)
                                } else {
                                    rawFrame
                                }

                                // Scale to model input size
                                val scaledBitmap = Bitmap.createScaledBitmap(
                                    convertedBitmap,
                                    detector.inputWidth,
                                    detector.inputHeight,
                                    true
                                )

                                // Recycle temporary bitmaps
                                if (convertedBitmap != rawFrame) {
                                    rawFrame.recycle()
                                }

                                detector.detect(scaledBitmap)
                                scaledBitmap.recycle()
                            }
                        } catch (e: Exception) {
                            Log.e("VideoProcessing", "Frame processing failed", e)
                        }

                        handler.postDelayed(this, (1000 / 30).toLong()) // 30 FPS
                    }
                }
                handler.post(frameExtractor!!)
            } catch (e: Exception) {
                Log.e("VideoProcessing", "Media setup failed", e)
                stopProcessing()
            }
        }
    }

    private fun stopProcessing() {
        isProcessing = false
        frameExtractor?.let { handler.removeCallbacks(it) }
        binding.overlay.clear()
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
    }

    // Update the onDetect function
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            cleanupExpiredHistory()

            // Centralized class name handling
            val processedBoxes = boundingBoxes.map { box ->
                box.copy(clsName = DetectionUtils.getDisplayClassName(box.clsName))
            }

            val prioritized = DetectionUtils.filterAndPrioritize(processedBoxes)
                .take(1)
                .filter { box ->
                    DetectionUtils.shouldAlert(alertHistory, box).also {
                        if (it) alertHistory[DetectionUtils.generateHistoryKey(box)] = System.currentTimeMillis()
                    }
                }

            binding.overlay.setResults(prioritized)
            binding.overlay.invalidate()
            binding.inferenceTime.text = "${inferenceTime}ms"

            prioritized.map { box ->
                DetectionUtils.generateAlertMessage(box)
            }.forEach { message ->
                if (!ttsQueue.contains(message)) {
                    ttsQueue.add(message)
                }
            }

            processNextAlert()
        }
    }

    // Simplify the generateAlertMessage function
    private fun generateAlertMessage(box: BoundingBox): String {
        val position = DetectionUtils.getPositionDescription(box)
        return "${box.clsName.replace("_", " ")} $position"
    }

// Remove the generateAlertKey function and use DetectionUtils version


    override fun onDestroy() {
        super.onDestroy()
        stopProcessing()
        detector.close()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    companion object {
        private const val REQUEST_PICK_VIDEO = 1001
    }
}