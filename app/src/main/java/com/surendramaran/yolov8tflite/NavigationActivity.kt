package com.surendramaran.yolov8tflite

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.info.AppInfoParams
import net.osmand.aidlapi.navigation.NavigateSearchParams
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText

class NavigationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OsmAndAPI"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001

        // These packages match exactly what's in the API demo app
        private val OSMAND_PACKAGES = listOf(
            "net.osmand",
            "net.osmand.plus",
            "net.osmand.dev"
        )

        // Navigation profiles available in OsmAnd
        private val NAVIGATION_PROFILES = arrayOf(
            "car", "bicycle", "pedestrian", "public_transport", "boat"
        )

        // Nominatim API for geocoding (OpenStreetMap service)
        private const val NOMINATIM_API_URL = "https://nominatim.openstreetmap.org/search"
    }

    // UI Elements
    private lateinit var apiStatusText: TextView
    private var versionTextView: TextView? = null
    private var connectButton: Button? = null
    private lateinit var sourceLocationInput: AutoCompleteTextView
    private lateinit var destinationLocationInput: AutoCompleteTextView
    private lateinit var sourceCoordinatesText: TextView
    private lateinit var destinationCoordinatesText: TextView
    private lateinit var useCurrentLocationButton: Button
    private lateinit var profileSpinner: Spinner
    private lateinit var startNavigationButton: Button


    // OsmAnd Service
    private var mIOsmAndAidlInterface: IOsmAndAidlInterface? = null
    private var osmandPackage: String? = null
    private var connected = false
    private val app: Application? = null

    // Location data
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var sourceLocation: Pair<Double, Double>? = null
    private var destinationLocation: Pair<Double, Double>? = null
    private var selectedProfile: String = "car" // Default profile

    // Search suggestions
    private val locationSuggestions = mutableListOf<LocationSuggestion>()

    // Add these to your class members
    private val sourceLocationSuggestions = mutableListOf<LocationSuggestion>()
    private val destinationLocationSuggestions = mutableListOf<LocationSuggestion>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        // Initialize UI elements
        apiStatusText = findViewById(R.id.apiStatusText)
        sourceLocationInput = findViewById(R.id.sourceLocationInput)
        destinationLocationInput = findViewById(R.id.destinationLocationInput)
        sourceCoordinatesText = findViewById(R.id.sourceCoordinatesText)
        destinationCoordinatesText = findViewById(R.id.destinationCoordinatesText)
        useCurrentLocationButton = findViewById(R.id.useCurrentLocationButton)
        profileSpinner = findViewById(R.id.profileSpinner)
        startNavigationButton = findViewById(R.id.startNavigationButton)

        // Try to find version TextView - not required
        try {
            versionTextView = findViewById(R.id.versionTextView)
        } catch (e: Exception) {
            Log.d(TAG, "Version TextView not found in layout, will create dynamically if needed")
        }

        // Add a connect button if available - not required
        try {
            connectButton = findViewById(R.id.connectButton)
            connectButton?.setOnClickListener {
                bindToOsmAnd()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Connect button not found in layout")
        }

        // Initialize location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize status
        apiStatusText.text = "Status: Initializing..."

        // Set up navigation profile spinner
        setupProfileSpinner()

        // Set up location search
        setupLocationSearch()

        // Set up buttons
        setupButtons()

        val manualDestinationButton: Button = findViewById(R.id.manualDestinationButton)
        manualDestinationButton.setOnClickListener {
            showManualCoordinateDialog()
        }


        // First, check if OsmAnd is installed
        osmandPackage = getInstalledOsmAndPackage()
        if (osmandPackage == null) {
            apiStatusText.text = "Status: OsmAnd Not Installed"
            Toast.makeText(this, "Please install OsmAnd app", Toast.LENGTH_LONG).show()
            return
        }

        // Try to bind to OsmAnd
        bindToOsmAnd()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromOsmAnd()
    }

    private fun setupProfileSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, NAVIGATION_PROFILES)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        profileSpinner.adapter = adapter

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedProfile = NAVIGATION_PROFILES[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }

    private fun setupLocationSearch() {
        // Setup autocomplete adapters - using a simpler adapter type
        val sourceAdapter = ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, ArrayList())
        val destinationAdapter = ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, ArrayList())

        sourceLocationInput.setAdapter(sourceAdapter)
        destinationLocationInput.setAdapter(destinationAdapter)

        // Add text change listeners
        sourceLocationInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s != null && s.length >= 3) {
                    // Debug log
                    Log.d(TAG, "Searching for source location: ${s.toString()}")
                    searchLocation(s.toString(), true, sourceAdapter)
                }
            }
        })

        destinationLocationInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s != null && s.length >= 3) {
                    // Debug log
                    Log.d(TAG, "Searching for destination location: ${s.toString()}")
                    searchLocation(s.toString(), false, destinationAdapter)
                }
            }
        })

        // Set dropdown to show with less strict conditions
        sourceLocationInput.threshold = 1
        destinationLocationInput.threshold = 1

        // Handle item selection
        sourceLocationInput.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            // Make sure we don't try to access an out-of-bounds item
            if (position < sourceLocationSuggestions.size) {
                val selectedLocation = sourceLocationSuggestions[position]
                sourceLocation = Pair(selectedLocation.lat, selectedLocation.lon)
                sourceCoordinatesText.text = "Coordinates: ${selectedLocation.lat}, ${selectedLocation.lon}"
                updateStartNavigationButton()
            }
        }

        destinationLocationInput.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            // Make sure we don't try to access an out-of-bounds item
            if (position < destinationLocationSuggestions.size) {
                val selectedLocation = destinationLocationSuggestions[position]
                destinationLocation = Pair(selectedLocation.lat, selectedLocation.lon)
                destinationCoordinatesText.text = "Coordinates: ${selectedLocation.lat}, ${selectedLocation.lon}"
                updateStartNavigationButton()
            }
        }
    }

    private fun setupButtons() {
        // Use current location button
        useCurrentLocationButton.setOnClickListener {
            if (checkLocationPermission()) {
                getCurrentLocation()
            } else {
                requestLocationPermission()
            }
        }

        // Start navigation button
        startNavigationButton.setOnClickListener {
            if (sourceLocation != null && destinationLocation != null && connected) {
                startNavigation()
            } else {
                Toast.makeText(this, "Please set source and destination locations", LENGTH_SHORT).show()
            }
        }
    }

    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation()
            } else {
                Toast.makeText(this, "Location permission denied", LENGTH_SHORT).show()
            }
        }
    }

    private fun getCurrentLocation() {
        if (checkLocationPermission()) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        sourceLocation = Pair(location.latitude, location.longitude)
                        sourceCoordinatesText.text = "Coordinates: ${location.latitude}, ${location.longitude}"

                        // Get address from coordinates for display
                        reverseGeocode(location.latitude, location.longitude) { address ->
                            sourceLocationInput.setText(address)
                            updateStartNavigationButton()
                        }
                    } else {
                        Toast.makeText(this, "Unable to get current location", LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Location error: ${e.message}", LENGTH_SHORT).show()
                }
        }
    }

    private fun searchLocation(query: String, isSource: Boolean, adapter: ArrayAdapter<String>) {
        thread {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")

                // Add location bias - use current location if available, or a default location for Pakistan
                val viewboxParam = if (sourceLocation != null) {
                    val lat = sourceLocation!!.first
                    val lon = sourceLocation!!.second
                    // Create a viewbox around the current location (roughly 20km square)
                    val delta = 0.2 // Approximately 20km depending on latitude
                    "&viewbox=${lon-delta},${lat-delta},${lon+delta},${lat+delta}&bounded=1"
                } else {
                    // Default viewbox centered on Pakistan (approximate center coordinates)
                    // These coordinates cover most of Pakistan
                    "&viewbox=60.872,23.6345,77.8046,37.0841&bounded=1"
                }

                // Add country code for Pakistan
                val countryParam = "&countrycodes=pk"

                // Add language preference for Urdu and English
                val languageParam = "&accept-language=ur,en"

                val urlString = "$NOMINATIM_API_URL?q=$encodedQuery$viewboxParam$countryParam$languageParam&format=json&limit=5"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "YoloV8TFLite Navigation App")
                connection.connectTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    // Parse JSON response
                    val jsonArray = JSONArray(response.toString())
                    val suggestions = ArrayList<String>()

                    // Clear appropriate list
                    if (isSource) {
                        sourceLocationSuggestions.clear()
                    } else {
                        destinationLocationSuggestions.clear()
                    }

                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val name = item.getString("display_name")
                        val lat = item.getDouble("lat")
                        val lon = item.getDouble("lon")

                        suggestions.add(name)

                        // Add to appropriate list
                        if (isSource) {
                            sourceLocationSuggestions.add(LocationSuggestion(name, lat, lon))
                        } else {
                            destinationLocationSuggestions.add(LocationSuggestion(name, lat, lon))
                        }
                    }

                    // Debug log
                    Log.d(TAG, "Found ${suggestions.size} suggestions for ${if (isSource) "source" else "destination"}")

                    // Update UI on main thread
                    runOnUiThread {
                        adapter.clear()
                        adapter.addAll(suggestions)
                        adapter.notifyDataSetChanged()

                        // Force dropdown to show
                        if (isSource) {
                            if (suggestions.isNotEmpty()) {
                                sourceLocationInput.showDropDown()
                            }
                        } else {
                            if (suggestions.isNotEmpty()) {
                                destinationLocationInput.showDropDown()
                            }
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error searching for location: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, "Location search error: ${e.message}", LENGTH_SHORT).show()
                }
            }
        }
    }
    // Add this to your setupButtons() function


    private fun showManualCoordinateDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Enter Coordinates")

        // Set up the input layout
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(20, 10, 20, 10)

        val latitudeLabel = TextView(this)
        latitudeLabel.text = "Latitude:"
        layout.addView(latitudeLabel)

        val latitudeInput = EditText(this)
        latitudeInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        layout.addView(latitudeInput)

        val longitudeLabel = TextView(this)
        longitudeLabel.text = "Longitude:"
        longitudeLabel.setPadding(0, 15, 0, 0)
        layout.addView(longitudeLabel)

        val longitudeInput = EditText(this)
        longitudeInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        layout.addView(longitudeInput)

        val nameLabel = TextView(this)
        nameLabel.text = "Location Name (optional):"
        nameLabel.setPadding(0, 15, 0, 0)
        layout.addView(nameLabel)

        val nameInput = EditText(this)
        layout.addView(nameInput)

        builder.setView(layout)

        builder.setPositiveButton("Set") { dialog, which ->
            try {
                val lat = latitudeInput.text.toString().toDouble()
                val lon = longitudeInput.text.toString().toDouble()
                val name = if (nameInput.text.isNotEmpty()) nameInput.text.toString() else "Custom Location"

                // Set the destination
                destinationLocation = Pair(lat, lon)
                destinationLocationInput.setText(name)
                destinationCoordinatesText.text = "Coordinates: $lat, $lon"
                updateStartNavigationButton()

                Toast.makeText(this, "Destination set manually", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Invalid coordinates", Toast.LENGTH_SHORT).show()
            }
        }

        builder.setNegativeButton("Cancel") { dialog, which ->
            dialog.cancel()
        }

        builder.show()
    }

    private fun reverseGeocode(lat: Double, lon: Double, callback: (String) -> Unit) {
        thread {
            try {
                val urlString = "$NOMINATIM_API_URL?lat=$lat&lon=$lon&format=json&limit=1"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "YoloV8TFLite Navigation App")

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    // Parse JSON response
                    val jsonArray = JSONArray(response.toString())
                    if (jsonArray.length() > 0) {
                        val item = jsonArray.getJSONObject(0)
                        val address = item.getString("display_name")

                        // Call callback on main thread
                        runOnUiThread {
                            callback(address)
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error reverse geocoding: ${e.message}")
                runOnUiThread {
                    callback("Current Location")
                }
            }
        }
    }

    private fun updateStartNavigationButton() {
        startNavigationButton.isEnabled = sourceLocation != null && destinationLocation != null && connected
    }

    private fun startNavigation() {
        val source = sourceLocation
        val destination = destinationLocation

        if (source == null || destination == null) {
            Toast.makeText(this, "Source and destination locations must be set", LENGTH_SHORT).show()
            return
        }

        navigateToLocation(
            source.first, source.second,
            destination.first, destination.second,
            sourceLocationInput.text.toString(),
            destinationLocationInput.text.toString()
        )
    }

    // Check which OsmAnd package is installed (following the demo app approach)
    private fun getInstalledOsmAndPackage(): String? {
        val pm = packageManager
        for (pkg in OSMAND_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                Log.d(TAG, "Found installed OsmAnd package: $pkg")
                return pkg
            } catch (e: PackageManager.NameNotFoundException) {
                // Package not found, try next
            }
        }
        return null
    }

    // Binding to OsmAnd service
    private fun bindToOsmAnd() {
        if (osmandPackage == null) {
            apiStatusText.text = "Status: OsmAnd Not Installed"
            return
        }

        apiStatusText.text = "Status: Binding to $osmandPackage..."

        val intent = Intent("net.osmand.aidl.OsmandAidlServiceV2")
        intent.`package` = osmandPackage  // Use OsmAnd package, not your package

        try {
            val success = bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
            if (!success) {
                Log.e(TAG, "Failed to bind to OsmAnd service")
                apiStatusText.text = "Status: Bind Failed - Is OsmAnd running?"
                startOsmAndAndBindAgain()
            } else {
                Log.d(TAG, "Bind request sent successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error binding to OsmAnd service", e)
            apiStatusText.text = "Status: Bind Error - ${e.message}"
        }
    }

    private fun startOsmAndAndBindAgain() {
        osmandPackage?.let { pkg ->
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    Toast.makeText(this, "Starting OsmAnd...", Toast.LENGTH_SHORT).show()
                    startActivity(launchIntent)

                    // Wait 3 seconds before trying to bind again
                    Handler(Looper.getMainLooper()).postDelayed({
                        bindToOsmAnd()
                    }, 3000)
                } else {
                    Log.e(TAG, "Error starting OsmAnd")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting OsmAnd", e)
            }
        }
    }

    private fun disconnectFromOsmAnd() {
        if (connected) {
            try {
                unbindService(mConnection)
                connected = false
                mIOsmAndAidlInterface = null
                Log.d(TAG, "Disconnected from OsmAnd service")
            } catch (e: Exception) {
                Log.w(TAG, "Error unbinding from service", e)
            }
        }
    }

    // SERVICE CONNECTION
    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "Service connected")
            mIOsmAndAidlInterface = IOsmAndAidlInterface.Stub.asInterface(service)
            connected = true
            apiStatusText.text = "Status: Connected to OsmAnd service"
            updateStartNavigationButton()

            // Now verify the connection by getting version info
            verifyConnectionAndGetVersion()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            connected = false
            mIOsmAndAidlInterface = null
            apiStatusText.text = "Status: Disconnected"
            updateStartNavigationButton()
        }
    }

    // Verify the connection and get version info
    private fun verifyConnectionAndGetVersion() {
        if (!connected || mIOsmAndAidlInterface == null) {
            apiStatusText.text = "Status: Not connected to OsmAnd"
            return
        }

        try {
            // Use the correct method to get app info
            val appInfo = mIOsmAndAidlInterface?.getAppInfo()

            if (appInfo != null) {
                // Fix: use the properties that actually exist in AppInfoParams
                val osmandVersion = appInfo.osmAndVersion ?: "Unknown"
                val apiVersion = appInfo.versionsInfo ?: "Unknown"

                Log.i(TAG, "Connected to OsmAnd version: $osmandVersion")
                Log.i(TAG, "API version: $apiVersion")

                // Display version info
                displayVersion(appInfo)

                // Update status
                apiStatusText.text = "Status: Connected to OsmAnd v$osmandVersion"

                // Connection verified successfully
                Toast.makeText(this, "Connected to OsmAnd v$osmandVersion", Toast.LENGTH_SHORT).show()
            } else {
                apiStatusText.text = "Status: Connection verified but no version info"
                Log.w(TAG, "AppInfo is null")
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException getting app info", e)
            apiStatusText.text = "Status: Connection error - ${e.message}"

            // Try to reconnect
            Handler(Looper.getMainLooper()).postDelayed({
                reconnectToOsmAnd()
            }, 2000)
        }
    }

    private fun reconnectToOsmAnd() {
        disconnectFromOsmAnd()
        bindToOsmAnd()
    }

    // Display version info
    private fun displayVersion(appInfo: AppInfoParams) {
        // Create formatted version information
        val versionText = buildString {
            append("OsmAnd Version: ${appInfo.osmAndVersion ?: "Unknown"}\n")
            append("API Version: ${appInfo.versionsInfo ?: "Unknown"}\n")
            append("Package: $osmandPackage")
        }

        // Find existing TextView or create a new one
        var versionView = versionTextView

        if (versionView == null) {
            // Create TextView dynamically
            versionView = TextView(this).apply {
                id = View.generateViewId()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(16, 16, 16, 16)
                textSize = 16f

                // Try to find a parent layout to add to
                val parent = findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
                if (parent is ViewGroup) {
                    parent.addView(this)
                }
            }
            versionTextView = versionView
        }

        // Set the version text
        versionView.text = versionText
        versionView.visibility = View.VISIBLE
    }

    // Function to navigate to a location using the correct API
// Function to navigate to a location using the correct API
    fun navigateToLocation(
        sourceLat: Double, sourceLon: Double,
        destLat: Double, destLon: Double,
        sourceName: String, destName: String
    ) {
        if (!connected || mIOsmAndAidlInterface == null) {
            Toast.makeText(this, "Not connected to OsmAnd", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Create intent to launch OsmAnd directly if needed
            val uri = "osmand.navigation:q=${destLat},${destLon}&name=$destName&profile=$selectedProfile&start_lat=${sourceLat}&start_lon=${sourceLon}&start_name=$sourceName&force=true"
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = android.net.Uri.parse(uri)
            intent.`package` = osmandPackage

            // First try with correct parameter order
            val searchParams = NavigateSearchParams(
                destName,                  // Target name
                destLat,                   // Target latitude
                destLon,                   // Target longitude
                sourceName,                // Start name
                sourceLat,                 // Start latitude (FIXED - was sourceLon)
                sourceLon,                 // Start longitude (FIXED - was sourceLat)
                selectedProfile,           // Navigation profile (car, bicycle, etc.)
                true,                      // Force
                true                       // Navigate
            )

            // Try the AIDL method first
            val success = mIOsmAndAidlInterface?.navigateSearch(searchParams) ?: false

            if (success) {
                Toast.makeText(this, "Navigation started via AIDL", LENGTH_SHORT).show()
            } else {
                // If AIDL fails, fall back to intent
                try {
                    Log.d(TAG, "AIDL method failed, trying intent: $uri")
                    startActivity(intent)
                    Toast.makeText(this, "Navigation started via Intent", LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start navigation via intent", e)
                    Toast.makeText(this, "Navigation error: ${e.message}", LENGTH_SHORT).show()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting navigation", e)
            Toast.makeText(this, "Navigation error: ${e.message}", LENGTH_SHORT).show()
        }
    }
    // Helper class for location suggestions
    data class LocationSuggestion(
        val displayName: String,
        val lat: Double,
        val lon: Double
    )
}