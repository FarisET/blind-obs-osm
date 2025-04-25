package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request location permissions
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        if (!hasPermissions(permissions)) {
            ActivityCompat.requestPermissions(
                this,
                permissions,
                PERMISSION_REQUEST_CODE
            )
        }

        setupNavigationCards()
    }

    private fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun setupNavigationCards() {
        binding.cardLiveCamera.setOnClickListener {
            startActivity(Intent(this, LiveCameraActivity::class.java))
        }

        binding.cardVideoSimulation.setOnClickListener {
            startActivity(Intent(this, VideoSimulationActivity::class.java))
        }

//        binding.cardNavigation.setOnClickListener {
//            startActivity(Intent(this, NavigationActivity::class.java))
//        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    // Permissions granted
                } else {
                    // Some permissions were denied
                }
            }
        }
    }
}