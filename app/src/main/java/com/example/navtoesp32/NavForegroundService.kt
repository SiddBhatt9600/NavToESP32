package com.example.navtoesp32

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NavForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "nav_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_ORIGIN_LAT = "origin_lat"
        const val EXTRA_ORIGIN_LON = "origin_lon"
        const val EXTRA_DEST_TEXT = "dest_text"
        const val EXTRA_API_KEY = "api_key"

        // Simple in-process callback so MainActivity can still update its UI
        // while the service does the real work. Cleared in onDestroy.
        var onPayload: ((NavPayload) -> Unit)? = null
        var onStatus: ((String) -> Unit)? = null
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var tracker: RouteTracker
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val originLat = intent?.getDoubleExtra(EXTRA_ORIGIN_LAT, 0.0) ?: return START_NOT_STICKY
        val originLon = intent.getDoubleExtra(EXTRA_ORIGIN_LON, 0.0)
        val destText = intent.getStringExtra(EXTRA_DEST_TEXT) ?: return START_NOT_STICKY
        val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("Starting navigation..."))

        val repo = RouteRepository(buildOrsApi(), apiKey)
        serviceScope.launch {
            try {
                onStatus?.invoke("Looking up \"$destText\"...")
                val destination = repo.geocodeAddress(destText)

                onStatus?.invoke("Fetching route...")
                val steps = repo.fetchRoute(LatLon(originLat, originLon), destination)
                Log.d("NAV", "route fetched: ${steps.size} steps")

                tracker = RouteTracker(steps)
                onStatus?.invoke("Navigating to $destText")
                startLocationUpdates()

            } catch (e: Exception) {
                Log.e("NAV", "Failed to start navigation: ${e.message}", e)
                onStatus?.invoke("Error: ${e.message}")
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("NAV", "Location permission not granted")
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val payload = tracker.onLocationUpdate(LatLon(loc.latitude, loc.longitude)) ?: return
                Log.d("NAV", payload.toString())
                onPayload?.invoke(payload)
                updateNotification("${payload.turn} onto ${payload.roadName} — ${payload.distanceToTurnM}m")
                // TODO: send payload to ESP32 — next step
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, NavForegroundService::class.java).apply { action = "STOP" }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Navigating")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        onStatus?.invoke("Navigation stopped")
        onPayload = null
        onStatus = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}