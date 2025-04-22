package com.surendramaran.yolov8tflite

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.surendramaran.yolov8tflite.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupNavigationCards()
    }

    private fun setupNavigationCards() {
        binding.cardLiveCamera.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        binding.cardVideoSimulation.setOnClickListener {
            startActivity(Intent(this, VideoSimulationActivity::class.java))
        }
    }
}