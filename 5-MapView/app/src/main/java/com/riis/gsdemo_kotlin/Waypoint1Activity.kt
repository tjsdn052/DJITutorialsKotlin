package com.riis.gsdemo_kotlin

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.IconFactory
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.maps.SupportMapFragment
import dji.common.error.DJIError
import dji.common.mission.waypoint.*
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import java.util.concurrent.ConcurrentHashMap

import dji.common.model.LocationCoordinate2D
import dji.common.flightcontroller.simulator.InitializationData // Not used in current context
// import dji.sdk.sdkmanager.DJISDKManager // Not used in current context, commented out to avoid warning if truly unused

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request


class Waypoint1Activity : AppCompatActivity(), MapboxMap.OnMapClickListener, OnMapReadyCallback, View.OnClickListener {

    private lateinit var locate: Button
    private lateinit var add: Button
    private lateinit var clear: Button
    private lateinit var config: Button
    private lateinit var upload: Button
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var sendLocationButton: Button
    private lateinit var getWaypointsBtn: Button

    // New UI elements for manual waypoint input
    private lateinit var inputLat: EditText
    private lateinit var inputLng: EditText
    private lateinit var inputAlt: EditText
    private lateinit var addWaypointManual: Button

    // TextViews for drone location
    private lateinit var droneLatTextView: TextView
    private lateinit var droneLngTextView: TextView
    private lateinit var droneAltTextView: TextView

    companion object {
        const val TAG = "GSDemoActivity"
        private var waypointMissionBuilder: WaypointMission.Builder? = null

        fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean {
            return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
        }
    }

    private var isAdd = false
    private var droneLocationLat: Double = 15.0
    private var droneLocationLng: Double = 15.0
    private var droneLocationAlt: Float = 0.0f
    private var droneMarker: Marker? = null
    private val markers: MutableMap<Int, Marker> = ConcurrentHashMap<Int, Marker>() // Key: waypoint index
    private var mapboxMap: MapboxMap? = null
    private var mavicMiniMissionOperator: MavicMiniMissionOperator? = null

//    private val SIMULATED_DRONE_LAT = 36.762556
//    private val SIMULATED_DRONE_LONG = -127.281789

    private var altitude = 100f // Default altitude for UI-added waypoints
    private var speed = 10f

