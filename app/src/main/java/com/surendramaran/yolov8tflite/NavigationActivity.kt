package com.surendramaran.yolov8tflite//package com.surendramaran.yolov8tflite //package com.surendramaran.yolov8tflite
//import android.Manifest
//import android.content.*
//import android.content.pm.PackageManager
//import android.location.Location
//import android.location.LocationListener
//import android.location.LocationManager
//import android.net.Uri
//import android.os.Bundle
//import android.os.IBinder
//import android.os.RemoteException
//import android.util.Log
//import android.view.View
//import android.widget.*
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.app.ActivityCompat
//import androidx.core.content.ContextCompat
//import kotlinx.coroutines.*
//
//import net.osmand.aidlapi.IOsmAndAidlInterface
//import net.osmand.aidlapi.IOsmAndAidlCallback
//
//import net.osmand.aidlapi.navigation
//import net.osmand.aidlapi.route.AidlRouteInfoParams
//import net.osmand.aidlapi.voice.AidlVoiceRouterParams
//
//class NavigationActivity : AppCompatActivity(), LocationListener {
//
//    // Location stuff (mostly unchanged)
//    private lateinit var locationManager: LocationManager
//    private var currentLocation: Location? = null
//    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
//
//    // UI Elements
//    private lateinit var startLocationEdit: EditText
//    private lateinit var endLocationEdit: AutoCompleteTextView // Changed type
//    private lateinit var navigateButton: Button
//    private lateinit var stopNavigateButton: Button
//    private lateinit var useCurrentLocationCheckbox: RadioButton
//    private lateinit var useCustomLocationCheckbox: RadioButton
//    private lateinit var transportModeGroup: RadioGroup
//    private lateinit var apiStatusText: TextView
//    private lateinit var navigationStatusText: TextView
//    private lateinit var progressBar: ProgressBar
//
//    // --- OsmAnd AIDL Service Binding ---
//    private var osmAndAidlService: IOsmAndAidlInterface? = null
//    private var isBound = false
//    private val osmandPackageName = "net.osmand.plus" // Or net.osmand for free version
//    private val osmandApiPluginPackageName = "net.osmand.aidlapi" // OsmAnd API Plugin package
//    private val osmandApiServiceClassName = "net.osmand.aidlapi.OsmandAidlService" // FQN of the service
//
//    // --- AIDL Callback ---
//    private val callback = object : IOsmAndAidlCallback.Stub() {
//        // Note: These callbacks run on a Binder thread, not the main UI thread!
//        override fun updateNavigationInfo(params: AidlNavInfoParams?) {
//            if (params == null) return
//            Log.d("OsmAndCallback", "NavInfo: Dist to next turn: ${params.distanceToNextTurn}")
//            // Example: Update UI (must post to main thread)
//            runOnUiThread {
//                val nextTurn = params.distanceToNextTurn // meters
//                val turnType = params.nextTurnType // Type of turn
//                navigationStatusText.text = "Next: ${formatDistance(nextTurn)} (${turnType ?: "straight"}), Total Left: ${formatDistance(params.distanceToTarget.toFloat())}"
//            }
//        }
//
//        override fun updateRouteInfo(params: AidlRouteInfoParams?) {
//            if (params == null) return
//            Log.d("OsmAndCallback", "RouteInfo: Total distance: ${params.totalDistance}, Status: ${params.routeCalculationProgress}")
//            runOnUiThread {
//                progressBar.visibility = if (params.routeCalculationProgress in 1..99) View.VISIBLE else View.GONE
//                progressBar.isIndeterminate = false
//                progressBar.progress = params.routeCalculationProgress
//
//                if (params.routeCalculationProgress == 100) {
//                    navigationStatusText.text = "Route calculated. Total: ${formatDistance(params.totalDistance.toFloat())}"
//                    navigateButton.isEnabled = true // Re-enable if disabled during calculation
//                    stopNavigateButton.visibility = View.VISIBLE
//                } else if (params.routeCalculationProgress < 0) { // Error codes are negative
//                    navigationStatusText.text = "Route calculation failed (Error: ${params.routeCalculationProgress})"
//                    Toast.makeText(this@NavigationActivity, "Route calculation failed.", Toast.LENGTH_SHORT).show()
//                    navigateButton.isEnabled = true
//                    stopNavigateButton.visibility = View.GONE
//                } else {
//                    navigationStatusText.text = "Calculating route: ${params.routeCalculationProgress}%"
//                }
//            }
//        }
//
//        override fun updateVoiceRouter(params: AidlVoiceRouterParams?) {
//            // This callback provides information needed if *you* were to implement TTS.
//            // Since we want OsmAnd to handle it, we might not need this directly,
//            // but good to log what comes through.
//            if (params == null) return
//            Log.d("OsmAndCallback", "VoiceRouter: Commands: ${params.commands?.contentToString()}")
//            // If OsmAnd's internal TTS isn't working, you could potentially use
//            // params.commands with Android's TextToSpeech here (run on main thread).
//        }
//
//        // Implement other callback methods as needed (e.g., map updates if you were showing a map)
//        override fun updateMapInfo(latitude: Double, longitude: Double, zoom: Float, rotate: Float) {}
//        override fun updateWaypointInfo(pointsPassed: Int, pointsLeft: Int) {}
//        override fun onPointPassed(index: Int) {}
//    }
//
//    // --- Service Connection ---
//    private val serviceConnection = object : ServiceConnection {
//        override fun onServiceConnected(className: ComponentName, service: IBinder) {
//            Log.d("NavigationActivity", "OsmAnd API Service Connected")
//            osmAndAidlService = IOsmAndAidlInterface.Stub.asInterface(service)
//            isBound = true
//            apiStatusText.text = "OsmAnd API Status: Connected"
//            apiStatusText.setTextColor(ContextCompat.getColor(this@NavigationActivity, R.color.teal_700)) // Example color
//
//            // Register our callback to receive updates from OsmAnd
//            try {
//                osmAndAidlService?.registerCallback(callback)
//                Log.d("NavigationActivity", "AIDL Callback registered")
//
//                // Optional: Configure voice guidance (ensure OsmAnd uses its TTS)
//                // Check the AidlVoiceRouterParams or IOsmAndAidlInterface for methods
//                // to enable/configure voice prompts if needed. Often it respects
//                // the settings within the main OsmAnd app. You might need to tell it
//                // which voice profile to use if not default.
//                osmAndAidlService?.setVoiceRouterParams(null) // Passing null might use defaults, or check API sample
//
//            } catch (e: RemoteException) {
//                Log.e("NavigationActivity", "Error registering callback or setting voice params", e)
//                apiStatusText.text = "OsmAnd API Status: Callback Error"
//            }
//            // Enable the navigate button once connected
//            navigateButton.isEnabled = true
//            progressBar.visibility = View.GONE // Hide progress bar initially
//        }
//
//        override fun onServiceDisconnected(className: ComponentName) {
//            Log.d("NavigationActivity", "OsmAnd API Service Disconnected")
//            // Unregister callback (though service is already gone)
//            // try { osmAndAidlService?.unregisterCallback(callback) } catch (e: Exception) {} // Best effort
//            osmAndAidlService = null
//            isBound = false
//            apiStatusText.text = "OsmAnd API Status: Disconnected"
//            apiStatusText.setTextColor(ContextCompat.getColor(this@NavigationActivity, android.R.color.holo_red_dark))
//            // Disable navigation buttons
//            navigateButton.isEnabled = false
//            stopNavigateButton.visibility = View.GONE
//            progressBar.visibility = View.GONE
//            navigationStatusText.text = "Navigation: Idle"
//
//            // Optional: Try to rebind? Or inform user.
//            Toast.makeText(this@NavigationActivity, "OsmAnd connection lost.", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_navigation)
//
//        // Initialize UI
//        apiStatusText = findViewById(R.id.apiStatusText)
//        navigationStatusText = findViewById(R.id.navigationStatusText)
//        startLocationEdit = findViewById(R.id.startLocationEdit)
//        endLocationEdit = findViewById(R.id.endLocationEdit) // Ensure this is MaterialAutoCompleteTextView
//        navigateButton = findViewById(R.id.navigateButton)
//        stopNavigateButton = findViewById(R.id.stopNavigateButton)
//        useCurrentLocationCheckbox = findViewById(R.id.useCurrentLocationRadio)
//        useCustomLocationCheckbox = findViewById(R.id.useCustomLocationRadio)
//        transportModeGroup = findViewById(R.id.transportModeGroup)
//        progressBar = findViewById(R.id.progressBar)
//
//        // Initially disable nav button until service connects
//        navigateButton.isEnabled = false
//        stopNavigateButton.visibility = View.GONE // Hide stop button initially
//
//        requestLocationPermissions() // Request location permission first
//        setupRadioListeners()
//
//        navigateButton.setOnClickListener {
//            startNavigationViaApi()
//        }
//        stopNavigateButton.setOnClickListener {
//            stopNavigationViaApi()
//        }
//
//        // Check if API plugin is installed and try to bind
//        if (!isAppInstalled(osmandApiPluginPackageName)) {
//            apiStatusText.text = "OsmAnd API Plugin NOT INSTALLED"
//            apiStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
//            Toast.makeText(this, "Please install the 'OsmAnd API plugin' from the Play Store.", Toast.LENGTH_LONG).show()
//            // Optionally show install button like before, but for the API plugin
//            showInstallButton(osmandApiPluginPackageName, "Install OsmAnd API Plugin")
//        } else {
//            apiStatusText.text = "OsmAnd API Status: Connecting..."
//            bindToOsmAndService()
//        }
//    }
//
//    private fun isAppInstalled(packageName: String): Boolean {
//        return try {
//            packageManager.getPackageInfo(packageName, 0)
//            true
//        } catch (e: PackageManager.NameNotFoundException) {
//            false
//        }
//    }
//
//    private fun showInstallButton(packageName: String, buttonText: String) {
//        val installButton = Button(this).apply {
//            text = buttonText
//            setOnClickListener {
//                try {
//                    startActivity(Intent(Intent.ACTION_VIEW,
//                        Uri.parse("market://details?id=$packageName")))
//                } catch (e: ActivityNotFoundException) {
//                    startActivity(Intent(Intent.ACTION_VIEW,
//                        Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
//                }
//            }
//        }
//        findViewById<LinearLayout>(R.id.buttonContainer)?.addView(installButton, 0) // Add before progress bar
//    }
//
//
//    private fun setupRadioListeners() {
//        useCurrentLocationCheckbox.setOnCheckedChangeListener { _, isChecked ->
//            startLocationEdit.isEnabled = !isChecked
//            if (isChecked) {
//                startLocationEdit.setText(if (currentLocation != null) "Current Location" else "")
//                startLocationEdit.hint = "Current Location"
//            }
//        }
//        useCustomLocationCheckbox.setOnCheckedChangeListener { _, isChecked ->
//            startLocationEdit.isEnabled = isChecked
//            if (isChecked) {
//                startLocationEdit.setText("")
//                startLocationEdit.hint = "Enter start (Lat,Lon or Address)"
//            }
//        }
//    }
//
//    private fun bindToOsmAndService() {
//        if (!isBound) {
//            try {
//                val intent = Intent()
//                // Use explicit intent targeting the Aidl Service Class Name
//                intent.setClassName(osmandApiPluginPackageName, osmandApiServiceClassName)
//
//                val serviceExists = packageManager.resolveService(intent, 0) != null
//                if (serviceExists) {
//                    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
//                    Log.i("NavigationActivity", "Attempting to bind to OsmAnd API service...")
//                } else {
//                    Log.e("NavigationActivity", "OsmAnd API Service ($osmandApiServiceClassName) not found in package $osmandApiPluginPackageName.")
//                    apiStatusText.text = "OsmAnd API Service Not Found"
//                    apiStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
//                    Toast.makeText(this, "OsmAnd API Service not found. Ensure plugin is installed and enabled in OsmAnd.", Toast.LENGTH_LONG).show()
//                    navigateButton.isEnabled = false // Keep disabled
//                }
//            } catch (e: Exception) {
//                Log.e("NavigationActivity", "Error binding to service", e)
//                apiStatusText.text = "OsmAnd API Status: Bind Error"
//                apiStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
//                Toast.makeText(this, "Error connecting to OsmAnd API: ${e.message}", Toast.LENGTH_LONG).show()
//            }
//        } else {
//            Log.d("NavigationActivity", "Already bound to service.")
//        }
//    }
//
//    private fun unbindFromOsmAndService() {
//        if (isBound) {
//            Log.d("NavigationActivity", "Unbinding from OsmAnd API service...")
//            // Unregister callback first
//            try {
//                osmAndAidlService?.unregisterCallback(callback)
//                Log.d("NavigationActivity", "AIDL Callback unregistered")
//            } catch (e: RemoteException) {
//                Log.e("NavigationActivity", "Error unregistering callback", e)
//            } catch (e: Exception) { // Catch broader exceptions during unbind
//                Log.e("NavigationActivity", "Exception during unregisterCallback", e)
//            }
//
//            try {
//                unbindService(serviceConnection)
//            } catch (e: Exception) {
//                Log.e("NavigationActivity", "Exception during unbindService", e)
//            } finally { // Ensure state is updated even if unbind throws error
//                isBound = false
//                osmAndAidlService = null
//                // Update UI state if needed (already handled in onServiceDisconnected, but good safety)
//                apiStatusText.text = "OsmAnd API Status: Disconnected"
//                navigateButton.isEnabled = false
//                stopNavigateButton.visibility = View.GONE
//            }
//        }
//    }
//
//    private fun startNavigationViaApi() {
//        if (!isBound || osmAndAidlService == null) {
//            Toast.makeText(this, "OsmAnd API not connected.", Toast.LENGTH_SHORT).show()
//            // Try to rebind if not connected
//            if (!isBound) bindToOsmAndService()
//            return
//        }
//
//        val endLocationText = endLocationEdit.text.toString().trim()
//        if (endLocationText.isEmpty()) {
//            Toast.makeText(this, "Please enter destination", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        var startPoint: AidlMapPoint? = null
//        val endPoint = AidlMapPoint(endLocationText) // API *might* handle geocoding for names
//
//        if (useCurrentLocationCheckbox.isChecked) {
//            if (currentLocation == null) {
//                Toast.makeText(this, "Current location not available yet.", Toast.LENGTH_SHORT).show()
//                return
//            }
//            startPoint = AidlMapPoint(currentLocation!!.latitude, currentLocation!!.longitude, "Current Location")
//        } else {
//            val startLocationText = startLocationEdit.text.toString().trim()
//            if (startLocationText.isEmpty()) {
//                Toast.makeText(this, "Please enter start location or use current location", Toast.LENGTH_SHORT).show()
//                return
//            }
//            // Try parsing Lat,Lon first, otherwise send as address string
//            val coords = tryParseCoordinates(startLocationText)
//            if (coords != null) {
//                startPoint = AidlMapPoint(coords.first, coords.second, "Custom Start")
//            } else {
//                startPoint = AidlMapPoint(startLocationText) // Send address string
//            }
//        }
//
//        val profile = when (transportModeGroup.checkedRadioButtonId) {
//            R.id.carModeRadio -> "car" // Use OsmAnd internal profile names
//            R.id.bikeModeRadio -> "bicycle"
//            R.id.walkModeRadio -> "pedestrian"
//            else -> "car"
//        }
//
//        Log.d("NavigationActivity", "Starting API navigation. Start: ${startPoint?.displayName ?: startPoint?.lat},${startPoint?.lon}, Dest: ${endPoint.displayName ?: endPoint.lat},${endPoint.lon}, Profile: $profile")
//
//        try {
//            // Disable button, show progress
//            navigateButton.isEnabled = false
//            stopNavigateButton.visibility = View.GONE // Hide stop until route calculated
//            progressBar.visibility = View.VISIBLE
//            progressBar.isIndeterminate = true // Indeterminate until calculation starts
//            navigationStatusText.text = "Requesting route..."
//
//            // Call the AIDL method to start navigation
//            // The `navigate` method likely calculates and starts navigation
//            // Consult OsmAnd API sample for exact method and parameters
//            // `startPoint` can be null to use OsmAnd's current location internally
//            val success = osmAndAidlService?.navigate(
//                startPoint, // Can be null
//                endPoint,
//                profile,
//                true // Start navigation immediately after calculation
//            )
//
//            if (success == true) {
//                Log.i("NavigationActivity", "navigate() API call successful. Waiting for RouteInfo callback...")
//                // Don't re-enable button here, wait for callback confirmation
//            } else {
//                Log.w("NavigationActivity", "navigate() API call returned false or service was null.")
//                Toast.makeText(this, "Failed to initiate navigation request.", Toast.LENGTH_SHORT).show()
//                navigateButton.isEnabled = true // Re-enable button on immediate failure
//                progressBar.visibility = View.GONE
//                navigationStatusText.text = "Navigation: Request Failed"
//            }
//
//        } catch (e: RemoteException) {
//            Log.e("NavigationActivity", "RemoteException during navigation request", e)
//            Toast.makeText(this, "Error communicating with OsmAnd: ${e.message}", Toast.LENGTH_LONG).show()
//            navigateButton.isEnabled = true
//            progressBar.visibility = View.GONE
//            navigationStatusText.text = "Navigation: API Error"
//        } catch (e: Exception) {
//            Log.e("NavigationActivity", "Exception during navigation request", e)
//            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
//            navigateButton.isEnabled = true
//            progressBar.visibility = View.GONE
//            navigationStatusText.text = "Navigation: Error"
//        }
//    }
//
//    private fun tryParseCoordinates(input: String): Pair<Double, Double>? {
//        try {
//            val parts = input.split(",")
//            if (parts.size == 2) {
//                val lat = parts[0].trim().toDouble()
//                val lon = parts[1].trim().toDouble()
//                // Basic validation, OsmAnd API might handle stricter checks
//                if (lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0) {
//                    return Pair(lat, lon)
//                }
//            }
//        } catch (e: NumberFormatException) {
//            // Not coordinates
//        }
//        return null
//    }
//
//    private fun stopNavigationViaApi() {
//        if (!isBound || osmAndAidlService == null) {
//            Toast.makeText(this, "OsmAnd API not connected.", Toast.LENGTH_SHORT).show()
//            return
//        }
//        try {
//            val success = osmAndAidlService?.stopNavigation()
//            if (success == true) {
//                Log.i("NavigationActivity", "stopNavigation() API call successful.")
//                navigationStatusText.text = "Navigation: Stopped"
//                stopNavigateButton.visibility = View.GONE
//                navigateButton.isEnabled = true // Re-enable start button
//            } else {
//                Log.w("NavigationActivity", "stopNavigation() API call returned false or service was null.")
//                Toast.makeText(this, "Failed to stop navigation via API.", Toast.LENGTH_SHORT).show()
//            }
//        } catch (e: RemoteException) {
//            Log.e("NavigationActivity", "RemoteException during stop navigation", e)
//            Toast.makeText(this, "Error stopping navigation: ${e.message}", Toast.LENGTH_LONG).show()
//        }
//    }
//
//
//    // --- Location Listener Implementation (Unchanged, but provides currentLocation) ---
//    override fun onLocationChanged(location: Location) {
//        currentLocation = location
//        Log.d("NavigationActivity", "Location Update: ${location.latitude}, ${location.longitude}")
//        if (useCurrentLocationCheckbox.isChecked) {
//            // Optionally update UI text if needed, but main use is having the location object
//            startLocationEdit.setText("Current Location")
//        }
//        // Send location update to OsmAnd API if needed?
//        // Usually OsmAnd uses its own location provider when navigating,
//        // but check if there's an API call like `updateMyLocation(lat, lon)` if required.
//        // try { osmAndAidlService?.updateMyLocation(AidlMapPoint(location.latitude, location.longitude)) } catch (e: Exception) {}
//    }
//
//    // --- Permission Handling (Unchanged) ---
//    private fun requestLocationPermissions() {
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
//            PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(
//                this,
//                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
//                LOCATION_PERMISSION_REQUEST_CODE
//            )
//        } else {
//            setupLocationManager()
//        }
//    }
//
//    override fun onRequestPermissionsResult(
//        requestCode: Int,
//        permissions: Array<out String>,
//        grantResults: IntArray
//    ) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
//            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                setupLocationManager()
//            } else {
//                Toast.makeText(this, "Location permission denied. Navigation may not work correctly.", Toast.LENGTH_LONG).show()
//            }
//        }
//    }
//
//
//    private fun setupLocationManager() {
//        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
//        try {
//            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
//                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 5f, this) // Update frequency
//                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
//            }
//        } catch (e: SecurityException) {
//            Log.e("NavigationActivity", "SecurityException setting up location manager", e)
//            Toast.makeText(this, "Location permission error.", Toast.LENGTH_SHORT).show()
//        } catch (e: Exception) {
//            Log.e("NavigationActivity", "Error setting up location manager", e)
//            Toast.makeText(this, "Could not start location updates.", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    // --- Lifecycle Management for Service Binding & Location ---
//    override fun onStart() {
//        super.onStart()
//        // Bind to the service when the activity becomes visible
//        if (!isAppInstalled(osmandApiPluginPackageName)) {
//            Log.w("NavigationActivity", "OsmAnd API Plugin not installed onStart.")
//            // Button might be shown in onCreate already
//        } else {
//            bindToOsmAndService()
//        }
//    }
//
//    override fun onStop() {
//        super.onStop()
//        // Unbind from the service when the activity is no longer visible
//        unbindFromOsmAndService()
//    }
//
//    override fun onPause() {
//        super.onPause()
//        // Stop location updates when paused to save battery
//        if (::locationManager.isInitialized) {
//            try {
//                locationManager.removeUpdates(this)
//                Log.d("NavigationActivity", "Location updates paused.")
//            } catch (e: Exception) {
//                Log.e("NavigationActivity", "Error removing location updates", e)
//            }
//        }
//    }
//
//    override fun onResume() {
//        super.onResume()
//        // Restart location updates if permission granted
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
//            if (::locationManager.isInitialized) {
//                try {
//                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 5f, this)
//                    Log.d("NavigationActivity", "Location updates resumed.")
//                } catch (e: SecurityException) {
//                    Log.e("NavigationActivity", "SecurityException resuming location updates", e)
//                } catch (e: Exception) {
//                    Log.e("NavigationActivity", "Error resuming location updates", e)
//                }
//            } else {
//                setupLocationManager() // Initialize if it wasn't ready
//            }
//        }
//    }
//
//
//    // --- Utility ---
//    private fun formatDistance(meters: Float): String {
//        return if (meters < 1000) {
//            "${meters.toInt()} m"
//        } else {
//            String.format("%.1f km", meters / 1000.0)
//        }
//    }
//
//    // Deprecated LocationListener methods (can be left empty)
//    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
//    override fun onProviderEnabled(provider: String) {}
//    override fun onProviderDisabled(provider: String) {}
//}