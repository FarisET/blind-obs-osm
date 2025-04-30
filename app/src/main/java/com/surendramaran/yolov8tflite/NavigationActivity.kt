package com.surendramaran.yolov8tflite // Make sure this matches your actual package name

// *** Android / Google Imports ***
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.os.* // Import os.* for IBinder, Handler, Looper, RemoteException, Bundle
import android.speech.tts.TextToSpeech // Import TextToSpeech
import android.util.Log
import android.view.View
import android.view.KeyEvent // Keep this import
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

// *** OsmAnd AIDL Imports ***
// Ensure these imports match the location of your AIDL generated files
import net.osmand.aidlapi.IOsmAndAidlCallback
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.info.AppInfoParams
import net.osmand.aidlapi.navigation.ANavigationVoiceRouterMessageParams
import net.osmand.aidlapi.navigation.NavigateParams
import net.osmand.aidlapi.search.SearchResult
import net.osmand.aidlapi.gpx.AGpxBitmap
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams // <<<< IMPORTANT: Make sure this AIDL file exists and matches OsmAnd
import net.osmand.aidlapi.navigation.ADirectionInfo
import net.osmand.aidlapi.logcat.OnLogcatMessageParams


// *** Other Imports ***
import java.util.Locale // Import Locale for TTS


class NavigationActivity : AppCompatActivity(), TextToSpeech.OnInitListener { // Implement OnInitListener

    companion object {
        private const val TAG = "OsmAndAPI" // Tag for Logcat filtering
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private val OSMAND_PACKAGES = listOf("net.osmand", "net.osmand.plus", "net.osmand.dev")
        private val NAVIGATION_PROFILES = arrayOf("pedestrian", "bicycle", "car") // Added more profiles
    }

    // --- UI Elements ---
    private lateinit var apiStatusText: TextView
    private var versionTextView: TextView? = null // Optional
    private var connectButton: Button? = null // Optional
    private lateinit var destinationSpinner: Spinner
    private lateinit var sourceCoordinatesText: TextView
    private lateinit var destinationCoordinatesText: TextView
    private lateinit var useCurrentLocationButton: Button
    private lateinit var profileSpinner: Spinner
    private lateinit var startNavigationButton: Button

    // --- Service Vars ---
    private var mIOsmAndAidlInterface: IOsmAndAidlInterface? = null
    private var osmandPackage: String? = null
    private var connected = false

    // --- Location Vars ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var sourceLocation: Pair<Double, Double>? = null
    private var destinationLocation: Pair<Double, Double>? = null
    private var selectedProfile: String = NAVIGATION_PROFILES[0]

    // --- TTS Vars ---
    private lateinit var tts: TextToSpeech
    private var ttsInitialized = false

    // --- Callback Vars ---
    @Volatile private var voiceCallbackId: Long = -1L // Use Volatile for visibility across threads
    @Volatile private var voiceCallback: AidlVoiceCallback? = null // Use Volatile

    // --- Fixed Destinations ---
    data class FixedDestination(val name: String, val address: String, val lat: Double, val lon: Double)
    private val fixedDestinations = listOf(
        FixedDestination("Mian Abdullah Library","Near Adamjee Academic Block",24.94086575, 67.1150593333021),
        FixedDestination("IBA Soccer Field","Near NBP Building",24.94172, 67.11383),
        FixedDestination("Mubarak Masjid","Q2WV+66J, DHA Phase 5 Defence V Defence Housing Authority, Karachi, 75500, Pakistan",24.79540,
            67.04332) // Corrected Lat/Lon? Please verify
        // Add more destinations as needed
    )

    //--------------------------------------------------------------------------
    // Lifecycle Methods
    //--------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)
        Log.d(TAG, "onCreate started")

        // --- Initialization ---
        apiStatusText = findViewById(R.id.apiStatusText)
        sourceCoordinatesText = findViewById(R.id.sourceCoordinatesText)
        destinationCoordinatesText = findViewById(R.id.destinationCoordinatesText)
        useCurrentLocationButton = findViewById(R.id.useCurrentLocationButton)
        profileSpinner = findViewById(R.id.profileSpinner)
        startNavigationButton = findViewById(R.id.startNavigationButton)
        destinationSpinner = findViewById(R.id.destinationSpinner)