    private val waypointList = mutableListOf<Waypoint>()
    private var finishedAction = WaypointMissionFinishedAction.NO_ACTION
    private var headingMode = WaypointMissionHeadingMode.AUTO


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this, getString(R.string.mapbox_access_token))
        setContentView(R.layout.activity_waypoint1)

        initUi()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        addListener()
    }

    override fun onMapReady(mapboxMap: MapboxMap) {
        this.mapboxMap = mapboxMap
        mapboxMap.addOnMapClickListener(this)
        mapboxMap.setStyle(Style.MAPBOX_STREETS) { style ->
            Log.d(TAG, "Mapbox style loaded successfully.")
            val fixedLatLng = LatLng(37.5665, 126.9780) // Example: Seoul City Hall
            mapboxMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fixedLatLng, 15.0))
            if (!style.isFullyLoaded) {
                Log.w(TAG, "Style is not fully loaded even if onStyleLoaded was called.")
            }
        }
    }

    override fun onMapClick(point: LatLng): Boolean {
        if (isAdd) {
            // Use the current 'altitude' (default altitude from settings) for the waypoint added by map click
            // The index for the new waypoint will be the current size of the waypointList
            markWaypoint(point, altitude.toDouble(), waypointList.size) // Heading is not specified, will use null default

            val waypoint = Waypoint(point.latitude, point.longitude, altitude)
            // Note: waypoint.heading will be 0 by default.
            // If headingMode is USING_WAYPOINT_HEADING, you might want to set a specific heading here,
            // or rely on the aircraft to maintain current heading or orient to next waypoint based on other modes.

            if (waypointMissionBuilder == null){
                waypointMissionBuilder = WaypointMission.Builder().also { builder ->
                    waypointList.add(waypoint)
                    builder.waypointList(waypointList).waypointCount(waypointList.size)
                }
            } else {
                waypointMissionBuilder?.let { builder ->
                    waypointList.add(waypoint)
                    builder.waypointList(waypointList).waypointCount(waypointList.size)
                }
            }
        } else {
            setResultToToast("Cannot Add Waypoint")
        }
        return true
    }

    // Modified markWaypoint function to accept an optional heading and use index as key
    private fun markWaypoint(point: LatLng, alt: Double, index: Int, heading: Int? = null) {
        val title = String.format(Locale.US, "WP %d: Lat: %.6f, Lng: %.6f", index + 1, point.latitude, point.longitude)
        val snippet: String = if (heading != null) {
            String.format(Locale.US, "Alt: %.2fm, Hdg: %d°", alt, heading)
        } else {
            String.format(Locale.US, "Alt: %.2fm", alt)
        }

        val markerOptions = MarkerOptions()
            .position(point)
            .title(title)
            .snippet(snippet)
        mapboxMap?.let {
            // Remove old marker at this index if it exists, before adding a new one
            markers[index]?.remove()
            val marker = it.addMarker(markerOptions)
            markers[index] = marker // Store marker by its logical index
        }
    }


    override fun onResume() {
        super.onResume()
        initFlightController()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeListener()
    }

    private fun addListener() {
        getWaypointMissionOperator()?.addListener(eventNotificationListener)
    }

    private fun removeListener() {
        getWaypointMissionOperator()?.removeListener()
    }

    private fun initUi() {
        locate = findViewById(R.id.locate)
        add = findViewById(R.id.add)
        clear = findViewById(R.id.clear)
        config = findViewById(R.id.config)
        upload = findViewById(R.id.upload)
        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)
        sendLocationButton = findViewById(R.id.send_location_button)
        getWaypointsBtn = findViewById(R.id.getWaypointsBtn)


        droneLatTextView = findViewById(R.id.droneLatTextView)
        droneLngTextView = findViewById(R.id.droneLngTextView)
        droneAltTextView = findViewById(R.id.droneAltTextView)

        inputLat = findViewById(R.id.inputLat)
        inputLng = findViewById(R.id.inputLng)
        inputAlt = findViewById(R.id.inputAlt)
        addWaypointManual = findViewById(R.id.addWaypointManual)

        locate.setOnClickListener(this)
        add.setOnClickListener(this)
        clear.setOnClickListener(this)
        config.setOnClickListener(this)
        upload.setOnClickListener(this)
        start.setOnClickListener(this)
        stop.setOnClickListener(this)
        sendLocationButton.setOnClickListener(this)
        addWaypointManual.setOnClickListener(this)
        getWaypointsBtn.setOnClickListener(this)
    }


    @SuppressLint("SetTextI18n")
    private fun initFlightController() {
        DJIDemoApplication.getFlightController()?.let { flightController ->
//            val simulateLocation = LocationCoordinate2D(SIMULATED_DRONE_LAT, SIMULATED_DRONE_LONG)
//            flightController.simulator.start(
//                InitializationData.createInstance(simulateLocation, 10, 10)
//            ){ error ->
//                if (error != null) {
//                    Log.e(TAG, "initFlightController: Error starting simulator: ${error.description}")
//                } else {
//                    Log.d(TAG, "initFlightController: Simulator started successfully")
//                }
//            }

            flightController.setStateCallback { flightControllerState ->
                droneLocationLat = flightControllerState.aircraftLocation.latitude
                droneLocationLng = flightControllerState.aircraftLocation.longitude
                droneLocationAlt = flightControllerState.aircraftLocation.altitude

                runOnUiThread {
                    mavicMiniMissionOperator?.droneLocationMutableLiveData?.postValue(flightControllerState.aircraftLocation)
                    updateDroneLocation()
                }
            }
        } ?: run {
            Log.e(TAG, "initFlightController: Flight Controller not available.")
            runOnUiThread {
                droneLatTextView.text = "Latitude: FC N/A"
                droneLngTextView.text = "Longitude: FC N/A"
                droneAltTextView.text = "Altitude: FC N/A"
            }
        }
    }


    @SuppressLint("SetTextI18n")
    private fun updateDroneLocation() {
        runOnUiThread {
            droneLatTextView.text = String.format(Locale.US,"Latitude: %.6f", droneLocationLat)
            droneLngTextView.text = String.format(Locale.US,"Longitude: %.6f", droneLocationLng)
            droneAltTextView.text = String.format(Locale.US,"Altitude: %.2f m", droneLocationAlt)
        }

        if (droneLocationLat.isNaN() || droneLocationLng.isNaN())  { return }

        val pos = LatLng(droneLocationLat, droneLocationLng)
        val icon = IconFactory.getInstance(this@Waypoint1Activity).fromResource(R.drawable.aircraft)
        val markerOptions = MarkerOptions()
            .position(pos)
            .icon(icon)
        runOnUiThread {
            droneMarker?.remove()
            if (checkGpsCoordination(droneLocationLat, droneLocationLng)) {
                droneMarker = mapboxMap?.addMarker(markerOptions)
            }
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.locate -> {
                updateDroneLocation()
                cameraUpdate()
            }
            R.id.add -> {
                enableDisableAdd()
            }
            R.id.clear -> {
                runOnUiThread {
                    mapboxMap?.clear() // Clears all markers, polylines, etc. from the map
                    markers.clear()    // Clear our tracked markers
                }
                waypointList.clear()
                waypointMissionBuilder?.waypointList(waypointList)?.waypointCount(waypointList.size)
                // markers.clear() // Already cleared above if mapboxMap?.clear() removes them. Explicitly clear our map.
                setResultToToast("Waypoints cleared")
            }
            R.id.config -> {
                showSettingsDialog()
            }
            R.id.upload -> {
                uploadWaypointMission()
            }
            R.id.start -> {
                startWaypointMission()
            }
            R.id.stop -> {
                stopWaypointMission()
            }
            R.id.send_location_button -> {
                sendCurrentLocationToApi()
            }
            R.id.addWaypointManual -> {
                addWaypointManually()
            }
            R.id.getWaypointsBtn -> {
                fetchWaypointsFromServer()
            }
            else -> {}
        }
    }

    private fun fetchWaypointsFromServer() {
        val buildingId = "26" // Temporary buildingId, should be dynamic in a real app

        if (buildingId.isBlank()) {
            setResultToToast("Building ID가 필요합니다.")
            return
        }

        setResultToToast("서버에서 웨이포인트 불러오는 중 (Building ID: $buildingId)...")
        val serverUrl = "http://3.37.127.247:8080/waypoints$buildingId" // Note: Added a slash before $buildingId

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url(serverUrl)
                    .build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        Log.d(TAG, "서버 응답: $responseBody")
                        val fetchedWaypoints = mutableListOf<Waypoint>()
                        // Store LatLng, Altitude, and Heading for marker creation
                        val fetchedMarkerData = mutableListOf<Triple<LatLng, Double, Int>>()

                        val jsonArray = JSONArray(responseBody)
                        for (i in 0 until jsonArray.length()) {
                            val wpObject = jsonArray.getJSONObject(i)
                            val lat = wpObject.optDouble("lat")
                            val lng = wpObject.optDouble("lng")
                            val alt = wpObject.optDouble("altitude").toFloat()
                            // ** Get heading from server, default to 0 if not provided **
                            val heading = wpObject.optInt("heading", 0) // DJI SDK heading: -180 to 180

                            if (checkGpsCoordination(lat, lng)) {
                                val waypoint = Waypoint(lat, lng, alt)
                                // ** Set the heading for the Waypoint object **
                                waypoint.heading = heading
                                fetchedWaypoints.add(waypoint)
                                fetchedMarkerData.add(Triple(LatLng(lat, lng), alt.toDouble(), heading))
                            }
                        }

                        withContext(Dispatchers.Main) {
                            mapboxMap?.clear() // Clear existing markers from the map
                            markers.clear()    // Clear our internal marker tracking
                            waypointList.clear() // Clear the old list of waypoints

                            waypointList.addAll(fetchedWaypoints)
                            if (waypointMissionBuilder == null) {
                                waypointMissionBuilder = WaypointMission.Builder()
                            }
                            waypointMissionBuilder?.waypointList(waypointList)?.waypointCount(waypointList.size)

                            // Add new markers to the map
                            fetchedMarkerData.forEachIndexed { index, data ->
                                val latLng = data.first
                                val markerAlt = data.second
                                val markerHeading = data.third
                                markWaypoint(latLng, markerAlt, index, markerHeading) // Pass heading to display in snippet
                            }

                            if (fetchedWaypoints.isNotEmpty()) {
                                cameraUpdateToFirstWaypoint(fetchedWaypoints.first())
                                setResultToToast("서버에서 웨이포인트 로드 완료: ${fetchedWaypoints.size}개")
                                // Re-configure the mission with the new waypoints and current settings
                                // This is important if USING_WAYPOINT_HEADING is active
                                configWayPointMission()
                            } else {
                                setResultToToast("서버에서 유효한 웨이포인트를 받지 못했습니다.")
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            setResultToToast("서버로부터 빈 응답을 받았습니다.")
                        }
                    }
                } else {
                    val errorBody = response.body?.string()
                    withContext(Dispatchers.Main) {
                        setResultToToast("웨이포인트 로드 실패: ${response.code} ${response.message}")
                        Log.e(TAG, "서버 오류: ${response.code} ${response.message} - $errorBody")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "서버에서 웨이포인트 로드 중 오류 발생", e)
                withContext(Dispatchers.Main) {
                    setResultToToast("오류 발생: ${e.message}")
                }
            }
        }
    }

    private fun cameraUpdateToFirstWaypoint(firstWaypoint: Waypoint) {
        if (mapboxMap == null) return
        val pos = LatLng(firstWaypoint.coordinate.latitude, firstWaypoint.coordinate.longitude)
        val zoomLevel = 18.0
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        mapboxMap?.animateCamera(cameraUpdate)
    }


    private fun addWaypointManually() {
        val latStr = inputLat.text.toString()
        val lngStr = inputLng.text.toString()
        val altStr = inputAlt.text.toString()

        if (latStr.isBlank() || lngStr.isBlank() || altStr.isBlank()) {
            setResultToToast("Please enter all waypoint coordinates and altitude.")
            return
        }

        try {
            val latitude = latStr.toDouble()
            val longitude = lngStr.toDouble()
            val altitudeInput = altStr.toFloat() // User input altitude for this specific waypoint

            if (!checkGpsCoordination(latitude, longitude)) {
                setResultToToast("Invalid GPS coordinates. Latitude must be between -90 and 90, Longitude between -180 and 180, and neither can be 0.")
                return
            }

            val newPoint = LatLng(latitude, longitude)
            // Mark waypoint using its future index in waypointList
            markWaypoint(newPoint, altitudeInput.toDouble(), waypointList.size) // No specific heading from manual input for marker

            val waypoint = Waypoint(latitude, longitude, altitudeInput)
            // waypoint.heading will be 0 by default. Mission's headingMode will dictate behavior.

            if (waypointMissionBuilder == null) {
                waypointMissionBuilder = WaypointMission.Builder().also { builder ->
                    waypointList.add(waypoint)
                    builder.waypointList(waypointList).waypointCount(waypointList.size)
                }
            } else {
                waypointMissionBuilder?.let { builder ->
                    waypointList.add(waypoint)
                    builder.waypointList(waypointList).waypointCount(waypointList.size)
                }
            }
            setResultToToast("Waypoint added manually: Lat: $latitude, Lng: $longitude, Alt: $altitudeInput m")
            inputLat.text.clear()
            inputLng.text.clear()
            inputAlt.text.clear()

        } catch (e: NumberFormatException) {
            setResultToToast("Invalid number format for coordinates or altitude.")
            Log.e(TAG, "NumberFormatException: ${e.message}")
        }
    }


    private fun sendCurrentLocationToApi() {
        val currentLat = droneLocationLat
        val currentLng = droneLocationLng
        val currentAlt = droneLocationAlt

        if (!checkGpsCoordination(currentLat, currentLng)) {
            setResultToToast("Invalid GPS coordinates to send.")
            return
        }

        val jsonObject = JSONObject()
        jsonObject.put("latitude", currentLat)
        jsonObject.put("longitude", currentLng)
        jsonObject.put("altitude", currentAlt.toDouble())
        // If you want to send current drone heading, you'd get it from FlightControllerState
        // Example: jsonObject.put("heading", flightController.Compass.getHeading()) // Requires flightController instance

        val jsonData = jsonObject.toString()
        val apiUrl = "http://3.37.127.247:8080/waypoint"

        setResultToToast("Sending location data...")
        Log.d(TAG, "Sending data: $jsonData to $apiUrl")


        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 15000
                connection.readTimeout = 10000

                OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                    writer.write(jsonData)
                    writer.flush()
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "API Response Code: $responseCode")

                val responseStream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream ?: connection.inputStream
                }

                BufferedReader(InputStreamReader(responseStream, "UTF-8")).use { reader ->
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    val responseBody = response.toString()
                    Log.d(TAG, "API Response: $responseBody")
                    withContext(Dispatchers.Main) {
                        if (responseCode in 200..299) {
                            setResultToToast("Location sent successfully! Response: ${responseBody.take(100)}...")
                        } else {
                            setResultToToast("Failed to send location. Code: $responseCode, Error: ${responseBody.take(100)}...")
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending location data", e)
                withContext(Dispatchers.Main) {
                    setResultToToast("Error sending location: ${e.message}")
                }
            }
        }
    }


    private fun startWaypointMission() {
        getWaypointMissionOperator()?.startMission { error ->
            setResultToToast("Mission Start: " + if (error == null) "Successfully" else error.description)
        }
    }

    private fun stopWaypointMission() {
        getWaypointMissionOperator()?.stopMission { error ->
            setResultToToast("Mission Stop: " + if (error == null) "Successfully" else error.description)
        }
    }

    private fun uploadWaypointMission() {
        if (waypointMissionBuilder == null || waypointMissionBuilder!!.waypointList.isEmpty()) {
            setResultToToast("No waypoints to upload.")
            return
        }
        // Ensure the mission is configured before uploading
        configWayPointMission() // This will apply the latest altitude, speed, heading mode, etc.

        getWaypointMissionOperator()?.uploadMission { error ->
            if (error == null) {
                setResultToToast("Mission upload successfully!")
            } else {
                setResultToToast("Mission upload failed, error: " + error.description + " (Error Code: " + (error as? DJIError)?.errorCode + ")")
            }
        }
    }

    private fun showSettingsDialog() {
        val wayPointSettings = layoutInflater.inflate(R.layout.dialog_waypointsetting, null) as LinearLayout

        val wpAltitudeTV = wayPointSettings.findViewById<EditText>(R.id.altitude) // This is for the *default* altitude for new points
        val speedSeekBar = wayPointSettings.findViewById<SeekBar>(R.id.speedSeekBar)
        val speedValueTextView = wayPointSettings.findViewById<TextView>(R.id.speedValueTextView)
        val actionAfterFinishedRG = wayPointSettings.findViewById<RadioGroup>(R.id.actionAfterFinished)
        val headingRG = wayPointSettings.findViewById<RadioGroup>(R.id.heading)

        wpAltitudeTV.setText(altitude.toInt().toString()) // Sets the general altitude

        speedSeekBar.progress = speed.toInt()
        speedValueTextView.text = String.format(Locale.US, "Current Speed: %.1f m/s", speed)


        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                speed = progress.toFloat()
                speedValueTextView.text = String.format(Locale.US, "Current Speed: %.1f m/s", speed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                setResultToToast("Speed set to: %.1f m/s".format(speed))
            }
        })

        when (finishedAction) {
            WaypointMissionFinishedAction.NO_ACTION -> actionAfterFinishedRG.check(R.id.finishNone)
            WaypointMissionFinishedAction.GO_HOME -> actionAfterFinishedRG.check(R.id.finishGoHome)
            WaypointMissionFinishedAction.AUTO_LAND -> actionAfterFinishedRG.check(R.id.finishAutoLanding)
            WaypointMissionFinishedAction.GO_FIRST_WAYPOINT -> actionAfterFinishedRG.check(R.id.finishToFirst)
            else -> {}
        }
        when (headingMode) { // Crucial for using individual waypoint headings
            WaypointMissionHeadingMode.AUTO -> headingRG.check(R.id.headingNext)
            WaypointMissionHeadingMode.USING_INITIAL_DIRECTION -> headingRG.check(R.id.headingInitDirec)
            WaypointMissionHeadingMode.CONTROL_BY_REMOTE_CONTROLLER -> headingRG.check(R.id.headingRC)
            WaypointMissionHeadingMode.USING_WAYPOINT_HEADING -> headingRG.check(R.id.headingWP) // Select this to use individual headings
            else -> {}
        }


        actionAfterFinishedRG.setOnCheckedChangeListener { _, checkedId ->
            finishedAction = when (checkedId) {
                R.id.finishNone -> WaypointMissionFinishedAction.NO_ACTION
                R.id.finishGoHome -> WaypointMissionFinishedAction.GO_HOME
                R.id.finishAutoLanding -> WaypointMissionFinishedAction.AUTO_LAND
                R.id.finishToFirst -> WaypointMissionFinishedAction.GO_FIRST_WAYPOINT
                else -> finishedAction
            }
        }

        headingRG.setOnCheckedChangeListener { _, checkedId ->
            headingMode = when (checkedId) { // Make sure R.id.headingWP sets USING_WAYPOINT_HEADING
                R.id.headingNext -> WaypointMissionHeadingMode.AUTO
                R.id.headingInitDirec -> WaypointMissionHeadingMode.USING_INITIAL_DIRECTION
                R.id.headingRC -> WaypointMissionHeadingMode.CONTROL_BY_REMOTE_CONTROLLER
                R.id.headingWP -> WaypointMissionHeadingMode.USING_WAYPOINT_HEADING
                else -> headingMode
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Waypoint Settings")
            .setView(wayPointSettings)
            .setPositiveButton("Finish") { _, _ ->
                val altitudeString = wpAltitudeTV.text.toString()
                altitude = nullToIntegerDefault(altitudeString).toFloat() // This is the general altitude
                Log.d(TAG, "Altitude: $altitude, Speed: $speed, Finished Action: $finishedAction, Heading Mode: $headingMode")
                // ConfigWayPointMission will apply these settings to the mission object
                configWayPointMission()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
            .create()
            .show()
    }

    private fun configWayPointMission() {
        // If waypointMissionBuilder is null and waypointList is populated (e.g. from server), initialize builder.
        if (waypointMissionBuilder == null && waypointList.isNotEmpty()) {
            waypointMissionBuilder = WaypointMission.Builder().waypointList(waypointList).waypointCount(waypointList.size)
        } else if (waypointMissionBuilder == null) {
            // No waypoints yet, and no builder. Initialize an empty one.
            waypointMissionBuilder = WaypointMission.Builder()
        }


        val builder = waypointMissionBuilder ?: return // Should not be null here with the above check but as a safeguard.

        builder.finishedAction(finishedAction)
            .headingMode(headingMode) // This is key! If USING_WAYPOINT_HEADING, individual waypoint.heading is used.
            .autoFlightSpeed(speed)
            .maxFlightSpeed(speed) // Consider allowing different max speed
            .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
            .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
            .isGimbalPitchRotationEnabled = true

        // The altitudes and headings for waypoints should already be set on the individual Waypoint objects
        // in waypointList, especially if they came from the server or were manually added with specific altitudes.
        // The 'altitude' variable from settings is more of a default for new map-clicked points.

        // Update waypoints in the builder's list if they've changed (e.g., actions)
        // This loop ensures all waypoints in the builder reflect the current state of waypointList
        // and have any necessary default actions.
        val currentWaypointsInBuilder = builder.waypointList
        for (i in currentWaypointsInBuilder.indices) {
            if (i < waypointList.size) {
                val sourceWaypoint = waypointList[i] // The definitive waypoint from our list
                val missionWaypoint = currentWaypointsInBuilder[i]

                // Sync basic properties if they could differ (altitude, coordinates are primary)
                missionWaypoint.coordinate = sourceWaypoint.coordinate
                missionWaypoint.altitude = sourceWaypoint.altitude
                missionWaypoint.heading = sourceWaypoint.heading // Ensure heading is synced

                // Clear and add actions as per your logic
                missionWaypoint.waypointActions.removeAll { it.actionType == WaypointActionType.START_TAKE_PHOTO }
                if (missionWaypoint.waypointActions.none { it.actionType == WaypointActionType.GIMBAL_PITCH }) {
                    missionWaypoint.addAction(WaypointAction(WaypointActionType.GIMBAL_PITCH, -90))
                }
            }
        }


        if (builder.waypointList.isNotEmpty()) {
            setResultToToast("Set Waypoint parameters successfully")
        }

        // No need to re-assign builder to waypointMissionBuilder if it's the same instance.
        // waypointMissionBuilder = builder

        getWaypointMissionOperator()?.let { operator ->
            val missionToLoad = builder.build() // Build the mission from the configured builder
            if (missionToLoad.waypointCount > 0) {
                val error = operator.loadMission(missionToLoad)
                if (error == null) {
                    setResultToToast("loadWaypointMission succeeded")
                } else {
                    setResultToToast("loadWaypointMission failed: ${error.description} (Code: ${(error as? DJIError)?.errorCode})")
                }
            } else {
                setResultToToast("Cannot load empty mission.")
            }
        }
    }

    private fun nullToIntegerDefault(value: String): String {
        val trimmedValue = value.trim()
        return if (isIntValue(trimmedValue)) trimmedValue else "0"
    }

    private fun isIntValue(value: String): Boolean {
        return value.toIntOrNull() != null
    }

    private fun enableDisableAdd() {
        isAdd = !isAdd
        add.text = if (isAdd) "Exit" else "Add"
    }

    private fun cameraUpdate() {
        if (droneLocationLat.isNaN() || droneLocationLng.isNaN() || mapboxMap == null)  { return }
        val pos = LatLng(droneLocationLat, droneLocationLng)
        val zoomLevel = 18.0
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        mapboxMap?.animateCamera(cameraUpdate)
    }

    private fun setResultToToast(string: String) {
        runOnUiThread { Toast.makeText(this, string, Toast.LENGTH_SHORT).show() }
    }

    private val eventNotificationListener: WaypointMissionOperatorListener = object : WaypointMissionOperatorListener {
        override fun onDownloadUpdate(downloadEvent: WaypointMissionDownloadEvent) {
            Log.d(TAG, "WaypointMissionOperator onDownloadUpdate: ${downloadEvent.progress?.toString()}")
        }
        override fun onUploadUpdate(uploadEvent: WaypointMissionUploadEvent) {
            Log.d(TAG, "WaypointMissionOperator onUploadUpdate: ${uploadEvent.progress?.toString()}")
            if (uploadEvent.currentState == WaypointMissionState.READY_TO_EXECUTE) {
                setResultToToast("Mission is ready to execute after upload.")
            }
        }
        override fun onExecutionUpdate(executionEvent: WaypointMissionExecutionEvent) {
            Log.d(TAG, "WaypointMissionOperator onExecutionUpdate. Current waypoint index: ${executionEvent.progress?.targetWaypointIndex}")
        }
        override fun onExecutionStart() {
            setResultToToast("Execution started.")
            Log.d(TAG, "WaypointMissionOperator onExecutionStart")
        }
        override fun onExecutionFinish(error: DJIError?) {
            val message = "Execution finished: " + if (error == null) "Success!" else "${error.description} (Code: ${error.errorCode})"
            setResultToToast(message)
            Log.d(TAG, message)
        }
    }

    private fun getWaypointMissionOperator(): MavicMiniMissionOperator? {
        if(mavicMiniMissionOperator == null){
            Log.d(TAG, "Initializing MavicMiniMissionOperator")
            mavicMiniMissionOperator = MavicMiniMissionOperator(this)
        }
        return mavicMiniMissionOperator
    }
}