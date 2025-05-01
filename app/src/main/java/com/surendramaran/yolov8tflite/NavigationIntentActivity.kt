package com.surendramaran.yolov8tflite // Adjust to your package name

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.KeyEvent // Needed for Volume Button
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.io.Serializable
import java.util.Locale
// Assuming OsmAndHelper is imported correctly
// import com.surendramaran.yolov8tflite.OsmAndHelper


class NavigationIntentActivity : AppCompatActivity(),
    OsmAndHelper.OnOsmandMissingListener,
    TextToSpeech.OnInitListener {

    // --- Data Class for Destination ---
    data class FixedDestination(val name: String, val address: String, val lat: Double, val lon: Double):
        Serializable

    companion object {
        private const val TAG = "VoiceNavIntent"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1002
        private const val AUDIO_PERMISSION_REQUEST_CODE = 1004
        private const val ALL_PERMISSIONS_REQUEST_CODE = 1005
        private const val REQUEST_OSMAND_API = 1003
        private const val SCAN_DURATION_MS = 5000L
        private const val DOUBLE_PRESS_DELAY_MS = 500

        // Utterance IDs
        private const val UTT_INITIAL_PROMPT = "UTT_INITIAL_PROMPT"
        private const val UTT_CONFIRM_DEST = "UTT_CONFIRM_DEST"
        private const val UTT_NAV_STARTING = "UTT_NAV_STARTING"
        private const val UTT_ASK_AGAIN = "UTT_ASK_AGAIN"
        private const val UTT_NO_MATCH = "UTT_NO_MATCH"
        private const val UTT_SPEECH_ERROR = "UTT_SPEECH_ERROR"
        private const val UTT_SCAN_STARTING = "UTT_SCAN_STARTING"
        private const val UTT_SCAN_TRIGGER_ERROR = "UTT_SCAN_TRIGGER_ERROR"
        private const val UTT_STOPPING_NAV = "UTT_STOPPING_NAV"
        private const val UTT_GENERAL_ERROR = "UTT_GENERAL_ERROR"
        private const val UTT_PERMISSIONS_NEEDED = "UTT_PERMISSIONS_NEEDED"
        private const val UTT_BACK_BUTTON_PROMPT = "UTT_BACK_BUTTON_PROMPT"
        // Keys for saving instance state
        private const val STATE_VOICE_STATE = "STATE_VOICE_STATE"
        private const val STATE_SELECTED_DESTINATION = "STATE_SELECTED_DESTINATION"
        private const val STATE_TEMP_MATCH_INDEX = "STATE_TEMP_MATCH_INDEX"
        private const val STATE_PERMISSIONS_GRANTED = "STATE_PERMISSIONS_GRANTED"
        private const val UTT_STOP_MSG_COMPLETE = "UTT_STOP_MSG_COMPLETE"
//        private const val UTT_RETRY_PROMPT = "UTT_RETRY_PROMPT"
        private const val UTT_RETRY_INSTRUCTION = "UTT_RETRY_INSTRUCTION"
    }

    // --- UI Elements ---
    private lateinit var statusText: TextView
    private lateinit var sourceCoordinatesText: TextView
    private lateinit var destinationSpinner: Spinner // Keep for internal selection logic
    private lateinit var selectedDestinationText: TextView
    private lateinit var startNavigationButton: Button
    private lateinit var stopNavigationButton: Button
    // Removed Pause/Resume Buttons

    // --- Location ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var sourceLocation: Location? = null

    // --- OsmAnd Helper ---
    private lateinit var osmandHelper: OsmAndHelper
    private var osmandPackage: String? = null

    // --- Destinations ---
    private val fixedDestinations = listOf(
        FixedDestination("Mian Abdullah Library", "Near Adamjee Academic Block", 24.94086575, 67.1150593333021),
        FixedDestination("IBA Soccer Field", "Near NBP Building", 24.94172, 67.11383),
        FixedDestination("Mubarak Masjid","Q2WV+66J, DHA Phase 5", 24.79540, 67.04332),
        FixedDestination("Falah Masjid","V3C2+GMR, Al-Falah Rd, Block 2 P.E.C.H.S.", 24.87137, 67.05169)

    )

    private var selectedDestination: FixedDestination? = null
    private var temporaryMatchedIndex: Int = -1

    // --- Voice & TTS ---
    private lateinit var tts: TextToSpeech
    private var ttsInitialized = false
    private lateinit var speechRecognizerIntent: Intent
    private var isListening = false

    // --- State Management ---
    private enum class VoiceState : Serializable { // Make enum Serializable
        IDLE,
        AWAITING_PERMISSIONS,
        PROMPTING_DESTINATION,
        LISTENING_FOR_DESTINATION,
        AWAITING_CONFIRMATION,
        NAVIGATION_ACTIVE,
        SCANNING_ACTIVE
    }

    private var currentVoiceState = VoiceState.AWAITING_PERMISSIONS
    private var requiredPermissionsGranted = false

    private var lastVolumeDownPressTime: Long = 0

    // --- Activity Result Launchers ---
    private val speechResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        handleSpeechResult(result)
    }

    // --- Lifecycle Methods ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation_intent)
        Log.i(TAG, "onCreate started. SavedInstanceState is null: ${savedInstanceState == null}")
