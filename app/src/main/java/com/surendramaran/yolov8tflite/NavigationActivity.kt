package com.surendramaran.yolov8tflite

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.AutoCompleteTextView

class NavigationActivity : AppCompatActivity(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null

    private lateinit var startLocationEdit: EditText
    private lateinit var endLocationEdit: EditText
    private lateinit var navigateButton: Button
    private lateinit var useCurrentLocationCheckbox: RadioButton
    private lateinit var useCustomLocationCheckbox: RadioButton
    private lateinit var transportModeGroup: RadioGroup
    private lateinit var statusText: TextView


    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    // Free version first, then paid version as fallback
    private val OSMAND_FREE_PACKAGE_NAME = "net.osmand"
    private val OSMAND_PLUS_PACKAGE_NAME = "net.osmand.plus"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        // Initialize UI components
        startLocationEdit = findViewById(R.id.startLocationEdit)
        endLocationEdit = findViewById(R.id.endLocationEdit)
        navigateButton = findViewById(R.id.navigateButton)
        useCurrentLocationCheckbox = findViewById(R.id.useCurrentLocationRadio)
        useCustomLocationCheckbox = findViewById(R.id.useCustomLocationRadio)
        transportModeGroup = findViewById(R.id.transportModeGroup)
        statusText = findViewById(R.id.statusText)

        // Request permissions
        requestLocationPermissions()

        // Set up event listeners
        useCurrentLocationCheckbox.setOnCheckedChangeListener { _, isChecked ->
            startLocationEdit.isEnabled = !isChecked
            if (isChecked && currentLocation != null) {
                startLocationEdit.setText("Current Location")
            }
        }

        useCustomLocationCheckbox.setOnCheckedChangeListener { _, isChecked ->
            startLocationEdit.isEnabled = isChecked
            if (isChecked) {
                startLocationEdit.setText("")
                startLocationEdit.hint = "Enter start location"
            }
        }

        navigateButton.setOnClickListener {
            startNavigation()
        }

        // Check if OsmAnd is installed
        checkOsmAndInstallation()
    }

    private fun checkOsmAndInstallation() {
        try {
            val installedPackage = getInstalledOsmAndPackage()
            if (installedPackage != null) {
                statusText.text = "OsmAnd detected: ${packageManager.getApplicationLabel(packageManager.getApplicationInfo(installedPackage, 0))}"
                findViewById<TextView>(R.id.errorText).visibility = View.GONE
            } else {
                showInstallButton()
            }
        } catch (e: Exception) {
            statusText.text = "Error checking apps: ${e.localizedMessage}"
        }
    }

    private fun showInstallButton() {
        val installButton = Button(this).apply {
            text = "Install OsmAnd"
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$OSMAND_FREE_PACKAGE_NAME")))
                } catch (e: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$OSMAND_FREE_PACKAGE_NAME")))
                }
            }
        }
        findViewById<LinearLayout>(R.id.buttonContainer).addView(installButton)
    }

    /**
     * Gets the installed OsmAnd package name, prioritizing the free version
     * Returns null if neither is installed
     */
    private fun getInstalledOsmAndPackage(): String? {
        val packageManager = packageManager

        // Check free version first
        return if (isPackageInstalled(OSMAND_FREE_PACKAGE_NAME)) {
            OSMAND_FREE_PACKAGE_NAME
        } else if (isPackageInstalled(OSMAND_PLUS_PACKAGE_NAME)) {
            OSMAND_PLUS_PACKAGE_NAME
        } else {
            null
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun startNavigation() {
        val installedPackage = getInstalledOsmAndPackage()

        if (installedPackage == null) {
            Toast.makeText(this, "Please install OsmAnd first", Toast.LENGTH_LONG).show()
            return
        }

        val endLocation = endLocationEdit.text.toString()
        if (endLocation.isEmpty()) {
            Toast.makeText(this, "Please enter destination", Toast.LENGTH_SHORT).show()
            return
        }

        val startPoint: String = if (useCurrentLocationCheckbox.isChecked && currentLocation != null) {
            "${currentLocation!!.latitude},${currentLocation!!.longitude}"
        } else {
            startLocationEdit.text.toString()
        }

        if (startPoint.isEmpty() && !useCurrentLocationCheckbox.isChecked) {
            Toast.makeText(this, "Please enter start location or use current location", Toast.LENGTH_SHORT).show()
            return
        }

        // Get selected transport mode
        val transportMode = when (transportModeGroup.checkedRadioButtonId) {
            R.id.carModeRadio -> "car"
            R.id.bikeModeRadio -> "bicycle"
            R.id.walkModeRadio -> "pedestrian"
            else -> "car" // Default
        }

        // Launch OsmAnd for navigation
        launchOsmAndNavigation(startPoint, endLocation, transportMode, installedPackage)
    }

    private fun launchOsmAndNavigation(startPoint: String, endPoint: String, profile: String, packageName: String) {
        try {
            val uri = buildOsmAndNavigationUri(startPoint, endPoint, profile)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                // Allow any OsmAnd version to handle it
                addCategory(Intent.CATEGORY_DEFAULT)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            // Verify intent can be handled
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "OsmAnd not installed", Toast.LENGTH_SHORT).show()
                checkOsmAndInstallation()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Navigation error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildOsmAndNavigationUri(startPoint: String, endPoint: String, profile: String): Uri {
        // Check if starting point is coordinates or address
        val startIsCoordinates = startPoint.matches(Regex("^[-+]?([1-8]?\\d(\\.\\d+)?|90(\\.0+)?),\\s*[-+]?(180(\\.0+)?|((1[0-7]\\d)|([1-9]?\\d))(\\.\\d+)?)$"))

        val uriBuilder = StringBuilder("osmand.navigation:q=")

        if (!startIsCoordinates) {
            // If start is not coordinates, search for destination by name
            uriBuilder.append(Uri.encode(endPoint))
        } else {
            // If start is coordinates, assume destination is a name, or try to convert to coordinates
            val destCoordinates = tryParseCoordinates(endPoint)
            if (destCoordinates != null) {
                uriBuilder.append("${destCoordinates.first},${destCoordinates.second}")
            } else {
                uriBuilder.append(Uri.encode(endPoint))
            }
        }

        // Add start point if defined
        if (startIsCoordinates) {
            uriBuilder.append("&start=")
            uriBuilder.append(startPoint)
        }

        // Add profile
        uriBuilder.append("&profile=")
        uriBuilder.append(profile)

        // Add force flag to ensure OsmAnd handles the intent properly
        uriBuilder.append("&force=true")

        return Uri.parse(uriBuilder.toString())
    }

    private fun tryParseCoordinates(input: String): Pair<Double, Double>? {
        // Try to parse coordinates like "latitude,longitude"
        try {
            val parts = input.split(",")
            if (parts.size == 2) {
                val lat = parts[0].trim().toDouble()
                val lon = parts[1].trim().toDouble()
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    return Pair(lat, lon)
                }
            }
        } catch (e: Exception) {
            // Not coordinates
        }
        return null
    }

    private fun requestLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            setupLocationManager()
        }
    }

    private fun setupLocationManager() {
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        try {
            // Request location updates
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000, // 5 seconds
                    10f,  // 10 meters
                    this
                )

                // Try to get last known location immediately
                val lastKnownLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (lastKnownLocation != null) {
                    onLocationChanged(lastKnownLocation)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error getting location: ${e.message}", Toast.LENGTH_SHORT).show()
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
                setupLocationManager()
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        currentLocation = location
        if (useCurrentLocationCheckbox.isChecked) {
            startLocationEdit.setText("Current Location")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::locationManager.isInitialized &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000,
                10f,
                this
            )
        }
    }

    override fun onPause() {
        super.onPause()
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(this)
        }
    }
}