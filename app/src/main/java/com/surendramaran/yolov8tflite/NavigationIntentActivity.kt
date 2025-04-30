package com.surendramaran.yolov8tflite // Adjust to your package name

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
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
import com.surendramaran.yolov8tflite.OsmAndHelper // Import the helper class

// Implement the required listener interface
class NavigationIntentActivity : AppCompatActivity(), OsmAndHelper.OnOsmandMissingListener {

    // --- Data Class for Destination ---
    data class FixedDestination(val name: String, val address: String, val lat: Double, val lon: Double)

    companion object {
        private const val TAG = "OsmAndIntentNav"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1002
        // Define the request code needed by OsmAndHelper
        private const val REQUEST_OSMAND_API = 1003 // Or any unique integer code
    }

    // --- UI Elements ---
    private lateinit var statusText: TextView
    private lateinit var sourceCoordinatesText: TextView
    private lateinit var destinationSpinner: Spinner
    private lateinit var selectedDestinationText: TextView
    private lateinit var startNavigationButton: Button

    // --- Location ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var sourceLocation: Location? = null

    // --- OsmAnd Helper ---
    private lateinit var osmandHelper: OsmAndHelper
    private var osmandPackage: String? = null // Keep track if OsmAnd is found

    // --- Destinations ---
    private val fixedDestinations = listOf(
        FixedDestination("Mian Abdullah Library", "Near Adamjee Academic Block", 24.94086575, 67.1150593333021),
        FixedDestination("IBA Soccer Field", "Near NBP Building", 24.94172, 67.11383),
        FixedDestination("Mubarak Masjid","Q2WV+66J, DHA Phase 5", 24.79540, 67.04332)
    )
    private var selectedDestination: FixedDestination? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation_intent)
        Log.d(TAG, "onCreate started")

        // --- Initialization ---
        statusText = findViewById(R.id.statusText)
        sourceCoordinatesText = findViewById(R.id.sourceCoordinatesText)
        destinationSpinner = findViewById(R.id.destinationSpinner)
        selectedDestinationText = findViewById(R.id.selectedDestinationText)
        startNavigationButton = findViewById(R.id.startNavigationButton)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize the helper using the correct constructor
        // Pass 'this' as the Activity, the request code, and 'this' as the listener
        osmandHelper = OsmAndHelper(this, REQUEST_OSMAND_API, this)

        // OsmAndHelper now calls searchOsmAnd() internally.
        // We rely on the osmandMissing callback if it's not found.
        // We can still try to get the package name immediately if needed,
        // but the primary check happens via the callback.
        osmandPackage = "net.osmand"; // Get initial value (might be null)
        if (osmandPackage != null) {
            statusText.text = "Status: Found OsmAnd: $osmandPackage"
        } else {
            statusText.text = "Status: Searching for OsmAnd..."
            // The osmandMissing callback will update if it's truly not found after search
        }


        // --- Setup UI ---
        setupDestinationSpinner()
        setupStartNavigationButton()

        // --- Permissions & Location ---
        if (checkLocationPermission()) {
            getCurrentLocation()
        } else {
            requestLocationPermission()
        }

        updateStartNavigationButton()
        Log.d(TAG, "onCreate finished")
    }

    // --- Implement the OnOsmandMissingListener method ---
    override fun osmandMissing() {
        Log.e(TAG, "OsmAndHelper reported OsmAnd is missing!")
        // This callback is triggered by OsmAndHelper if it cannot find
        // a suitable OsmAnd package after checking.
        osmandPackage = null // Ensure our tracked variable is null
        runOnUiThread { // Ensure UI updates on the main thread
            statusText.text = "Status: OsmAnd Not Installed/Found"
            Toast.makeText(this, "OsmAnd app not found by helper.", Toast.LENGTH_LONG).show()
            updateStartNavigationButton() // Disable button if OsmAnd is missing
            // Optionally, you could prompt the user to install OsmAnd here
            // osmandHelper.requestOsmandInstall(this); // Example if you implement this in helper
        }
    }

    // --- Permission Handling ---
    // ... (keep existing permission code: checkLocationPermission, requestLocationPermission, onRequestPermissionsResult) ...
    private fun checkLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        Log.d(TAG, "Requesting location permission.")
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE
        )
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
                Toast.makeText(this, "Location permission needed for source.", Toast.LENGTH_LONG).show()
                updateStartNavigationButton()
            }
        }
        // Add handling for REQUEST_OSMAND_API if OsmAndHelper uses startActivityForResult
        // in its requestOsmandInstall method (check OsmAndHelper's code for this)
        // else if (requestCode == REQUEST_OSMAND_API) { ... }
    }


    // --- Location Fetching ---
    // ... (keep existing getCurrentLocation code) ...
    private fun getCurrentLocation() {
        Log.d(TAG, "Attempting to get current location...")
        if (!checkLocationPermission()) {
            Log.w(TAG, "getCurrentLocation called without permission.")
            return
        }
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        sourceLocation = location
                        sourceCoordinatesText.text = "Source: Current (${String.format("%.6f", location.latitude)}, ${String.format("%.6f", location.longitude)})"
                        Log.i(TAG, "Current location obtained: Lat: ${location.latitude}, Lon: ${location.longitude}")
                    } else {
                        Log.w(TAG, "FusedLocationProvider returned null location.")
                        sourceLocation = null
                        sourceCoordinatesText.text = "Source: Location N/A (Waiting...)"
                        Toast.makeText(this, "Could not get current location yet.", Toast.LENGTH_SHORT).show()
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
            Log.e(TAG, "SecurityException in getCurrentLocation.", se)
        }
    }

    // --- UI Setup ---
    // ... (keep existing setupDestinationSpinner and setupStartNavigationButton code) ...
    private fun setupDestinationSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fixedDestinations.map { it.name })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        destinationSpinner.adapter = adapter
        destinationSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < fixedDestinations.size) {
                    selectedDestination = fixedDestinations[position]
                    selectedDestinationText.text = "Dest: ${selectedDestination?.name}\n(${String.format("%.6f", selectedDestination?.lat)}, ${String.format("%.6f", selectedDestination?.lon)})"
                    Log.d(TAG, "Selected destination: ${selectedDestination?.name}")
                } else {
                    selectedDestination = null
                    selectedDestinationText.text = "Dest: -"
                    Log.d(TAG, "No destination selected.")
                }
                updateStartNavigationButton()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedDestination = null
                selectedDestinationText.text = "Dest: -"
                updateStartNavigationButton()
            }
        }
        destinationSpinner.setSelection(0)
        if (fixedDestinations.isNotEmpty()) {
            selectedDestination = fixedDestinations[0]
            selectedDestinationText.text = "Dest: ${selectedDestination?.name}\n(${String.format("%.6f", selectedDestination?.lat)}, ${String.format("%.6f", selectedDestination?.lon)})"
        } else {
            selectedDestinationText.text = "Dest: -"
        }
    }

    private fun setupStartNavigationButton() {
        startNavigationButton.setOnClickListener {
            Log.d(TAG, "Start Navigation button clicked")
            startNavigationViaIntent()
        }
    }


    private fun updateStartNavigationButton() {
        // Check osmandPackage from the helper now
        val isOsmAndAvailable = osmandPackage!= null
        val enabled = isOsmAndAvailable && selectedDestination != null && sourceLocation != null
        startNavigationButton.isEnabled = enabled
        Log.v(TAG, "Update Start Button: OsmAnd Found=${isOsmAndAvailable}, DestSelected=${selectedDestination != null}, SourceFound=${sourceLocation != null} -> Enabled=$enabled")

        // Update status text if OsmAnd wasn't found initially but is now
        if (isOsmAndAvailable && statusText.text.contains("Searching")) {
            statusText.text = "Status: Ready (Using OsmAnd: ${osmandPackage})"
        }
    }

    // --- Navigation Logic (Intent Method) ---
    // ... (keep existing startNavigationViaIntent code) ...
    private fun startNavigationViaIntent() {
        val currentSource = sourceLocation
        val currentDestination = selectedDestination

        // Check using the helper's property now
        if (osmandPackage == null) {
            Toast.makeText(this, "Cannot start: OsmAnd not found.", Toast.LENGTH_SHORT).show()
            // Maybe call osmandHelper.requestOsmandInstall(this) here? Check OsmAndHelper implementation.
            return
        }
        if (currentSource == null) {
            Toast.makeText(this, "Cannot start: Source location not available.", Toast.LENGTH_SHORT).show()
            getCurrentLocation() // Try fetching again
            return
        }
        if (currentDestination == null) {
            Toast.makeText(this, "Cannot start: Please select a destination.", Toast.LENGTH_SHORT).show()
            return
        }

        Log.i(TAG, "Requesting navigation via Intent:")
        Log.i(TAG, "  Source: Current Location (${currentSource.latitude}, ${currentSource.longitude})")
        Log.i(TAG, "  Destination: ${currentDestination.name} (${currentDestination.lat}, ${currentDestination.lon})")

        try {
            osmandHelper.navigate(
                "Current Location",
                currentSource.latitude,
                currentSource.longitude,
                currentDestination.name,
                currentDestination.lat,
                currentDestination.lon,
                "pedestrian", // Adjust profile as needed
                true,
                true
            )
            Toast.makeText(this, "Opening OsmAnd... Please start navigation there.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error launching OsmAnd via helper", e)
            Toast.makeText(this, "Error trying to open OsmAnd.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }
}