// *** Restore state BEFORE initializing everything ***
        if (savedInstanceState != null) {
            Log.i(TAG, "Restoring instance state...")
            // Restore state (use safe defaults if key missing)
            // Use getSerializable for Enum
            currentVoiceState = savedInstanceState.getSerializable(STATE_VOICE_STATE) as? VoiceState ?: VoiceState.AWAITING_PERMISSIONS
            // Restore permission flag
            requiredPermissionsGranted = savedInstanceState.getBoolean(STATE_PERMISSIONS_GRANTED, false)
            // Restore destination only if navigating or scanning
            if (currentVoiceState == VoiceState.NAVIGATION_ACTIVE || currentVoiceState == VoiceState.SCANNING_ACTIVE) {
                // Use getSerializable for the data class
                selectedDestination = savedInstanceState.getSerializable(STATE_SELECTED_DESTINATION) as? FixedDestination
                Log.i(TAG, "Restored selectedDestination: ${selectedDestination?.name}")
            }
            // Restore temp index if awaiting confirmation
            if (currentVoiceState == VoiceState.AWAITING_CONFIRMATION) {
                temporaryMatchedIndex = savedInstanceState.getInt(STATE_TEMP_MATCH_INDEX, -1)
            }
            Log.i(TAG, "Restored State: $currentVoiceState, Perms: $requiredPermissionsGranted, TempIdx: $temporaryMatchedIndex")
        } else {
            // No saved state, initialize as usual (AWAITING_PERMISSIONS)
            currentVoiceState = VoiceState.AWAITING_PERMISSIONS
            requiredPermissionsGranted = false // Will be checked
            Log.i(TAG, "No saved state, initializing fresh.")
        }

        bindViews()
        initializeDependencies()
        setupUI()
        updateStatusText()
        if (!requiredPermissionsGranted) {
            checkAndRequestPermissions()
        } else {
            // Permissions already granted, proceed with location and potential prompt
            Log.i(TAG,"Permissions were already granted (restored or checked).")
            getCurrentLocation()
            // Check if we should prompt initially (only if restored/initialized state is IDLE and TTS ready)
            checkAndPromptInitial()
        }
        updateButtonStates()
        Log.i(TAG, "onCreate finished")
    }

    // *** Add onSaveInstanceState ***
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Log.i(TAG, "onSaveInstanceState: Saving state = $currentVoiceState")
        // Save the critical state variables
        outState.putSerializable(STATE_VOICE_STATE, currentVoiceState)
        outState.putBoolean(STATE_PERMISSIONS_GRANTED, requiredPermissionsGranted)
        // Save destination only if relevant
        if (currentVoiceState == VoiceState.NAVIGATION_ACTIVE || currentVoiceState == VoiceState.SCANNING_ACTIVE) {
            selectedDestination?.let { outState.putSerializable(STATE_SELECTED_DESTINATION, it) }
        }
        // Save temp index only if relevant
        if (currentVoiceState == VoiceState.AWAITING_CONFIRMATION) {
            outState.putInt(STATE_TEMP_MATCH_INDEX, temporaryMatchedIndex)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: Entering with State = $currentVoiceState")

        // *** SIMPLIFIED onResume ***
        // State restoration now primarily happens in onCreate.
        // onResume mainly handles returning from SCANNING_ACTIVE
        // and refreshing dynamic info like location.

        if (currentVoiceState == VoiceState.SCANNING_ACTIVE) {
            Log.i(TAG, "Returned from scan, resetting state to NAVIGATION_ACTIVE.")
            currentVoiceState = VoiceState.NAVIGATION_ACTIVE
            // Update UI immediately
            updateStatusText()
            updateButtonStates()
            // Optional: "Scan finished" prompt
        }

        // Always refresh location if permissions allow
        if (requiredPermissionsGranted) {
            getCurrentLocation()
        }

        // Always update UI state on resume
        updateStatusText()
        updateButtonStates()

        Log.i(TAG, "onResume completed. State is now = $currentVoiceState")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy")
        shutdownTTS()
        // No explicit speech recognizer object to destroy with Intent method
    }

    // --- Initialization ---
    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        sourceCoordinatesText = findViewById(R.id.sourceCoordinatesText)
        destinationSpinner = findViewById(R.id.destinationSpinner)
        selectedDestinationText = findViewById(R.id.selectedDestinationText)
        startNavigationButton = findViewById(R.id.startNavigationButton)
        stopNavigationButton = findViewById(R.id.stopNavigationButton)
        // Removed Pause/Resume findViewById
    }

    private fun initializeDependencies() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        osmandHelper = OsmAndHelper(this, REQUEST_OSMAND_API, this)
        osmandPackage = "net.osmand"; // Use helper to get name
        initializeTTS()
        setupSpeechRecognizerIntent()
