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
//            "car",
//            "bicycle",
            "pedestrian",
//            "public_transport",
//            "boat"
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

    private lateinit var destinationSpinner: Spinner // Changed from AutoCompleteTextView
//    private lateinit var sourceCoordinatesText: TextView
//    private lateinit var destinationCoordinatesText: TextView


    data class FixedDestination(
        val name: String,
        val address: String,
        val lat: Double,
        val lon: Double
    )

    private val fixedDestinations = listOf(
//        FixedDestination(
//            "IBA Alumni Students' Center",
//            "IBA Alumni Students' Center, KU Circular Road, Gulshan-e-Kaneez Fatima Society, Gulshan-e-Iqbal Town, Gulshan District, Karachi Division, 75300, Pakistan",
//            24.94057925,
//            67.11293312086875
//        ),
        FixedDestination(
            "Mian Abdullah Library",
            "Mian Abdullah Library, KU Circular Road, Gulshan-e-Kaneez Fatima Society, Gulshan-e-Iqbal Town, Gulshan District, Karachi Division, 75300, Pakistan",
            24.94086575,
            67.1150593333021
        ),
        FixedDestination(
            "IBA Soccer Field",
            "IBA Soccer Field, KU Circular Road, Gulshan-e-Kaneez Fatima Society, Gulshan-e-Iqbal Town, Gulshan District, Karachi Division, 75300, Pakistan",
            24.94172,
            67.11383

        ),
        FixedDestination(
            "Mubarak Masjid",
            "Q2WV+66J, DHA Phase 5 Defence V Defence Housing Authority, Karachi, 75500, Pakistan",
            24.79540,
            67.04332

        ),
//        FixedDestination(
//            "Royale Rodale",
//            "TC-V 34th St, Off Khayaban - e - Seher Phase V Defence V Defence Housing Authority, Karachi, 75500, Pakistan",
//            24.79153, 67.04678
//        )

//                FixedDestination(
//                "Tabba building",
//        "Tabba building, KU Circular Road, Gulshan-e-Kaneez Fatima Society, Gulshan-e-Iqbal Town, Gulshan District, Karachi Division, 75300, Pakistan",
//                    24.94172,
//                    67.11383
//
//    )
    )



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        // Initialize UI elements
        apiStatusText = findViewById(R.id.apiStatusText)
        sourceCoordinatesText = findViewById(R.id.sourceCoordinatesText)
        destinationCoordinatesText = findViewById(R.id.destinationCoordinatesText)
        useCurrentLocationButton = findViewById(R.id.useCurrentLocationButton)
        profileSpinner = findViewById(R.id.profileSpinner)
        startNavigationButton = findViewById(R.id.startNavigationButton)
        destinationSpinner = findViewById(R.id.destinationSpinner) // Add this Spinner to your XML
        useCurrentLocationButton = findViewById(R.id.useCurrentLocationButton)

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

        // Set up buttons
        setupButtons()

        // Setup destination spinner
        setupDestinationSpinner()

        // Automatically get current location on launch
        if (checkLocationPermission()) {
            getCurrentLocation()
        } else {
            requestLocationPermission()
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

    private fun setupDestinationSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            fixedDestinations.map { it.name }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        destinationSpinner.adapter = adapter
        destinationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = fixedDestinations[position]
                destinationLocation = Pair(selected.lat, selected.lon)
                destinationCoordinatesText.text = "Destination: ${selected.name}\n(${selected.lat}, ${selected.lon})"
                updateStartNavigationButton()
                Log.d(TAG, "Selected destination: ${selected.name} (${selected.lat}, ${selected.lon})")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                destinationLocation = null
                updateStartNavigationButton()
            }
        }
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


    // Modified getCurrentLocation - auto-sets source
    private fun getCurrentLocation() {
        if (checkLocationPermission()) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    location?.let {
                        sourceLocation = Pair(it.latitude, it.longitude)
                        sourceCoordinatesText.text = "Current Location: ${it.latitude}, ${it.longitude}"
                        updateStartNavigationButton()
                        Log.d(TAG, "Updated current location: ${it.latitude}, ${it.longitude}")
                    } ?: run {
                        Toast.makeText(this, "Couldn't get current location", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Location error: ${e.message}", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Location not set", LENGTH_SHORT).show()
            return
        }

        // Debug logs
        Log.d(TAG, "Source Location: ${source.first}, ${source.second}")
        Log.d(TAG, "Destination Location: ${destination.first}, ${destination.second}")

        // Get the selected destination name from spinner
        val selectedIndex = destinationSpinner.selectedItemPosition
        val destName = if (selectedIndex >= 0) {
            fixedDestinations[selectedIndex].name
        } else {
            "Selected Destination"
        }

        navigateToLocation(
            source.first,      // Current location lat
            source.second,     // Current location lon
            destination.first, // Selected destination lat
            destination.second,// Selected destination lon
            "Current Location", // Source name
            destName           // Destination name
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


    fun navigateToLocation(
        sourceLat: Double,
        sourceLon: Double,
        destLat: Double,
        destLon: Double,
        sourceName: String,
        destName: String
    ) {
        if (!connected || mIOsmAndAidlInterface == null) {
            Toast.makeText(this, "Not connected to OsmAnd", LENGTH_SHORT).show()
            return
        }

        // Log the inputs to *this* function to be absolutely sure
        Log.d(TAG, "navigateToLocation called with: source=($sourceLat, $sourceLon) '$sourceName', dest=($destLat, $destLon) '$destName'")

        try {
//            val searchParams = NavigateSearchParams(
//                destName,        // Target name
//                destLat,         // Target latitude
//                destLon,        // Target longitude
//                sourceName,     // Start name
//                sourceLat,      // Start latitude
//                sourceLon,     // Start longitude
//                selectedProfile, // Navigation profile
//                true,          // Force
//                true          // Navigate
//            )
            // Despite logs showing correct source/destination variables before this call,
            // OsmAnd seems to be interpreting the start/target parameters in reverse.
            // We explicitly swap them here when creating NavigateSearchParams to correct this.
            // We pass SOURCE data to TARGET slots and DESTINATION data to START slots.
            val searchParams = NavigateSearchParams(
                // Parameters are: targetName, targetLat, targetLon, startName, startLat, startLon, ...
                sourceName,     // Use the INTENDED source name as the TARGET name
                sourceLat,      // Use the INTENDED source latitude as the TARGET latitude
                sourceLon,     // Use the INTENDED source longitude as the TARGET longitude
                destName,       // Use the INTENDED destination name as the START name
                destLat,        // Use the INTENDED destination latitude as the START latitude
                destLon,       // Use the INTENDED destination longitude as the START longitude
                selectedProfile, // Navigation profile remains the same
                true,          // Force
                true           // Navigate
            )
            val success = mIOsmAndAidlInterface?.navigateSearch(searchParams) ?: false

            if (success) {
                Toast.makeText(this, "Navigation started", LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to start navigation", LENGTH_SHORT).show()
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