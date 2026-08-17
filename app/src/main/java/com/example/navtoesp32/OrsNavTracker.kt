package com.example.navtoesp32

/**
 * ORS routing + on-device step tracking sketch.
 *
 * Flow:
 *  1. RouteRepository.fetchRoute() -> one ORS API call, parses steps + geometry.
 *  2. RouteTracker.onLocationUpdate() -> called on every GPS fix (no network call).
 *     Figures out current step, distance-to-turn, ETA, and off-route status.
 *  3. NavPayload is what you serialize to JSON and push to the ESP32.
 *
 * Dependencies assumed: Retrofit + Moshi/Gson, kotlinx-coroutines.
 * Add your ORS API key as a build config field or resource, don't hardcode in real code.
 */

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Header
import kotlin.math.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// ---------- 1. ORS API contract ----------

interface OrsApi {
    // ORS GeoJSON directions endpoint
    @GET("v2/directions/driving-car")
    suspend fun getRoute(
        @Query("api_key") apiKey: String,
        @Query("start") start: String,   // "lon,lat"
        @Query("end") end: String,       // "lon,lat"
        @Query("instructions") instructions: Boolean = true
    ): OrsResponse

    @GET("geocode/search")
    suspend fun geocode(
        @Header("Authorization") apiKey: String,
        @Query("text") text: String,
        @Query("size") size: Int = 1
    ): GeocodeResponse
}

// --- Geocoding (address text -> lat/lon) ---

data class GeocodeResponse(val features: List<GeocodeFeature>)
data class GeocodeFeature(val geometry: GeocodeGeometry)
data class GeocodeGeometry(val coordinates: List<Double>) // [lon, lat]

// Trimmed response shape — ORS returns more fields, we only model what we use.
data class OrsResponse(val features: List<OrsFeature>)
data class OrsFeature(val geometry: OrsGeometry, val properties: OrsProperties)
data class OrsGeometry(val coordinates: List<List<Double>>) // [ [lon,lat], ... ] full route polyline
data class OrsProperties(val segments: List<OrsSegment>)
data class OrsSegment(val steps: List<OrsStep>, val duration: Double, val distance: Double)
data class OrsStep(
    val instruction: String,
    val name: String,          // road name, "-" if unnamed
    val distance: Double,       // meters, length of this step
    val duration: Double,       // seconds
    val type: Int,              // maneuver code (0=left,1=right,etc — ORS docs have the full table)
    val way_points: List<Int>   // [startIdx, endIdx] into the geometry.coordinates array
)