//        setupUI()
    }

    private fun initializeTTS() {
        tts = TextToSpeech(this, this) // 'this' is the OnInitListener
    }

    // --- TextToSpeech OnInitListener ---
    override fun onInit(status: Int) {
        // ... (Keep existing onInit logic - triggers initial prompt if permissions ready) ...
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language not supported.")
                ttsInitialized = false
                Toast.makeText(this, "TTS Language Not Supported", Toast.LENGTH_SHORT).show()
            } else {
                ttsInitialized = true
                Log.i(TAG, "TTS Initialized.")
                tts.setOnUtteranceProgressListener(ttsUtteranceListener)
                // Check if permissions are already granted AFTER TTS is ready
                checkAndPromptInitial()
//                if (currentVoiceState == VoiceState.IDLE && requiredPermissionsGranted) {
//                    promptInitialDestination()
//                } else if (currentVoiceState == VoiceState.AWAITING_PERMISSIONS && !requiredPermissionsGranted) {
//                    speak(UTT_PERMISSIONS_NEEDED, "This app requires location and microphone permissions.")
//                }
            }
        } else { Log.e(TAG, "TTS Init failed."); ttsInitialized = false }
        updateStatusText() // Update status based on TTS init
    }

    private fun shutdownTTS() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
            Log.i(TAG, "TTS shut down.")
        }
    }


    // --- TTS Utterance Listener ---
    private val ttsUtteranceListener = object : UtteranceProgressListener() {
        // ... (Keep existing listener logic to trigger startListening after prompts) ...
        override fun onStart(utteranceId: String?) {}
        override fun onError(utteranceId: String?, errorCode: Int) { Log.e(TAG, "TTS Error ($errorCode) for $utteranceId"); runOnUiThread { handleTtsError(utteranceId) } }
        @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { onError(utteranceId, -1) }

        override fun onDone(utteranceId: String?) {
            Log.d(TAG, "TTS Done: $utteranceId, Current State: $currentVoiceState")
            runOnUiThread {
                when (utteranceId) {
                    UTT_INITIAL_PROMPT, UTT_ASK_AGAIN -> {
                        if (currentVoiceState == VoiceState.PROMPTING_DESTINATION) {
                            startListening(VoiceState.LISTENING_FOR_DESTINATION)
                        }
                    }
                    UTT_CONFIRM_DEST -> {
                        if (currentVoiceState == VoiceState.AWAITING_CONFIRMATION) {
                            startListening(VoiceState.AWAITING_CONFIRMATION)
                        }
                    }
                    UTT_STOPPING_NAV -> { if (currentVoiceState == VoiceState.IDLE) { speak(UTT_BACK_BUTTON_PROMPT, "Please press the back button.") } } // Speak next part
                    UTT_BACK_BUTTON_PROMPT -> { if (currentVoiceState == VoiceState.IDLE) { speak(UTT_STOP_MSG_COMPLETE, "Then, double press Volume Down for a new trip.") } } // Speak final part
                    UTT_BACK_BUTTON_PROMPT -> { // After "Please press back..."
                        // Check state, should still be IDLE
                        if (currentVoiceState == VoiceState.IDLE) {
                            speak(
                                UTT_STOP_MSG_COMPLETE,
                                "Then, double press Volume Down to start a new trip."
                            )
                        }
                    }
                    UTT_STOP_MSG_COMPLETE -> { // After the final part of stop sequence
                        Log.d(TAG, "Finished speaking stop message sequence.")
                        // No automatic action needed. User needs to press back & double-press Vol Down.
                    }
                    // --- End Chain Stop Messages ---

                    // After speaking retry instruction, wait for user trigger
//
                    UTT_RETRY_INSTRUCTION -> { Log.d(TAG, "Retry instruction finished.") }
                }
            }
        }
    }

    private fun setupUI() { // Renamed for clarity, called from onCreate
        setupDestinationSpinner()
        setupNavigationButtons()
    }

    private fun handleTtsError(utteranceId: String?) {
        Log.e(TAG, "Handling TTS error for utterance: $utteranceId")
        when (utteranceId) {
            UTT_INITIAL_PROMPT, UTT_ASK_AGAIN, UTT_CONFIRM_DEST -> {
                if (currentVoiceState != VoiceState.NAVIGATION_ACTIVE && currentVoiceState != VoiceState.SCANNING_ACTIVE) { currentVoiceState = VoiceState.IDLE }
                updateStatusText(); Toast.makeText(this, "Audio prompt error.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Speech Recognition ---
    private fun setupSpeechRecognizerIntent() {
        // ... (Keep existing intent setup) ...
        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
    }

    private fun handleSpeechResult(result: androidx.activity.result.ActivityResult) {
        Log.d(TAG, "Handling Speech Result Code: ${result.resultCode}, Current State: $currentVoiceState")
        isListening = false // Mark listening finished

        if (result.resultCode == RESULT_OK && result.data != null) {
            val speechResult: ArrayList<String>? = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!speechResult.isNullOrEmpty()) {
                val recognizedText = speechResult[0].lowercase(Locale.getDefault()).trim()
                Log.i(TAG, "Recognized: '$recognizedText'")
                processVoiceInput(recognizedText)
            } else {
                Log.w(TAG, "Speech returned empty results.")
                // Use the specific error handler for speech failures
                handleSpeechRecognitionError("No speech detected")
            }
        } else {
            Log.w(TAG, "Speech recognition failed, cancelled, or no result (Result Code: ${result.resultCode}).")
            // Use the specific error handler for speech failures
            handleSpeechRecognitionError("Listening failed or was cancelled")
        }
    }


    private fun startListening(newState: VoiceState) {
        // ... (Keep existing startListening logic, including permission/availability checks and state setting) ...
        if (isListening) { Log.w(TAG, "Already listening..."); return }
        if (!requiredPermissionsGranted) { Log.e(TAG, "Cannot listen, permissions missing."); speak(UTT_PERMISSIONS_NEEDED, "Microphone permission needed."); checkAndRequestPermissions(); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { Log.e(TAG, "Speech recognition not available."); Toast.makeText(this, "Speech recognition unavailable.", Toast.LENGTH_SHORT).show(); return }

        Log.i(TAG, "Starting to listen... Setting state to $newState")
        currentVoiceState = newState
        isListening = true
        updateStatusText()

        try {
            speechResultLauncher.launch(speechRecognizerIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching speech recognizer", e)
            handleSpeechError("Error starting voice input.") // Central error handler resets state
        }
    }

    // *** MODIFIED Speech Error Handling ***
    private fun handleSpeechError(errorMessage: String) {
        Log.e(TAG, "handleSpeechError: $errorMessage. Current State was: $currentVoiceState")

        // Construct the user-facing message with retry instructions
        val fullErrorMessage = "Sorry, $errorMessage. Please double press the Volume Down button to try again."

        // Speak the error and the instruction
        Log.e(TAG, "Error launching speech recognizer, UTT_RETRY_INSTRUCTION")
        speak(UTT_RETRY_INSTRUCTION, fullErrorMessage) // Using UTT_RETRY_PROMPT is fine

        // Reset state to IDLE ONLY if the error occurred during a pre-navigation phase
        // This allows the Volume Down double-press trigger to work.
        // Do NOT reset if navigation was active (though listening isn't expected then anyway).
        if (currentVoiceState == VoiceState.LISTENING_FOR_DESTINATION ||
            currentVoiceState == VoiceState.AWAITING_CONFIRMATION ||
            currentVoiceState == VoiceState.PROMPTING_DESTINATION) // Or if TTS error happened during prompt
        {
            Log.i(TAG, "Resetting state to IDLE due to speech error in pre-navigation phase.")
            currentVoiceState = VoiceState.IDLE
        } else {
            Log.w(TAG, "Speech error occurred in state $currentVoiceState, not resetting to IDLE.")
            // If error occurred during NAV state (unlikely as listening isn't auto),
            // we might want different handling, but for now, just don't reset to IDLE.
        }

        isListening = false // Ensure listening flag is always reset on error
        updateStatusText() // Update UI to reflect IDLE state and prompt
        updateButtonStates() // Update buttons based on new state
    }

    private fun handleSpeechRecognitionError(errorContext: String) {
        Log.e(TAG, "handleSpeechRecognitionError: $errorContext. State was: $currentVoiceState")

        // *** Speak the error and explicit retry instruction ***
        speak(UTT_RETRY_INSTRUCTION, "Sorry, $errorContext. Please double press the Volume Down button to try specifying a destination again.")

        // Ensure state is reset to IDLE so the double-press trigger works
        if (currentVoiceState != VoiceState.NAVIGATION_ACTIVE && currentVoiceState != VoiceState.SCANNING_ACTIVE) {
            currentVoiceState = VoiceState.IDLE
        }
        isListening = false // Ensure listening flag is reset
        updateStatusText()
        updateButtonStates()
    }

    // --- Voice Input Processing Logic ---
    private fun processVoiceInput(text: String) {
        Log.i(TAG, "Processing voice input: '$text' while in state: $currentVoiceState")
        if (text.isBlank()) { handleSpeechError("No speech detected."); return }



        // --- State-Specific Processing ---
        when (currentVoiceState) {
            // Listening for destination OR just returned to IDLE after stop/error
            VoiceState.LISTENING_FOR_DESTINATION -> { // Note: IDLE state doesn't listen automatically now
                val matchedIndex = findBestMatch(text)
                if (matchedIndex != -1) { temporaryMatchedIndex = matchedIndex; promptForConfirmation() }
                else { Log.w(TAG, "No dest match '$text'"); speak(UTT_NO_MATCH, "No match. Double press Volume Down to try again."); currentVoiceState = VoiceState.IDLE; updateStatusText() } // Guide user
            }
            VoiceState.AWAITING_CONFIRMATION -> {
                // Process yes/no for confirmation
                if (text.contains("yes") || text.contains("yeah") || text.contains("start")) {
                    Log.i(TAG, "User confirmed destination.")
                    if (temporaryMatchedIndex != -1) {
                        destinationSpinner.setSelection(temporaryMatchedIndex)
                        selectedDestination = fixedDestinations[temporaryMatchedIndex]
                        updateStatusText()
                        updateButtonStates()
                        speak(UTT_NAV_STARTING, "Okay, starting navigation to ${selectedDestination?.name}.")
                        startNavigationViaIntent() // Sets state to NAVIGATION_ACTIVE
                    } else { // Error case
                        handleSpeechError("Something went wrong with selection.")
                        promptInitialDestination()
                    }
                } else if (text.contains("no") || text.contains("cancel")) {
                    Log.i(TAG, "User rejected destination.")
                    temporaryMatchedIndex = -1
                    speak(UTT_ASK_AGAIN, "Okay, which destination would you like to go to instead?")
                    currentVoiceState = VoiceState.PROMPTING_DESTINATION // Wait for TTS, then listen again
                    updateStatusText()
                } else { // Unclear response
                    promptForConfirmation() // Ask confirmation again
                }
            }
            VoiceState.NAVIGATION_ACTIVE -> {
                // Check for "Scan Ahead" command (Stop handled globally)
                if (text.contains("scan ahead") || text.contains("what's ahead")) {
                    triggerObstacleScan()
                } else {
                    Log.d(TAG, "Ignoring non-command speech during active navigation: $text")
                    // Don't automatically re-listen here unless prompted by user
                    // User needs to use volume button or trigger phrase (if implemented)
                }
            }
            VoiceState.SCANNING_ACTIVE -> {
                Log.d(TAG, "Ignoring speech during scan: $text")
                // Scan runs for fixed duration or until stopped manually (not implemented here)
            }
            else -> {
                Log.w(TAG, "processVoiceInput called in unexpected state: $currentVoiceState for text: $text")
            }
        }
    }

    // --- Destination Matching ---
    // ... (Keep existing findBestMatch and levenshteinDistance) ...
    // --- Destination Matching (Simple Example) ---
    private fun findBestMatch(spokenText: String): Int {
        if (spokenText.isBlank()) return -1
        Log.d(TAG, "Finding best match for: '$spokenText'")

        var bestMatchIndex = -1
        // Using Levenshtein distance for potentially better matching
        var minDistance = Int.MAX_VALUE
        val threshold = spokenText.length / 2 + 1 // Allow some errors based on length

        fixedDestinations.forEachIndexed { index, destination ->
            val nameLower = destination.name.lowercase(Locale.getDefault())
            val distance = levenshteinDistance(spokenText, nameLower)

            Log.v(TAG, "Comparing '$spokenText' with '$nameLower', distance: $distance") // Verbose log

            if (distance < minDistance && distance <= threshold) { // Check distance against threshold
                minDistance = distance
                bestMatchIndex = index
            }
        }

        if (bestMatchIndex != -1) {
            Log.i(TAG, "Best match found: '${fixedDestinations[bestMatchIndex].name}' (Index: $bestMatchIndex, Distance: $minDistance)")
        } else {
            Log.w(TAG, "No suitable match found based on distance and threshold.")
        }
        return bestMatchIndex
    }

    // Basic Levenshtein Distance implementation
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val costs = IntArray(s2.length + 1)
        for (i in 0..s1.length) {
            var lastValue = i
            for (j in 0..s2.length) {
                if (i == 0) {
                    costs[j] = j
                } else {
                    if (j > 0) {
                        var newValue = costs[j - 1]
                        if (s1[i - 1] != s2[j - 1]) {
                            newValue = Math.min(Math.min(newValue, lastValue), costs[j]) + 1
                        }
                        costs[j - 1] = lastValue
                        lastValue = newValue
                    }
                }
            }
            if (i > 0) costs[s2.length] = lastValue
        }
        return costs[s2.length]
    }



    // --- Voice Prompts ---
    private fun checkAndPromptInitial() {
        // Only prompt if ALL conditions are met
        if (currentVoiceState == VoiceState.IDLE && requiredPermissionsGranted && ttsInitialized && !isListening) {
            Log.i(TAG, "Conditions met, prompting for initial destination.")
            speak(UTT_INITIAL_PROMPT, "Where would you like to go?")
            currentVoiceState = VoiceState.PROMPTING_DESTINATION
            updateStatusText()
        } else {
            Log.w(TAG, "Skipped initial prompt check. State: $currentVoiceState, Perms: $requiredPermissionsGranted, TTS: $ttsInitialized, Listening: $isListening")
        }
    }
    // Keep promptInitialDestination for direct calls when needed (e.g., after stop)
    private fun promptInitialDestination() {
        Log.i(TAG, "Direct call to promptInitialDestination.")
        // Use the check function internally as well for safety
        checkAndPromptInitial()
    }


    private fun promptForConfirmation() {
        // ... (Keep existing promptForConfirmation logic) ...
        if (temporaryMatchedIndex != -1 && temporaryMatchedIndex < fixedDestinations.size) {
            val destName = fixedDestinations[temporaryMatchedIndex].name
            Log.i(TAG, "Prompting for confirmation for: $destName")
            speak(UTT_CONFIRM_DEST, "Did you mean $destName? Please say yes or no.")
            currentVoiceState = VoiceState.AWAITING_CONFIRMATION
            updateStatusText()
        } else {
            Log.e(TAG, "Tried to prompt confirmation with invalid index: $temporaryMatchedIndex")
            promptInitialDestination()
        }
    }

    // --- TTS Speak Helper ---
    private fun speak(utteranceId: String, text: String) {
        if (ttsInitialized) {
            Log.i(TAG,"TTS Speak ($utteranceId): $text")
            // Use QUEUE_FLUSH for most prompts to ensure they are spoken immediately.
            // Use QUEUE_ADD only where needed (like the back button prompt after nav start).
            val queueMode = if (utteranceId == UTT_BACK_BUTTON_PROMPT || utteranceId == UTT_STOP_MSG_COMPLETE) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
            tts.speak(text, queueMode, null, utteranceId)
        } else {
            Log.e(TAG, "TTS not initialized, cannot speak: $text")
            // Consider queuing messages if TTS isn't ready yet? More complex. For now, just log.
        }
    }

    // --- Obstacle Scan Trigger ---
    // Modified to be called by Volume Button press or voice command
    private fun triggerObstacleScan() {
        Log.i(TAG, "Attempting to trigger obstacle scan...")
        if (currentVoiceState != VoiceState.NAVIGATION_ACTIVE) {
            Log.w(TAG, "Scan requested but navigation not active.")
            speak(UTT_SCAN_TRIGGER_ERROR, "Navigation needs to be active to scan.")
            return
        }
        // Prevent triggering if already scanning
        if (isListening) {
            Log.w(TAG, "Scan trigger ignored, currently listening.")
            return
        }

        currentVoiceState = VoiceState.SCANNING_ACTIVE
        updateStatusText()
        updateButtonStates() // Visually disable nav buttons during scan

        try {
            val intent = Intent(this, LiveCameraActivity::class.java)
            intent.putExtra("SCAN_DURATION", SCAN_DURATION_MS)
            startActivity(intent)
            // State will be reset in onResume when user returns or scan finishes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch LiveCameraActivity", e)
            speak(UTT_SCAN_TRIGGER_ERROR, "Could not start obstacle scanner.")
            currentVoiceState = VoiceState.NAVIGATION_ACTIVE // Revert state on error
            updateStatusText()
            updateButtonStates()
        }
    }

    // --- Permission Handling ---
    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (!checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!checkPermission(Manifest.permission.RECORD_AUDIO)) permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)

        if (permissionsNeeded.isNotEmpty()) {
            requiredPermissionsGranted = false // Mark as not granted until result
            // Set state ONLY if not already AWAITING
            if (currentVoiceState != VoiceState.AWAITING_PERMISSIONS) {
                Log.i(TAG,"Permissions missing, setting state to AWAITING_PERMISSIONS")
                currentVoiceState = VoiceState.AWAITING_PERMISSIONS
                updateStatusText()
                updateButtonStates()
            }
            Log.i(TAG, "Requesting permissions: ${permissionsNeeded.joinToString()}")
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), ALL_PERMISSIONS_REQUEST_CODE)
        } else {
            // Already granted
            if (!requiredPermissionsGranted) { // Update flag if it was false
                Log.i(TAG, "All required permissions already granted (on check).")
                requiredPermissionsGranted = true
            }
            // If state was AWAITING, move to IDLE now
            if (currentVoiceState == VoiceState.AWAITING_PERMISSIONS) {
                currentVoiceState = VoiceState.IDLE
                Log.i(TAG,"Permissions check complete, moving state to IDLE")
            }
            updateStatusText()
            updateButtonStates()
            getCurrentLocation() // Safe to get location now
            checkAndPromptInitial() // Check if we can prompt now that permissions are confirmed
        }
    }


    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ALL_PERMISSIONS_REQUEST_CODE) {
            // Recheck permissions after dialog closes
            val locationGranted = checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            val audioGranted = checkPermission(Manifest.permission.RECORD_AUDIO)
            requiredPermissionsGranted = locationGranted && audioGranted // Update global flag
            Log.i(TAG, "Permission results processed: Location=$locationGranted, Audio=$audioGranted, RequiredGranted=$requiredPermissionsGranted")

            if (locationGranted) { getCurrentLocation() } else { handleLocationPermissionDenied() }
            if (!audioGranted) { handleAudioPermissionDenied() }

            if (requiredPermissionsGranted) {
                // If we were waiting for permissions, move to IDLE
                if (currentVoiceState == VoiceState.AWAITING_PERMISSIONS) {
                    currentVoiceState = VoiceState.IDLE
                    Log.i(TAG,"Permissions granted via dialog, moving state to IDLE")
                    checkAndPromptInitial() // Check if we can prompt now
                }
            } else {
                // Stay in AWAITING_PERMISSIONS state if still missing
                currentVoiceState = VoiceState.AWAITING_PERMISSIONS
                speak(UTT_PERMISSIONS_NEEDED, "Some permissions are still required.")
            }
            updateStatusText()
            updateButtonStates()
        }
    }

    private fun handleLocationPermissionDenied() {
        Log.w(TAG, "Location permission DENIED.")
        sourceLocation = null
        sourceCoordinatesText.text = "Source: Permission Denied"
        Toast.makeText(this, "Location permission is required for navigation.", Toast.LENGTH_LONG).show()
        // Buttons updated in main result handler
    }
    private fun handleAudioPermissionDenied() {
        Log.w(TAG, "Audio permission DENIED.")
        Toast.makeText(this, "Microphone permission is required for voice commands.", Toast.LENGTH_LONG).show()
        // Buttons updated in main result handler
    }




    // --- Location Fetching ---
    // ... (Keep existing getCurrentLocation, checkPermission) ...
    private fun getCurrentLocation() {
        if (!checkPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Log.w(TAG, "Attempting getCurrentLocation without permission.")
            // checkAndRequestPermissions() // Avoid loop, permission should be granted before calling
            return
        }
        Log.d(TAG, "Attempting to get current location...")
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        sourceLocation = location
                        sourceCoordinatesText.text = "Source: Current (${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)})"
                        Log.i(TAG, "Current location obtained: Lat: ${location.latitude}, Lon: ${location.longitude}")
                        updateStatusText() // Update status as location affects readiness
                        updateButtonStates()
                    } else {
                        Log.w(TAG, "FusedLocationProvider returned null location. Maybe GPS off?")
                        sourceCoordinatesText.text = "Source: Location N/A (Enable GPS?)"
                        Toast.makeText(this, "Could not get current location.", Toast.LENGTH_SHORT).show()
                        updateButtonStates()
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get current location", e)
                    sourceLocation = null
                    sourceCoordinatesText.text = "Source: Location Error"
                    Toast.makeText(this, "Error getting location.", Toast.LENGTH_SHORT).show()
                    updateButtonStates()
                }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException in getCurrentLocation.", se)
            Toast.makeText(this, "Location permission error.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }


    // --- UI Setup ---
    private fun setupDestinationSpinner() {
        // ... (Keep existing setup - spinner disabled) ...
        destinationSpinner.isEnabled = false
        destinationSpinner.alpha = 0.5f
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fixedDestinations.map { it.name })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        destinationSpinner.adapter = adapter
        selectedDestinationText.text = "Dest: Speak destination"
    }

    private fun setupNavigationButtons() {
        // ... (Keep existing button setup, remove listeners for Pause/Resume) ...
        startNavigationButton.setOnClickListener {
            if (currentVoiceState == VoiceState.IDLE && sourceLocation != null && selectedDestination != null) {
                speak(UTT_NAV_STARTING, "Starting navigation to ${selectedDestination?.name}.")
                startNavigationViaIntent()
            } else { Toast.makeText(this, "Use voice or ensure location/destination.", Toast.LENGTH_SHORT).show() }
        }
        stopNavigationButton.setOnClickListener { stopNavigationAction() }
        // pauseNavigationButton.setOnClickListener { /* Removed */ }
        // resumeNavigationButton.setOnClickListener { /* Removed */ }

//        // Hide Pause/Resume buttons
//        pauseNavigationButton.visibility = View.GONE
//        resumeNavigationButton.visibility = View.GONE
    }

    private fun updateButtonStates() {
        // ... (Update logic - remove Pause/Resume enabling) ...
        val isOsmAndAvailable = osmandPackage != null
        val locationReady = sourceLocation != null
        val destinationSelected = selectedDestination != null

        startNavigationButton.isEnabled = isOsmAndAvailable && locationReady && destinationSelected &&
                (currentVoiceState != VoiceState.NAVIGATION_ACTIVE && currentVoiceState != VoiceState.SCANNING_ACTIVE)

        val navControlsEnabled = isOsmAndAvailable && (currentVoiceState == VoiceState.NAVIGATION_ACTIVE || currentVoiceState == VoiceState.SCANNING_ACTIVE)
        stopNavigationButton.isEnabled = navControlsEnabled
        // pauseNavigationButton.isEnabled = false // Removed
        // resumeNavigationButton.isEnabled = false // Removed

        Log.v(TAG, "Update Button States: OsmAnd=$isOsmAndAvailable, Loc=$locationReady, Dest=$destinationSelected, State=$currentVoiceState " +
                "-> Start=${startNavigationButton.isEnabled}, Stop=${stopNavigationButton.isEnabled}")
    }

    private fun updateStatusText() {
        // ... (Keep existing status text logic) ...
        val status = when (currentVoiceState) {
            VoiceState.AWAITING_PERMISSIONS -> "Status: Waiting for permissions..."
            VoiceState.IDLE -> if (!ttsInitialized) "Status: Initializing audio..." else "Status: Ready. Speak destination."
            VoiceState.PROMPTING_DESTINATION -> "Status: Please speak destination..."
            VoiceState.LISTENING_FOR_DESTINATION -> "Status: Listening for destination..."
            VoiceState.AWAITING_CONFIRMATION -> "Status: Listening for confirmation (Yes/No)..."
            VoiceState.NAVIGATION_ACTIVE -> "Status: Navigation active. (Vol Up to Scan)" // Hint for scan
            VoiceState.SCANNING_ACTIVE -> "Status: Scanning for obstacles..."
        }
        if (osmandPackage == null) { statusText.text = "Status: OsmAnd Not Found" }
        else if (!requiredPermissionsGranted && currentVoiceState != VoiceState.AWAITING_PERMISSIONS) { statusText.text = "Status: Permissions Required" }
        else { statusText.text = status }
        Log.d(TAG, "Status Updated: ${statusText.text}")
    }

    // --- Navigation Logic Actions ---
    private fun startNavigationViaIntent() {
        // ... (Keep existing startNavigationViaIntent logic, including back button prompt) ...
        val currentSource = sourceLocation; val currentDestination = selectedDestination
        if (osmandPackage == null || currentSource == null || currentDestination == null) { /* Error handling */ return }
        Log.i(TAG, "Executing navigation Intent for ${currentDestination.name}")
        currentVoiceState = VoiceState.NAVIGATION_ACTIVE; updateButtonStates(); updateStatusText()
        try {
            osmandHelper.navigate("Current Location", currentSource.latitude, currentSource.longitude, currentDestination.name, currentDestination.lat, currentDestination.lon, "pedestrian", true, true)
            Log.i(TAG, "Navigation Intent sent.")
            Handler(Looper.getMainLooper()).postDelayed({
                if (currentVoiceState == VoiceState.NAVIGATION_ACTIVE && ttsInitialized) {
                    Log.i(TAG, "Speaking back button prompt.")
                    tts.speak("You can press the back button now.", TextToSpeech.QUEUE_ADD, null, UTT_BACK_BUTTON_PROMPT)
                }
            }, 5000L)
        } catch (e: Exception) { /* Error handling, reset state */ currentVoiceState = VoiceState.IDLE }
        finally { temporaryMatchedIndex = -1 }
    }


    private fun stopNavigationAction() {
        Log.i(TAG, "Stop Navigation action triggered. Current State: $currentVoiceState")
        if (osmandPackage == null) { Log.e(TAG,"Cannot stop, OsmAnd missing."); return }

        if (currentVoiceState == VoiceState.NAVIGATION_ACTIVE || currentVoiceState == VoiceState.SCANNING_ACTIVE) {
            try {
                osmandHelper.stopNavigation() // Send intent

                // --- Reset State Immediately ---
                val previousState = currentVoiceState
                currentVoiceState = VoiceState.IDLE
                selectedDestination = null
                temporaryMatchedIndex = -1
                runOnUiThread {
                    selectedDestinationText.text = "Dest: Speak destination"
                    updateButtonStates()
                    updateStatusText()
                }
                // -----------------------------

                // *** Speak ONLY the first part; rest chained via listener ***
                speak(UTT_STOPPING_NAV, "Navigation stopped.")

            } catch (e: Exception) {
                Log.e(TAG, "Error sending stop navigation command", e)
                speak(UTT_GENERAL_ERROR, "Error stopping navigation.")
                currentVoiceState = VoiceState.IDLE // Attempt recovery
                runOnUiThread { updateButtonStates(); updateStatusText() }
            }
        } else {
            Log.w(TAG, "Stop ignored: State is $currentVoiceState")
        }
    }


    // --- OsmAnd Missing Listener ---
    override fun osmandMissing() {
        Log.e(TAG, "OsmAndHelper reported OsmAnd is missing!")
        osmandPackage = null
        requiredPermissionsGranted = false // Cannot function without OsmAnd really
        currentVoiceState = VoiceState.IDLE // Reset state
        runOnUiThread {
            Toast.makeText(this, "OsmAnd app not found. Please install OsmAnd.", Toast.LENGTH_LONG).show()
            updateStatusText()
            updateButtonStates() // Disable buttons
        }
    }


    // --- Handle Return to App ---
    private fun handleReturnToApp() {
        // If returning and navigation *should* be active, ensure state matches
        // This helps if the activity was destroyed and recreated.
        if (currentVoiceState != VoiceState.NAVIGATION_ACTIVE && currentVoiceState != VoiceState.SCANNING_ACTIVE) {
            // If not navigating, check if we should re-prompt for destination
            if (currentVoiceState == VoiceState.IDLE && requiredPermissionsGranted && ttsInitialized) {
                // Avoid aggressive re-prompting on every resume, maybe add a flag?
                // For now, we rely on the flow after stop or initial start.
                Log.d(TAG, "onResume in IDLE state, waiting for interaction.")
            }
        } else {
            Log.d(TAG, "onResume while navigation/scanning active.")
            // We don't automatically start listening here. User uses volume button.
        }
        // Refresh location and UI
        if (requiredPermissionsGranted) getCurrentLocation()
        updateStatusText()
        updateButtonStates()
    }



    // --- Volume Button Scan Trigger ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (currentVoiceState == VoiceState.NAVIGATION_ACTIVE) { Log.i(TAG, "Vol Up -> Scan"); triggerObstacleScan(); return true }
                else { Log.d(TAG, "Vol Up ignored: State=$currentVoiceState") }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val currentTime = SystemClock.uptimeMillis()
                Log.d(TAG, "Vol Down pressed. State: $currentVoiceState, Time: $currentTime")

                // Stop Action (Single Press during Nav/Scan)
                if (currentVoiceState == VoiceState.NAVIGATION_ACTIVE || currentVoiceState == VoiceState.SCANNING_ACTIVE) {
                    Log.i(TAG, "Vol Down -> Stop Navigation")
                    stopNavigationAction()
                    lastVolumeDownPressTime = 0 // Reset time to prevent immediate double-press trigger after stopping
                    return true // Consume event
                }
                // New Navigation Trigger (Double Press when IDLE)
                else if (currentVoiceState == VoiceState.IDLE) {
                    if (currentTime - lastVolumeDownPressTime < DOUBLE_PRESS_DELAY_MS) {
                        Log.i(TAG, "Vol Down -> DOUBLE PRESS (IDLE) -> Start New Destination Prompt")
                        promptInitialDestination() // Trigger the voice flow
                        lastVolumeDownPressTime = 0 // Reset time
                        return true // Consume event
                    } else {
                        lastVolumeDownPressTime = currentTime
                        Log.d(TAG,"Vol Down -> First press recorded in IDLE state.")
                        // Don't consume, allow default volume action
                    }
                } else { Log.d(TAG, "Vol Down ignored: State=$currentVoiceState") }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
} // End of Activity