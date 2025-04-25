package com.surendramaran.yolov8tflite

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.app.AppCompatActivity
import net.osmand.aidlapi.IOsmAndAidlInterface
import net.osmand.aidlapi.info.AppInfoParams
import net.osmand.aidlapi.navigation.NavigateSearchParams


class NavigationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OsmAndAPI"

        // These packages match exactly what's in the API demo app
        private val OSMAND_PACKAGES = listOf(
            "net.osmand",
            "net.osmand.plus",
            "net.osmand.dev"
        )
    }

    // UI Elements
    private lateinit var apiStatusText: TextView
    private var versionTextView: TextView? = null
    private var connectButton: Button? = null

    // OsmAnd Service
    private var mIOsmAndAidlInterface: IOsmAndAidlInterface? = null
    private var osmandPackage: String? = null
    private var connected = false
    private val app: Application? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        // Initialize UI elements
        apiStatusText = findViewById(R.id.apiStatusText)

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

        // Initialize status
        apiStatusText.text = "Status: Initializing..."

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

            // Now verify the connection by getting version info
            verifyConnectionAndGetVersion()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            connected = false
            mIOsmAndAidlInterface = null
            apiStatusText.text = "Status: Disconnected"
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

//     Function to navigate to a location using the correct API
    fun navigateToLocation(latitude: Double, longitude: Double, name: String) {

        if (!connected || mIOsmAndAidlInterface == null) {
            Toast.makeText(this, "Not connected to OsmAnd", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Fix: Use the correct method signature based on OsmAnd API
            // 1. Build the param object
            val searchParams = NavigateSearchParams(
                name,
                latitude,
                longitude,
                "home",
                longitude,
                latitude,
                "car",
                true,
                true
            )

// 2. Call the AIDL method with exactly one argument
            val success = mIOsmAndAidlInterface
                ?.navigateSearch(searchParams)
                ?: false

            if (success) {
                Toast.makeText(this, "Navigation started", LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to start navigation", LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting navigation", e)
            Toast.makeText(this, "Navigation error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}