        // Optional UI elements
        try { versionTextView = findViewById(R.id.versionTextView) } catch (e: Exception) { Log.w(TAG,"versionTextView not found in layout") }
        try { connectButton = findViewById(R.id.connectButton); connectButton?.setOnClickListener { bindToOsmAnd() } } catch (e: Exception) { Log.w(TAG,"connectButton not found in layout") }

        tts = TextToSpeech(this, this) // Initialize TTS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        apiStatusText.text = "Status: Initializing..."

        // --- Setup UI ---
        setupProfileSpinner()
        setupDestinationSpinner()
        setupButtons()

        // --- Permissions & Location ---
        if (checkLocationPermission()) {
            getCurrentLocation()
        } else {
            requestLocationPermission()
        }

        // --- Service Binding ---
        osmandPackage = getInstalledOsmAndPackage()
        if (osmandPackage == null) {
            apiStatusText.text = "Status: OsmAnd Not Installed"
            Toast.makeText(this, "Please install OsmAnd or OsmAnd+.", Toast.LENGTH_LONG).show()
            startNavigationButton.isEnabled = false
        } else {
            // Attempt to bind immediately
            bindToOsmAnd()
        }
        Log.d(TAG, "onCreate finished")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy started")
        // Clean up TTS
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
            Log.i(TAG, "TTS shut down.")
        }
        // Clean up AIDL connection and callbacks
        unregisterVoiceCallback() // Attempt unregistration BEFORE disconnect
        disconnectFromOsmAnd()    // Unbind the service
        super.onDestroy()
        Log.d(TAG, "onDestroy finished")
    }

    //--------------------------------------------------------------------------
    // TextToSpeech Initialization
    //--------------------------------------------------------------------------
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language (US English) is not supported or missing data.")
                Toast.makeText(this, "TTS Language not supported", Toast.LENGTH_SHORT).show()
                ttsInitialized = false
            } else {
                ttsInitialized = true
                Log.i(TAG, "TTS Initialized successfully.")
                // speakInstruction("Text to speech ready.") // Optional confirmation
            }
        } else {
            Log.e(TAG, "TTS Initialization failed with status: $status")
            Toast.makeText(this, "TTS Initialization failed", Toast.LENGTH_SHORT).show()
            ttsInitialized = false
        }
    }

    //--------------------------------------------------------------------------
    // TextToSpeech Speaking Function
    //--------------------------------------------------------------------------
    private fun speakInstruction(text: String?) {
        Log.d(TAG, "speakInstruction called with text: '$text'") // Log attempt
        if (text.isNullOrBlank()) {
            Log.w(TAG, "Attempted to speak null or blank text.")
            return
        }
        if (ttsInitialized && ::tts.isInitialized) {
            Log.i(TAG, "Speaking: '$text'")
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NavInstruction") // Use utterance ID
        } else {
            Log.w(TAG, "TTS not ready, cannot speak: '$text'")
            // Toast.makeText(this, "TTS not ready", Toast.LENGTH_SHORT).show() // Avoid spamming toasts
        }
    }

    //--------------------------------------------------------------------------
    // UI Setup Methods
    //--------------------------------------------------------------------------
    private fun setupProfileSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, NAVIGATION_PROFILES)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileSpinner.adapter = adapter
        profileSpinner.setSelection(0) // Default to pedestrian
        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedProfile = NAVIGATION_PROFILES[position]
                Log.d(TAG, "Selected profile: $selectedProfile")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupDestinationSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fixedDestinations.map { it.name })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        destinationSpinner.adapter = adapter
        destinationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < fixedDestinations.size) {
                    val selectedDest = fixedDestinations[position]
                    destinationLocation = Pair(selectedDest.lat, selectedDest.lon)
                    destinationCoordinatesText.text = "Dest: ${selectedDest.name}\n(${String.format("%.6f", selectedDest.lat)}, ${String.format("%.6f", selectedDest.lon)})"
                    Log.d(TAG, "Selected destination: ${selectedDest.name} - Lat: ${destinationLocation?.first}, Lon: ${destinationLocation?.second}")
                } else {
                    destinationLocation = null
                    destinationCoordinatesText.text = "Dest: -"
                    Log.d(TAG, "No destination selected.")
                }
                updateStartNavigationButton()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                destinationLocation = null
                destinationCoordinatesText.text = "Dest: -"
                updateStartNavigationButton()
            }
        }
        // Pre-select first destination
        if (fixedDestinations.isNotEmpty()) {
            destinationSpinner.setSelection(0)
        } else {
            destinationCoordinatesText.text = "Dest: -"
            destinationLocation = null
        }
        updateStartNavigationButton()
    }

    private fun setupButtons() {
        useCurrentLocationButton.setOnClickListener {
            Log.d(TAG, "Use Current Location button clicked")
            if (checkLocationPermission()) {
                getCurrentLocation()
            } else {
                requestLocationPermission()
            }
        }
        startNavigationButton.setOnClickListener {
            Log.d(TAG, "Start Navigation button clicked")
            startNavigation()
        }
        updateStartNavigationButton() // Initial state
    }

    private fun updateStartNavigationButton() {
        val enabled = sourceLocation != null && destinationLocation != null && connected
        startNavigationButton.isEnabled = enabled
        // Log.v(TAG, "Update Start Button: Source=${sourceLocation!=null}, Dest=${destinationLocation!=null}, Connected=$connected -> Enabled=$enabled")
    }

    //--------------------------------------------------------------------------
    // Location Permissions and Retrieval
    //--------------------------------------------------------------------------
    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        Log.d(TAG, "Requesting location permission.")
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Location permission GRANTED.")
                getCurrentLocation()
            } else {
                Log.w(TAG, "Location permission DENIED.")
                sourceLocation = null
                sourceCoordinatesText.text = "Source: Permission Denied"
                Toast.makeText(this, "Location permission is required for navigation.", Toast.LENGTH_LONG).show()
                updateStartNavigationButton()
            }
        }
    }

    private fun getCurrentLocation() {
        Log.d(TAG, "Attempting to get current location...")
        if (!checkLocationPermission()) {
            Log.w(TAG, "getCurrentLocation called without permission. Requesting again.")
            requestLocationPermission()
            return
        }
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        sourceLocation = Pair(location.latitude, location.longitude)
                        sourceCoordinatesText.text = "Source: Current (${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)})"
                        Log.i(TAG, "Current location obtained: Lat: ${sourceLocation?.first}, Lon: ${sourceLocation?.second}")
                    } else {
                        Log.w(TAG, "FusedLocationProvider returned null location. Location services might be off or GPS signal weak.")
                        sourceLocation = null
                        sourceCoordinatesText.text = "Source: Location N/A"
                        Toast.makeText(this, "Could not get current location.", Toast.LENGTH_SHORT).show()
                    }
                    updateStartNavigationButton()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get current location", e)
                    sourceLocation = null
                    sourceCoordinatesText.text = "Source: Location Error"
                    Toast.makeText(this, "Error getting location.", Toast.LENGTH_SHORT).show()
                    updateStartNavigationButton()
                }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException in getCurrentLocation. This should not happen after permission check.", se)
            requestLocationPermission() // Ask again just in case state is inconsistent
        }
    }

    //--------------------------------------------------------------------------
    // OsmAnd Service Connection / Disconnection
    //--------------------------------------------------------------------------
    private fun getInstalledOsmAndPackage(): String? {
        val pm = packageManager
        for (pkg in OSMAND_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                Log.i(TAG, "Found installed OsmAnd package: $pkg")
                return pkg
            } catch (e: PackageManager.NameNotFoundException) {
                // Expected, continue checking
            } catch (e: Exception) {
                Log.e(TAG, "Error checking package $pkg", e)
            }
        }
        Log.w(TAG, "No installed OsmAnd package found among: ${OSMAND_PACKAGES.joinToString()}")
        return null
    }

    private fun bindToOsmAnd() {
        if (connected && mIOsmAndAidlInterface != null) { // Check interface too
            Log.d(TAG, "Already connected or connecting, skipping bind.")
            return
        }
        if (osmandPackage == null) {
            apiStatusText.text = "Status: OsmAnd Not Installed"
            Log.e(TAG, "Cannot bind, OsmAnd package not identified.")
            return
        }

        apiStatusText.text = "Status: Binding..."
        Log.i(TAG, "Attempting to bind to OsmAnd service: $osmandPackage (Action: net.osmand.aidl.OsmandAidlServiceV2)")
        val intent = Intent("net.osmand.aidl.OsmandAidlServiceV2") // Use V2 Action
//        intent.package = osmandPackage // Explicitly target the package
        intent.`package` = osmandPackage

        try {
            val bound = bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
            if (bound) {
                Log.d(TAG, "bindService call successful, waiting for connection callback...")
            } else {
                Log.e(TAG, "bindService call returned false. Service might not be running, available, or exported correctly in its Manifest.")
                apiStatusText.text = "Status: Bind Failed (Returned False)"
                // Consider trying to start OsmAnd if binding fails this way
                startOsmAndAndBindAgain()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during bindService. Check Manifest permissions (e.g., <queries> tag for API 30+)", e)
            apiStatusText.text = "Status: Bind Security Error"
            Toast.makeText(this, "Binding Error: Check App Permissions", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during bindService call", e)
            apiStatusText.text = "Status: Bind Exception"
        }
    }

    private fun startOsmAndAndBindAgain() {
        osmandPackage?.let { pkg ->
            Log.w(TAG, "Attempting to start OsmAnd ($pkg) and re-bind after delay...")
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    Toast.makeText(this, "Starting OsmAnd...", Toast.LENGTH_SHORT).show()
                    // Wait a bit for OsmAnd to potentially start its service
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "Retrying bind after starting OsmAnd.")
                        bindToOsmAnd() // Recursive call, ensure exit condition exists
                    }, 6000) // Increased delay (6 seconds)
                } else {
                    Log.e(TAG, "Could not get launch intent for package: $pkg")
                    apiStatusText.text = "Status: Cannot Launch OsmAnd"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error trying to launch OsmAnd package: $pkg", e)
                apiStatusText.text = "Status: Error Launching OsmAnd"
            }
        } ?: Log.e(TAG, "Cannot start OsmAnd, package name is null.")
    }

    private fun disconnectFromOsmAnd() {
        if (connected || mIOsmAndAidlInterface != null) { // Check both flags
            Log.i(TAG, "Disconnecting from OsmAnd service (unbind)...")
            try {
                // Ensure callback is unregistered first (called from onDestroy)
                // unregisterVoiceCallback() // Called separately now

                unbindService(mConnection)
                Log.i(TAG, "unbindService called.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service not registered? Error during unbindService.", e)
                // This can happen if the service died or was already unbound
            } catch (e: Exception) {
                Log.e(TAG, "Exception during unbindService", e)
            } finally {
                // Clean up state regardless of exceptions during unbind
                Log.i(TAG, "Resetting connection state after disconnect attempt.")
                connected = false
                mIOsmAndAidlInterface = null
                // Don't reset callback state here, unregister handles it
                runOnUiThread { // Ensure UI updates on main thread
                    apiStatusText.text = "Status: Disconnected"
                    updateStartNavigationButton()
                }
            }
        } else {
            Log.d(TAG, "Already disconnected or interface null, skipping unbind.")
            // Ensure state is clean even if we weren't technically 'connected'
            connected = false
            mIOsmAndAidlInterface = null
        }
    }

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            Log.i(TAG, "++++ Service CONNECTED: ${className.packageName} ++++")
            mIOsmAndAidlInterface = IOsmAndAidlInterface.Stub.asInterface(service)
            connected = true

            runOnUiThread { // Ensure UI updates on main thread
                apiStatusText.text = "Status: Connected"
                updateStartNavigationButton()
                verifyConnectionAndGetVersion() // Get version info now
                registerVoiceCallback()         // Register callback now that we are connected
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            // Called when the connection is unexpectedly lost (e.g., OsmAnd crashes)
            Log.w(TAG, "---- Service DISCONNECTED UNEXPECTEDLY: ${className.packageName} ----")
            // Connection is broken, clean up state
            unregisterVoiceCallback() // Clean up local callback state
            connected = false
            mIOsmAndAidlInterface = null
            runOnUiThread { // Ensure UI updates on main thread
                apiStatusText.text = "Status: Disconnected (Lost)"
                updateStartNavigationButton()
                Toast.makeText(this@NavigationActivity, "Lost connection to OsmAnd", Toast.LENGTH_SHORT).show()
            }
            // Optionally attempt to re-bind after a delay
            // Handler(Looper.getMainLooper()).postDelayed({ bindToOsmAnd() }, 5000)
        }

        override fun onBindingDied(name: ComponentName?) {
            // Called when the binding is dead (host process died). Treat as unexpected disconnect.
            Log.e(TAG, "**** Binding DIED for component: ${name?.flattenToString()} ****")
            onServiceDisconnected(name ?: ComponentName("?", "?")) // Reuse disconnect logic
        }

        override fun onNullBinding(name: ComponentName?) {
            // Called when bindService is called but the service rejects the binding.
            Log.e(TAG, "**** Null binding received for component: ${name?.flattenToString()} **** Check OsmAnd service definition/permissions.")
            connected = false
            mIOsmAndAidlInterface = null
            runOnUiThread { // Ensure UI updates on main thread
                apiStatusText.text = "Status: Bind Rejected (Null Binding)"
                updateStartNavigationButton()
                Toast.makeText(this@NavigationActivity, "OsmAnd rejected the connection.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun verifyConnectionAndGetVersion() {
        if (!connected || mIOsmAndAidlInterface == null) {
            Log.w(TAG, "Cannot verify version, not connected or interface is null.")
            return
        }
        Log.d(TAG, "Verifying connection by calling getAppInfo()...")
        try {
            val appInfo: AppInfoParams? = mIOsmAndAidlInterface?.getAppInfo()
            if (appInfo != null) {
                val osmAndVersion = appInfo.osmAndVersion ?: "Unknown"
                val apiVersion = appInfo.versionsInfo ?: "Unknown"
                Log.i(TAG, "OsmAnd Version: $osmAndVersion, API Version Info: $apiVersion")
                runOnUiThread {
                    apiStatusText.text = "Status: Connected (v$osmAndVersion)"
                    displayVersion(appInfo)
                }
            } else {
                Log.w(TAG, "getAppInfo() returned null. Connection might be unstable.")
                runOnUiThread { apiStatusText.text = "Status: Connected (Version ?)" }
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException while calling getAppInfo()", e)
            runOnUiThread { apiStatusText.text = "Status: Connection Error" }
            // Consider disconnecting if communication fails reliably
            disconnectFromOsmAnd() // Disconnect if basic communication fails
        } catch (e: Exception) {
            Log.e(TAG, "Exception while calling getAppInfo()", e)
            runOnUiThread { apiStatusText.text = "Status: Error getting version" }
        }
    }

    private fun displayVersion(appInfo: AppInfoParams) {
        versionTextView?.let {
            val versionText = """
                OsmAnd Version: ${appInfo.osmAndVersion ?: "?"}
                API Versions: ${appInfo.versionsInfo ?: "?"}
                Package: $osmandPackage
            """.trimIndent()
            it.text = versionText
            it.visibility = View.VISIBLE
        }
    }

    //--------------------------------------------------------------------------
    // Navigation Logic
    //--------------------------------------------------------------------------
    private fun startNavigation() {
        Log.d(TAG, "startNavigation called.")
        if (!connected || mIOsmAndAidlInterface == null) {
            Toast.makeText(this, "Not connected to OsmAnd service.", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Start navigation requested but not connected.")
            if (!connected) bindToOsmAnd() // Try to connect if button somehow enabled while disconnected
            return
        }

        val source = sourceLocation
        if (source == null) {
            Toast.makeText(this, "Source location not available.", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Start navigation requested but source location is null.")
            if (checkLocationPermission()) getCurrentLocation() else requestLocationPermission()
            return
        }

        val destination = destinationLocation
        if (destination == null) {
            Toast.makeText(this, "Please select a destination.", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Start navigation requested but destination location is null.")
            return
        }

        val destName = if (destinationSpinner.selectedItemPosition >= 0 && destinationSpinner.selectedItemPosition < fixedDestinations.size) {
            fixedDestinations[destinationSpinner.selectedItemPosition].name
        } else {
            "Selected Destination" // Fallback
        }
        val sourceName = "Current Location"

        Log.i(TAG, "Requesting navigation: FROM '$sourceName' (${source.first}, ${source.second}) " +
                "TO '$destName' (${destination.first}, ${destination.second}) using profile '$selectedProfile'")

        navigateToLocation(source.first, source.second, destination.first, destination.second, sourceName, destName)
    }

    private fun navigateToLocation(
        sourceLat: Double, sourceLon: Double,
        destLat: Double, destLon: Double,
        sourceName: String, destName: String
    ) {
        if (!connected || mIOsmAndAidlInterface == null) {
            Log.e(TAG, "Cannot navigate, service not connected.")
            Toast.makeText(this, "Not connected to OsmAnd.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Use NavigateParams for direct navigation start
            val navParams = NavigateParams(
                // Set source lat/lon to 0,0 to use current GPS location within OsmAnd
                // Otherwise, provide the specific source coords.
                // If using current location button, setting 0,0 might be more reliable
                // sourceName, sourceLat, sourceLon,
                null, 0.0, 0.0, // Let OsmAnd use current GPS as start
                destName, destLat, destLon,       // Destination point
                selectedProfile,                  // Navigation profile
                true,                             // Start navigation immediately
                true                             // Force recalculate (usually false)
            )

            Log.d(TAG, "Calling mIOsmAndAidlInterface.navigate with params: $navParams")
            val success: Boolean = mIOsmAndAidlInterface?.navigate(navParams) ?: false

            if (success) {
                Log.i(TAG, "OsmAnd navigate call successful (returned true). OsmAnd should start navigating.")
                Toast.makeText(this, "Navigation request sent.", Toast.LENGTH_SHORT).show()
            } else {
                Log.e(TAG, "OsmAnd navigate call failed (returned false or null interface). Check OsmAnd state/logs.")
                Toast.makeText(this, "Failed to send navigation request.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException during navigate call", e)
            Toast.makeText(this, "Error communicating with OsmAnd for navigation.", Toast.LENGTH_LONG).show()
            disconnectFromOsmAnd() // Service might have died
        } catch (e: Exception) {
            Log.e(TAG, "Exception during navigate call", e)
            Toast.makeText(this, "Error initiating navigation.", Toast.LENGTH_LONG).show()
        }
    }

    //--------------------------------------------------------------------------
    // AIDL Voice Callback Registration and Implementation
    //--------------------------------------------------------------------------

    private fun registerVoiceCallback() {
        if (!connected || mIOsmAndAidlInterface == null) {
            Log.w(TAG, "Cannot register voice callback, not connected.")
            return
        }
        if (voiceCallback != null) {
            Log.d(TAG, "Voice callback already registered (Local instance exists). Skipping.")
            return
        }

        try {
            Log.i(TAG, "Registering for voice router messages...")
            val callbackInstance = AidlVoiceCallback() // Create new instance
            val params = ANavigationVoiceRouterMessageParams() // Params often not needed but required by AIDL method signature

            // Register the callback
            val id = mIOsmAndAidlInterface?.registerForVoiceRouterMessages(params, callbackInstance) ?: -1L

            if (id != -1L) {
                // Success - store the instance and ID
                voiceCallback = callbackInstance // Store the successfully registered instance
                voiceCallbackId = id
                Log.i(TAG, "Successfully registered voice callback. Received ID: $voiceCallbackId")
                Toast.makeText(this, "Voice instructions enabled.", Toast.LENGTH_SHORT).show()
            } else {
                // Registration technically succeeded (no exception) but returned -1 ID.
                // This might indicate an issue on OsmAnd's side or incompatible versions.
                Log.w(TAG, "Registered voice callback, but received ID is -1. May not receive updates.")
                voiceCallback = null // Don't store instance if ID is invalid
                voiceCallbackId = -1L
                Toast.makeText(this, "Voice registration issue (ID -1).", Toast.LENGTH_SHORT).show()
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException registering voice callback", e)
            voiceCallback = null // Clean up local state
            voiceCallbackId = -1L
            Toast.makeText(this, "Error registering for voice.", Toast.LENGTH_SHORT).show()
            disconnectFromOsmAnd() // Connection likely broken
        } catch (e: Exception) {
            Log.e(TAG, "Exception registering voice callback", e)
            voiceCallback = null // Clean up local state
            voiceCallbackId = -1L
            Toast.makeText(this, "Error registering for voice.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun unregisterVoiceCallback() {
        // Check if we have an interface AND a local callback instance to unregister
        if (mIOsmAndAidlInterface == null || voiceCallback == null) {
            if (voiceCallback != null || voiceCallbackId != -1L) { // Log only if we thought we had one
                Log.d(TAG, "Skipping voice unregistration: Interface=${mIOsmAndAidlInterface != null}, Local Callback Instance Exists=${voiceCallback != null}")
            }
            // Ensure local state is reset anyway
            voiceCallbackId = -1L
            voiceCallback = null
            return
        }

        val callbackInstanceToUnregister = voiceCallback // Get ref before nulling
        Log.i(TAG, "Attempting to unregister voice callback (Local instance: ${callbackInstanceToUnregister})...")
        try {
            // *** THE FIX: Pass null as the callback parameter to unregister ***
            val params = ANavigationVoiceRouterMessageParams() // Provide params if needed by AIDL signature
            val result = mIOsmAndAidlInterface?.registerForVoiceRouterMessages(params, null) // <<< Pass NULL here!

            Log.i(TAG, "Called registerForVoiceRouterMessages with null callback for unregistration. Result (if any): $result")
            // Toast.makeText(this, "Voice instructions disabled.", Toast.LENGTH_SHORT).show() // Optional confirmation

        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException unregistering voice callback", e)
            // If this happens, the connection is likely broken anyway
        } catch (e: Exception) {
            Log.e(TAG, "Exception unregistering voice callback", e)
        } finally {
            // Reset local state AFTER attempting unregistration, regardless of success/failure
            Log.d(TAG, "Resetting local voice callback state post-unregistration attempt.")
            voiceCallbackId = -1L
            voiceCallback = null // <<< Crucial: clear the local reference
        }
    }


    /**
     * Inner class implementing the AIDL callback interface.
     */
    private inner class AidlVoiceCallback : IOsmAndAidlCallback.Stub() {

        // --- CORE METHOD FOR VOICE ---
        @Throws(RemoteException::class)
        override fun onVoiceRouterNotify(params: OnVoiceNavigationParams?) {
            // **** MOST IMPORTANT LOG **** Is this called repeatedly?
            Log.i(TAG, "<<<<< Callback: onVoiceRouterNotify received! (Params object is null: ${params == null}) >>>>>")

            if (params == null) {
                Log.w(TAG, "onVoiceRouterNotify received null params object. Cannot process.")
                return
            }

            // **** Log raw object structure for debugging AIDL/reflection ****
            Log.d(TAG, "Received OnVoiceNavigationParams raw content: $params") // Log the object itself

            var prompt: String? = null
            try {
                // Define potential methods to try via reflection
                val methodsToCheck = listOf(
                    "getCommands",      // Common in older/some versions
                    "getPlayed"
//                    "getPrompt",       // Another possibility
//                    "getText",         // Generic getter
//                    "getDescription",  // Potential field
//                    "getMessage",      // Another generic possibility
//                    "getInstruction"   // Logical name for navigation instruction
//                )
                )
                Log.d(TAG, "Attempting to extract prompt via reflection using methods: ${methodsToCheck.joinToString()}")

                for (methodName in methodsToCheck) {
                    try {
                        val method = params.javaClass.getMethod(methodName)
                        val result = method.invoke(params)
                        if (result is String && result.isNotBlank()) {
                            prompt = result
                            Log.i(TAG, "SUCCESS: Found prompt via '$methodName()': '$prompt'")
                            break // Found a usable prompt
                        } else {
                            Log.v(TAG, "Method '$methodName()' returned: '$result' (Type: ${result?.javaClass?.simpleName}) - Not usable or blank.")
                        }
                    } catch (e: NoSuchMethodException) {
                        Log.v(TAG, "Method '$methodName()' not found in received params object.")
                    } catch (e: IllegalAccessException) {
                        Log.w(TAG, "IllegalAccessException invoking '$methodName()'", e)
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        Log.w(TAG, "InvocationTargetException invoking '$methodName()'", e)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error invoking '$methodName()' via reflection", e)
                    }
                }

                // If reflection failed, log clearly
                if (prompt.isNullOrBlank()) {
                    Log.w(TAG, "Could not extract a usable prompt string via reflection methods. Check OsmAnd version/AIDL definition.")
                    // Log the raw params again for context when extraction fails
                    Log.w(TAG, "Raw params object that failed extraction: $params")
                    // As a last resort, you could try accessing known fields if reflection fails,
                    // but this is very brittle. Example (replace 'promptField' with actual name):
                    /*
                    try {
                        val field = params.javaClass.getDeclaredField("promptField")
                        field.isAccessible = true // If private
                        val value = field.get(params)
                        if (value is String && value.isNotBlank()) {
                            prompt = value
                            Log.i(TAG, "SUCCESS: Found prompt via direct FIELD access ('promptField'): '$prompt'")
                        }
                    } catch (fe: Exception) {
                        Log.v(TAG, "Direct field access also failed: ${fe.message}")
                    }
                    */
                }

            } catch (e: Exception) {
                Log.e(TAG, "General exception while processing OnVoiceNavigationParams object", e)
            }

            // --- Speaking Logic ---
            if (!prompt.isNullOrBlank()) {
                Log.i(TAG, "Dispatching instruction to TTS handler: '$prompt'")
                // Ensure TTS runs on the main thread
                Handler(Looper.getMainLooper()).post {
                    speakInstruction(prompt)
                }
            } else {
                // **** Enhanced log when no prompt found ****
                Log.w(TAG, "No valid voice prompt string found in OnVoiceNavigationParams to speak after processing. Raw params were: $params")
            }
        }

        // --- Other Required IOsmAndAidlCallback Methods ---
        // Implement them, even if empty, to satisfy the interface contract. Add logging if needed.

        @Throws(RemoteException::class)
        override fun onUpdate() {
            // Log.v(TAG, "Callback: onUpdate called")
        }

        @Throws(RemoteException::class)
        override fun onContextMenuButtonClicked(buttonId: Int, pointId: String?, layerId: String?) {
            Log.d(TAG, "Callback: onContextMenuButtonClicked: button=$buttonId, point=${pointId ?: "null"}, layer=${layerId ?: "null"}")
        }

        @Throws(RemoteException::class)
        override fun onGpxBitmapCreated(bitmap: AGpxBitmap?) {
            Log.d(TAG, "Callback: onGpxBitmapCreated received AGpxBitmap: ${bitmap != null}")
        }

        @Throws(RemoteException::class)
        override fun onSearchComplete(results: MutableList<SearchResult>?) {
            Log.d(TAG, "Callback: onSearchComplete: results count=${results?.size ?: "null"}")
        }

        @Throws(RemoteException::class)
        override fun onAppInitialized() {
            Log.i(TAG, "Callback: onAppInitialized")
            // Could potentially re-verify connection or re-register callbacks here if needed
            //runOnUiThread { verifyConnectionAndGetVersion() } // Example: Re-check version on init
        }

        @Throws(RemoteException::class)
        override fun updateNavigationInfo(directionInfo: ADirectionInfo?) {
            // This provides structured info (distance, next turn type etc.) - NOT the voice prompt string.
            // Log only if needed, can be spammy. Check ADirectionInfo.aidl structure.
            Log.v(TAG, "Callback: updateNavigationInfo received: ${directionInfo}")
        }

        @Throws(RemoteException::class)
        override fun onKeyEvent(event: KeyEvent?) {
            Log.d(TAG, "Callback: onKeyEvent received: ${event?.keyCode}")
        }

        @Throws(RemoteException::class)
        override fun onLogcatMessage(params: OnLogcatMessageParams?) {
            // Only relevant if you explicitly register for logcat messages via AIDL
            Log.v(TAG, "Callback: onLogcatMessage received: $params")
        }

        // Add implementations for any other abstract methods defined in your specific
        // IOsmAndAidlCallback.aidl file.

    } // End of AidlVoiceCallback inner class

} // End of NavigationActivity class