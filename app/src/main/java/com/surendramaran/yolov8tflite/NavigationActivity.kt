package com.surendramaran.yolov8tflite

// NavigationActivity.kt
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class NavigationActivity : AppCompatActivity(), LocationListener {
    private lateinit var mapView: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var locationManager: LocationManager
    private var sourceMarker: Marker? = null
    private var destMarker: Marker? = null

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        // Configure osmdroid
        Configuration.getInstance().load(applicationContext,
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(applicationContext))
        Configuration.getInstance().userAgentValue = packageName

        // Initialize map
        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(15.0)

        // Set up location overlay
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        locationOverlay.enableMyLocation()
        locationOverlay.enableFollowLocation()
        mapView.overlays.add(locationOverlay)

        // Request permissions and setup location
        requestLocationPermissions()

        // Add "My Location" button programmatically
        addMyLocationButton()

        // Add sample markers
        addSampleMarkers()
    }

    private fun addMyLocationButton() {
        // Find the parent RelativeLayout
        val mapLayout = findViewById<RelativeLayout>(R.id.mapLayout)

        val myLocationButton = ImageButton(this)
        myLocationButton.setImageResource(android.R.drawable.ic_menu_mylocation)

        val params = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        params.addRule(RelativeLayout.ALIGN_PARENT_END)
        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        params.setMargins(0, 0, 30, 30)

        myLocationButton.layoutParams = params
        myLocationButton.setBackgroundResource(android.R.drawable.btn_default)
        myLocationButton.setPadding(10, 10, 10, 10)

        myLocationButton.setOnClickListener {
            centerOnCurrentLocation()
        }

        // Add button to the RelativeLayout
        mapLayout.addView(myLocationButton)
    }

    private fun addSampleMarkers() {
        // Source marker (for example purposes, using London coordinates)
        sourceMarker = Marker(mapView).apply {
            position = GeoPoint(51.5074, -0.1278)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Source"
            icon = ContextCompat.getDrawable(this@NavigationActivity, android.R.drawable.ic_menu_mylocation)
            mapView.overlays.add(this)
        }

        // Destination marker (for example purposes, using Paris coordinates)
        destMarker = Marker(mapView).apply {
            position = GeoPoint(48.8566, 2.3522)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Destination"
            icon = ContextCompat.getDrawable(this@NavigationActivity, android.R.drawable.ic_menu_compass)
            mapView.overlays.add(this)
        }

        mapView.invalidate()
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

    private fun centerOnCurrentLocation() {
        val location = locationOverlay.lastFix
        if (location != null) {
            val geoPoint = GeoPoint(location.latitude, location.longitude)
            mapView.controller.animateTo(geoPoint)
        } else {
            Toast.makeText(this, "Current location not available yet", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onLocationChanged(location: Location) {
        val geoPoint = GeoPoint(location.latitude, location.longitude)
        mapView.controller.animateTo(geoPoint)

        // Update source marker to current location if needed
        if (sourceMarker != null) {
            sourceMarker?.position = geoPoint
            mapView.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
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
        mapView.onPause()
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(this)
        }
    }
}