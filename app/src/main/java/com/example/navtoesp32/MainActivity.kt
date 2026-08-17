package com.example.navtoesp32

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var statusText: TextView
    private lateinit var destinationInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusText = findViewById(R.id.statusText)
        destinationInput = findViewById(R.id.destinationInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        requestLocationPermissionIfNeeded()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Bridge so this activity's UI reflects what the background service is doing,
        // while the service itself does the actual work independent of screen/activity state.
        NavForegroundService.onPayload = { payload ->
            runOnUiThread {
                statusText.text =
                    "${payload.turn} onto ${payload.roadName} — ${payload.distanceToTurnM}m, ETA ${payload.etaMinutes} min"
            }
        }
        NavForegroundService.onStatus = { status ->
            runOnUiThread { statusText.text = status }
        }

        startButton.setOnClickListener {
            val destinationText = destinationInput.text.toString().trim()
            if (destinationText.isEmpty()) {
                statusText.text = "Please enter a destination"
                return@setOnClickListener
            }
            beginNavigation(destinationText)
        }

        stopButton.setOnClickListener {
            val intent = Intent(this, NavForegroundService::class.java).apply { action = "STOP" }
            startService(intent)
            stopButton.isEnabled = false
            startButton.isEnabled = true
            destinationInput.isEnabled = true
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 100
            )
        }
    }

    private fun beginNavigation(destinationText: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = "Location permission not granted"
            requestLocationPermissionIfNeeded()
            return
        }

        startButton.isEnabled = false
        destinationInput.isEnabled = false
        statusText.text = "Getting current location..."

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc == null) {
                statusText.text = "Couldn't get current location — move outdoors or wait a moment and retry"
                startButton.isEnabled = true
                destinationInput.isEnabled = true
                return@addOnSuccessListener
            }

            val intent = Intent(this, NavForegroundService::class.java).apply {
                putExtra(NavForegroundService.EXTRA_ORIGIN_LAT, loc.latitude)
                putExtra(NavForegroundService.EXTRA_ORIGIN_LON, loc.longitude)
                putExtra(NavForegroundService.EXTRA_DEST_TEXT, destinationText)
                putExtra(NavForegroundService.EXTRA_API_KEY, BuildConfig.ORS_API_KEY)
            }
            startForegroundService(intent)
            stopButton.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Prevent a stale reference if the service outlives this activity instance.
        NavForegroundService.onPayload = null
        NavForegroundService.onStatus = null
    }
}