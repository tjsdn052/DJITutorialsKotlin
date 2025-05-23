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
import dji.common.flightcontroller.simulator.InitializationData
import dji.sdk.sdkmanager.DJISDKManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale


class Waypoint1Activity : AppCompatActivity(), MapboxMap.OnMapClickListener, OnMapReadyCallback, View.OnClickListener {

    private lateinit var locate: Button
    private lateinit var add: Button
    private lateinit var clear: Button
    private lateinit var config: Button
    private lateinit var upload: Button
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var sendLocationButton: Button

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
    private val markers: MutableMap<Int, Marker> = ConcurrentHashMap<Int, Marker>()
    private var mapboxMap: MapboxMap? = null
    private var mavicMiniMissionOperator: MavicMiniMissionOperator? = null

    private val SIMULATED_DRONE_LAT = 42.557965 // 시뮬레이터 사용 시 주석 해제
    private val SIMULATED_DRONE_LONG = -83.154303 // 시뮬레이터 사용 시 주석 해제

    private var altitude = 100f
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
        mapboxMap.setStyle(Style.MAPBOX_STREETS, object : Style.OnStyleLoaded {
            override fun onStyleLoaded(style: Style) {
                Log.d(TAG, "Mapbox style loaded successfully.")
                val fixedLatLng = LatLng(37.5665, 126.9780) // 예: 서울 시청
                mapboxMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fixedLatLng, 15.0))
                if (!style.isFullyLoaded) {
                    Log.w(TAG, "Style is not fully loaded even if onStyleLoaded was called.")
                }
            }
        })
    }

    override fun onMapClick(point: LatLng): Boolean {
        if (isAdd) {
            // Use the current 'altitude' value for the waypoint added by map click
            markWaypoint(point, altitude.toDouble())
            val waypoint = Waypoint(point.latitude, point.longitude, altitude)

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

    // Modified markWaypoint function to accept altitude for marker title/snippet
    private fun markWaypoint(point: LatLng, alt: Double) {
        val markerOptions = MarkerOptions()
            .position(point)
            .title(String.format(Locale.US, "Lat: %.6f, Lng: %.6f", point.latitude, point.longitude))
            .snippet(String.format(Locale.US, "Alt: %.3f m", alt))
        mapboxMap?.let {
            val marker = it.addMarker(markerOptions)
            markers[markers.size] = marker
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

        // Initialize new TextViews
        droneLatTextView = findViewById(R.id.droneLatTextView)
        droneLngTextView = findViewById(R.id.droneLngTextView)
        droneAltTextView = findViewById(R.id.droneAltTextView)

        // Initialize new UI elements for manual waypoint input
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
    }


    @SuppressLint("SetTextI18n")
    private fun initFlightController() {
        DJIDemoApplication.getFlightController()?.let { flightController ->
            val simulateLocation = LocationCoordinate2D(SIMULATED_DRONE_LAT, SIMULATED_DRONE_LONG)
            flightController.simulator.start(
                InitializationData.createInstance(simulateLocation, 10, 10)
            ){ error ->
                if (error != null) {
                    Log.e(TAG, "initFlightController: Error starting simulator: ${error.description}")
                } else {
                    Log.d(TAG, "initFlightController: Simulator started successfully")
                }
            }

            flightController.setStateCallback { flightControllerState ->
                droneLocationLat = flightControllerState.aircraftLocation.latitude
                droneLocationLng = flightControllerState.aircraftLocation.longitude
                droneLocationAlt = flightControllerState.aircraftLocation.altitude

                runOnUiThread {
                    mavicMiniMissionOperator?.droneLocationMutableLiveData?.postValue(flightControllerState.aircraftLocation)
                    updateDroneLocation() // This will also update the TextViews
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
        // Update TextViews with current drone location
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
                    mapboxMap?.clear()
                }
                waypointList.clear()
                waypointMissionBuilder?.waypointList(waypointList)?.waypointCount(waypointList.size)
                markers.clear()
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
            else -> {}
        }
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
            val altitude = altStr.toFloat()

            if (!checkGpsCoordination(latitude, longitude)) {
                setResultToToast("Invalid GPS coordinates. Latitude must be between -90 and 90, Longitude between -180 and 180, and neither can be 0.")
                return
            }

            val newPoint = LatLng(latitude, longitude)
            markWaypoint(newPoint, altitude.toDouble()) // Mark with specified altitude

            val waypoint = Waypoint(latitude, longitude, altitude)
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
            setResultToToast("Waypoint added manually: Lat: $latitude, Lng: $longitude, Alt: $altitude m")
            // Clear input fields after adding
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

        val wpAltitudeTV = wayPointSettings.findViewById<EditText>(R.id.altitude)
        val speedRG = wayPointSettings.findViewById<RadioGroup>(R.id.speed)
        val actionAfterFinishedRG = wayPointSettings.findViewById<RadioGroup>(R.id.actionAfterFinished)
        val headingRG = wayPointSettings.findViewById<RadioGroup>(R.id.heading)

        wpAltitudeTV.setText(altitude.toInt().toString())
        when (speed) {
            3.0f -> speedRG.check(R.id.lowSpeed)
            5.0f -> speedRG.check(R.id.MidSpeed)
            10.0f -> speedRG.check(R.id.HighSpeed)
        }
        when (finishedAction) {
            WaypointMissionFinishedAction.NO_ACTION -> actionAfterFinishedRG.check(R.id.finishNone)
            WaypointMissionFinishedAction.GO_HOME -> actionAfterFinishedRG.check(R.id.finishGoHome)
            WaypointMissionFinishedAction.AUTO_LAND -> actionAfterFinishedRG.check(R.id.finishAutoLanding)
            WaypointMissionFinishedAction.GO_FIRST_WAYPOINT -> actionAfterFinishedRG.check(R.id.finishToFirst)
            else -> {}
        }
        when (headingMode) {
            WaypointMissionHeadingMode.AUTO -> headingRG.check(R.id.headingNext)
            WaypointMissionHeadingMode.USING_INITIAL_DIRECTION -> headingRG.check(R.id.headingInitDirec)
            WaypointMissionHeadingMode.CONTROL_BY_REMOTE_CONTROLLER -> headingRG.check(R.id.headingRC)
            WaypointMissionHeadingMode.USING_WAYPOINT_HEADING -> headingRG.check(R.id.headingWP)
            else -> {}
        }


        speedRG.setOnCheckedChangeListener { _, checkedId ->
            speed = when (checkedId) {
                R.id.lowSpeed -> 3.0f
                R.id.MidSpeed -> 5.0f
                R.id.HighSpeed -> 10.0f
                else -> speed
            }
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
            headingMode = when (checkedId) {
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
                altitude = nullToIntegerDefault(altitudeString).toFloat()
                Log.d(TAG, "Altitude: $altitude, Speed: $speed, Finished Action: $finishedAction, Heading Mode: $headingMode")
                configWayPointMission()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
            .create()
            .show()
    }

    private fun configWayPointMission() {
        val builder = waypointMissionBuilder ?: WaypointMission.Builder()

        builder.finishedAction(finishedAction)
            .headingMode(headingMode)
            .autoFlightSpeed(speed)
            .maxFlightSpeed(speed)
            .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
            .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
            .isGimbalPitchRotationEnabled = true

        if (builder.waypointList.isNotEmpty()) {
            for (waypoint in builder.waypointList) {
                waypoint.altitude = altitude
                if (waypoint.waypointActions.none { it.actionType == WaypointActionType.GIMBAL_PITCH }) {
                    waypoint.addAction(WaypointAction(WaypointActionType.GIMBAL_PITCH, -90))
                }
                if (waypoint.waypointActions.none { it.actionType == WaypointActionType.START_TAKE_PHOTO }) {
                    waypoint.addAction(WaypointAction(WaypointActionType.START_TAKE_PHOTO, 0))
                }
            }
            setResultToToast("Set Waypoint parameters successfully")
        }

        waypointMissionBuilder = builder

        getWaypointMissionOperator()?.let { operator ->
            val error = operator.loadMission(builder.build())
            if (error == null) {
                setResultToToast("loadWaypointMission succeeded")
            } else {
                setResultToToast("loadWaypointMission failed: ${error.description} (Code: ${(error as? DJIError)?.errorCode})")
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