fun buildOrsApi(): OrsApi {
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    return Retrofit.Builder()
        .baseUrl("https://api.openrouteservice.org/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OrsApi::class.java)
}

// ---------- 2. Domain model used internally ----------

data class LatLon(val lat: Double, val lon: Double)

data class Step(
    val instruction: String,
    val roadName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverType: Int,
    val polyline: List<LatLon>   // just this step's slice of the route geometry
)

// What actually gets sent to the ESP32
data class NavPayload(
    val turn: String,           // short code, e.g. "L", "R", "STRAIGHT"
    val roadName: String,
    val distanceToTurnM: Int,
    val etaMinutes: Int,
    val offRoute: Boolean
)

// ---------- 3. Fetch + parse route once per trip ----------

class RouteRepository(private val api: OrsApi, private val apiKey: String) {

    suspend fun fetchRoute(origin: LatLon, destination: LatLon): List<Step> {
        val resp = api.getRoute(
            apiKey = apiKey,
            start = "${origin.lon},${origin.lat}",
            end = "${destination.lon},${destination.lat}"
        )
        val feature = resp.features.first()
        val coords = feature.geometry.coordinates.map { LatLon(lat = it[1], lon = it[0]) }
        val segment = feature.properties.segments.first()

        return segment.steps.map { s ->
            val (from, to) = s.way_points
            Step(
                instruction = s.instruction,
                roadName = s.name.ifBlank { "Unnamed road" },
                distanceMeters = s.distance,
                durationSeconds = s.duration,
                maneuverType = s.type,
                polyline = coords.subList(from, (to + 1).coerceAtMost(coords.size))
            )
        }
    }

    suspend fun geocodeAddress(address: String): LatLon {
        val resp = api.geocode(apiKey = apiKey, text = address)
        val coords = resp.features.firstOrNull()?.geometry?.coordinates
            ?: throw IllegalStateException("No results found for \"$address\"")
        return LatLon(lat = coords[1], lon = coords[0])
    }
}

// ---------- 4. Local step tracking (runs on every GPS fix, no network) ----------

class RouteTracker(private val steps: List<Step>) {

    private var currentStepIndex = 0
    private val offRouteThresholdMeters = 35.0

    /**
     * Call this from your location callback (e.g. every 1-2s).
     * Returns the payload to send to the ESP32, or null if nothing changed
     * enough to warrant re-sending (avoid spamming BT/WiFi).
     */
    fun onLocationUpdate(current: LatLon): NavPayload? {
        if (currentStepIndex >= steps.size) return null

        advanceStepIfNeeded(current)

        val step = steps[currentStepIndex]
        val distToStepEnd = distanceToPoint(current, step.polyline.last())
        val distFromRoute = minDistanceToPolyline(current, step.polyline)
        val isOffRoute = distFromRoute > offRouteThresholdMeters

        val etaSeconds = remainingDuration(current)
        val etaMinutes = ceil(etaSeconds / 60.0).toInt()

        return NavPayload(
            turn = maneuverToCode(step.maneuverType),
            roadName = step.roadName,
            distanceToTurnM = distToStepEnd.roundToInt(),
            etaMinutes = etaMinutes,
            offRoute = isOffRoute
        )
        // Caller decides: if isOffRoute stays true for N consecutive updates,
        // trigger RouteRepository.fetchRoute() again from current position.
    }

    private fun advanceStepIfNeeded(current: LatLon) {
        // If we're closer to the *next* step's start than to anything in the
        // current step, and within a tight radius of the current step's end,
        // move on. Simple heuristic — good enough for turn-by-turn granularity.
        val step = steps[currentStepIndex]
        val distToEnd = distanceToPoint(current, step.polyline.last())
        if (distToEnd < 15.0 && currentStepIndex < steps.size - 1) {
            currentStepIndex++
        }
    }

    private fun remainingDuration(current: LatLon): Double {
        val step = steps[currentStepIndex]
        val stepFractionDone = 1.0 - (distanceToPoint(current, step.polyline.last()) / max(step.distanceMeters, 1.0))
        val currentStepRemaining = step.durationSeconds * (1.0 - stepFractionDone.coerceIn(0.0, 1.0))
        val futureStepsDuration = steps.drop(currentStepIndex + 1).sumOf { it.durationSeconds }
        return currentStepRemaining + futureStepsDuration
    }

    private fun maneuverToCode(type: Int): String = when (type) {
        0 -> "L"       // left
        1 -> "R"       // right
        2 -> "SHARP_L"
        3 -> "SHARP_R"
        4 -> "SLIGHT_L"
        5 -> "SLIGHT_R"
        6 -> "STRAIGHT"
        7 -> "ROUNDABOUT"
        10 -> "ARRIVE"
        11 -> "DEPART"
        else -> "STRAIGHT"
    }

    // ---- geometry helpers ----

    private fun distanceToPoint(a: LatLon, b: LatLon): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(h))
    }

    private fun minDistanceToPolyline(p: LatLon, line: List<LatLon>): Double {
        if (line.size < 2) return line.firstOrNull()?.let { distanceToPoint(p, it) } ?: Double.MAX_VALUE
        var minDist = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val d = distanceToSegment(p, line[i], line[i + 1])
            if (d < minDist) minDist = d
        }
        return minDist
    }

    // Approximate point-to-segment distance using equirectangular projection
    // (fine for short segments at typical road-navigation scale).
    private fun distanceToSegment(p: LatLon, a: LatLon, b: LatLon): Double {
        val latRef = Math.toRadians(a.lat)
        fun toXY(pt: LatLon): Pair<Double, Double> {
            val x = Math.toRadians(pt.lon) * cos(latRef) * 6371000.0
            val y = Math.toRadians(pt.lat) * 6371000.0
            return x to y
        }
        val (px, py) = toXY(p)
        val (ax, ay) = toXY(a)
        val (bx, by) = toXY(b)
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy
        return sqrt((px - projX).pow(2) + (py - projY).pow(2))
    }
}

// ---------- 5. Wiring it together (pseudo usage) ----------

/*
val api = buildOrsApi()
val repo = RouteRepository(api, apiKey = "YOUR_ORS_KEY")

// once, when trip starts:
val steps = repo.fetchRoute(origin, destination)
val tracker = RouteTracker(steps)

// in your FusedLocationProviderClient callback:
fun onLocation(loc: Location) {
    val payload = tracker.onLocationUpdate(LatLon(loc.latitude, loc.longitude)) ?: return
    val json = Gson().toJson(payload)
    sendToEsp32(json) // your BT/BLE/WiFi send function
}
*/