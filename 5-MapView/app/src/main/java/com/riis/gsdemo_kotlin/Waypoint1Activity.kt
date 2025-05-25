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
// import dji.common.flightcontroller.simulator.InitializationData // User commented out
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


class Waypoint1Activity : AppCompatActivity(), MapboxMap.OnMapClickListener, OnMapReadyCallback, View.OnClickListener, MapboxMap.OnMarkerClickListener {

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

//    private val SIMULATED_DRONE_LAT = 36.762556 // User commented out
//    private val SIMULATED_DRONE_LONG = -127.281789 // User commented out

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
        mapboxMap.setOnMarkerClickListener(this) // OnMarkerClickListener 등록
        mapboxMap.setStyle(Style.MAPBOX_STREETS) { style ->
            Log.d(TAG, "Mapbox style loaded successfully.")
            val fixedLatLng = LatLng(36.763706, 127.281910)
            mapboxMap.moveCamera(CameraUpdateFactory.newLatLngZoom(fixedLatLng, 15.0))
            if (!style.isFullyLoaded) {
                Log.w(TAG, "Style is not fully loaded even if onStyleLoaded was called.")
            }
        }
    }

    override fun onMapClick(point: LatLng): Boolean {
        if (isAdd) {
            markWaypoint(point, altitude.toDouble(), waypointList.size)

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

    private fun markWaypoint(point: LatLng, alt: Double, index: Int, heading: Int? = null) {
        val title: String
        val snippet: String

        if (heading != null) {
            title = String.format(Locale.US, "WP %d: Lat: %.6f, Lng: %.6f, Alt: %.2fm, Hdg: %d°",
                index + 1, point.latitude, point.longitude, alt, heading)
            snippet = String.format(Locale.US, "Alt: %.2fm, Hdg: %d°", alt, heading) // Snippet can remain more concise or be same as title
        } else {
            title = String.format(Locale.US, "WP %d: Lat: %.6f, Lng: %.6f, Alt: %.2fm",
                index + 1, point.latitude, point.longitude, alt)
            snippet = String.format(Locale.US, "Alt: %.2fm", alt) // Snippet can remain more concise
        }

        val markerOptions = MarkerOptions()
            .position(point)
            .title(title)
            .snippet(snippet) // You might want to adjust the snippet if the title now contains all info
        mapboxMap?.let {
            markers[index]?.remove() // 이전 마커가 있다면 제거 (업데이트 시 유용)
            val marker = it.addMarker(markerOptions)
            markers[index] = marker
        }
    }

    override fun onMarkerClick(clickedMarker: Marker): Boolean {
        if (clickedMarker == droneMarker) {
            return false
        }

        var waypointIndexToRemove: Int? = null
        for ((index, markerInMap) in markers) {
            if (markerInMap == clickedMarker) {
                if (index < waypointList.size) {
                    waypointIndexToRemove = index
                }
                break
            }
        }

        if (waypointIndexToRemove != null) {
            val finalIndex = waypointIndexToRemove
            AlertDialog.Builder(this)
                .setTitle("웨이포인트 삭제")
                .setMessage("'${clickedMarker.title}' 웨이포인트를 삭제하시겠습니까?")
                .setPositiveButton("삭제") { _, _ ->
                    deleteWaypoint(finalIndex, clickedMarker)
                }
                .setNegativeButton("취소", null)
                .show()
            return true
        }
        return false
    }

    private fun deleteWaypoint(indexToRemove: Int, markerToToast: Marker) {
        if (indexToRemove < 0 || indexToRemove >= waypointList.size) {
            setResultToToast("오류: 잘못된 삭제 인덱스입니다.")
            Log.e(TAG, "Attempted to delete waypoint with invalid index: $indexToRemove")
            return
        }

        val markerTitleForToast = markerToToast.title ?: "선택된 웨이포인트"

        val removedWaypoint = waypointList.removeAt(indexToRemove)
        Log.d(TAG, "웨이포인트 목록에서 ${removedWaypoint.coordinate} 삭제됨. 현재 목록 크기: ${waypointList.size}")

        markers.values.forEach { it.remove() }
        markers.clear()

        waypointList.forEachIndexed { newIndex, waypoint ->
            val headingForMarker: Int? = if (headingMode == WaypointMissionHeadingMode.USING_WAYPOINT_HEADING) {
                waypoint.heading.takeIf { it != 0 || waypointList[newIndex].heading != 0 }
            } else {
                null
            }
            markWaypoint(
                LatLng(waypoint.coordinate.latitude, waypoint.coordinate.longitude),
                waypoint.altitude.toDouble(),
                newIndex,
                headingForMarker
            )
        }

        if (waypointMissionBuilder == null && waypointList.isNotEmpty()) {
            waypointMissionBuilder = WaypointMission.Builder()
        }

        waypointMissionBuilder?.waypointList(waypointList)
        waypointMissionBuilder?.waypointCount(waypointList.size)

        configWayPointMission()

        setResultToToast("'$markerTitleForToast' 삭제 완료.")

        if (waypointList.isEmpty()) {
            setResultToToast("모든 웨이포인트가 삭제되었습니다.")
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
//            val simulateLocation = LocationCoordinate2D(SIMULATED_DRONE_LAT, SIMULATED_DRONE_LONG) // User commented out
//            flightController.simulator.start( // User commented out
//                InitializationData.createInstance(simulateLocation, 10, 10) // User commented out
//            ){ error -> // User commented out
//                if (error != null) { // User commented out
//                    Log.e(TAG, "initFlightController: Error starting simulator: ${error.description}") // User commented out
//                } else { // User commented out
//                    Log.d(TAG, "initFlightController: Simulator started successfully") // User commented out
//                } // User commented out
//            } // User commented out

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
                    mapboxMap?.clear()
                    markers.clear()
                }
                waypointList.clear()
                waypointMissionBuilder?.waypointList(waypointList)?.waypointCount(waypointList.size)

                if (waypointMissionBuilder != null) {
                    configWayPointMission()
                } else {
                    // *** 수정된 부분 시작 ***
                    val error = getWaypointMissionOperator()?.loadMission(null)
                    if (error == null) {
                        Log.d(TAG, "Mission cleared from operator after full clear (no builder).")
                    } else {
                        Log.e(TAG, "Error clearing mission from operator: ${error.description}")
                        setResultToToast("미션 비우기 실패: ${error.description}")
                    }
                    // *** 수정된 부분 끝 ***
                }
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
        val buildingId = "26"

        if (buildingId.isBlank()) {
            setResultToToast("Building ID가 필요합니다.")
            return
        }

        setResultToToast("서버에서 웨이포인트 불러오는 중 (Building ID: $buildingId)...")
        val serverUrl = "http://3.37.127.247:8080/waypoints$buildingId"

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
                        val fetchedMarkerData = mutableListOf<Triple<LatLng, Double, Int>>()

                        val jsonArray = JSONArray(responseBody)
                        for (i in 0 until jsonArray.length()) {
                            val wpObject = jsonArray.getJSONObject(i)
                            val lat = wpObject.optDouble("lat")
                            val lng = wpObject.optDouble("lng")
                            val alt = wpObject.optDouble("altitude").toFloat()
                            val heading = wpObject.optInt("heading", 0)

                            if (checkGpsCoordination(lat, lng)) {
                                val waypoint = Waypoint(lat, lng, alt)
                                waypoint.heading = heading
                                fetchedWaypoints.add(waypoint)
                                fetchedMarkerData.add(Triple(LatLng(lat, lng), alt.toDouble(), heading))
                            }
                        }

                        withContext(Dispatchers.Main) {
                            markers.values.forEach { it.remove() }
                            markers.clear()
                            waypointList.clear()

                            waypointList.addAll(fetchedWaypoints)
                            if (waypointMissionBuilder == null) {
                                waypointMissionBuilder = WaypointMission.Builder()
                            }
                            waypointMissionBuilder?.waypointList(waypointList)?.waypointCount(waypointList.size)

                            fetchedMarkerData.forEachIndexed { index, data ->
                                val latLng = data.first
                                val markerAlt = data.second
                                val markerHeading = data.third
                                markWaypoint(latLng, markerAlt, index, markerHeading)
                            }

                            if (fetchedWaypoints.isNotEmpty()) {
                                cameraUpdateToFirstWaypoint(fetchedWaypoints.first())
                                setResultToToast("서버에서 웨이포인트 로드 완료: ${fetchedWaypoints.size}개")
                            } else {
                                setResultToToast("서버에서 유효한 웨이포인트를 받지 못했습니다.")
                            }
                            configWayPointMission()
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
            val altitudeInput = altStr.toFloat()

            if (!checkGpsCoordination(latitude, longitude)) {
                setResultToToast("Invalid GPS coordinates.")
                return
            }

            val newPoint = LatLng(latitude, longitude)
            markWaypoint(newPoint, altitudeInput.toDouble(), waypointList.size)

            val waypoint = Waypoint(latitude, longitude, altitudeInput)

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
        if (waypointMissionBuilder != null && waypointList.isNotEmpty()) {
            configWayPointMission()
            getWaypointMissionOperator()?.startMission { error ->
                setResultToToast("Mission Start: " + if (error == null) "Successfully" else error.description)
            }
        } else {
            setResultToToast("Cannot start mission: No waypoints or mission not configured.")
        }
    }

    private fun stopWaypointMission() {
        getWaypointMissionOperator()?.stopMission { error ->
            setResultToToast("Mission Stop: " + if (error == null) "Successfully" else error.description)
        }
    }

    private fun uploadWaypointMission() {
        if (waypointMissionBuilder == null || waypointList.isEmpty()) {
            setResultToToast("No waypoints to upload.")
            return
        }
        configWayPointMission()

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
        val speedSeekBar = wayPointSettings.findViewById<SeekBar>(R.id.speedSeekBar)
        val speedValueTextView = wayPointSettings.findViewById<TextView>(R.id.speedValueTextView)
        val actionAfterFinishedRG = wayPointSettings.findViewById<RadioGroup>(R.id.actionAfterFinished)
        val headingRG = wayPointSettings.findViewById<RadioGroup>(R.id.heading)

        wpAltitudeTV.setText(altitude.toInt().toString())

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
        when (headingMode) {
            WaypointMissionHeadingMode.AUTO -> headingRG.check(R.id.headingNext)
            WaypointMissionHeadingMode.USING_INITIAL_DIRECTION -> headingRG.check(R.id.headingInitDirec)
            WaypointMissionHeadingMode.CONTROL_BY_REMOTE_CONTROLLER -> headingRG.check(R.id.headingRC)
            WaypointMissionHeadingMode.USING_WAYPOINT_HEADING -> headingRG.check(R.id.headingWP)
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
        if (waypointMissionBuilder == null && waypointList.isNotEmpty()) {
            waypointMissionBuilder = WaypointMission.Builder()
        } else if (waypointMissionBuilder == null && waypointList.isEmpty()) {
            waypointMissionBuilder = WaypointMission.Builder()
        }

        val builder = waypointMissionBuilder ?: run {
            Log.w(TAG, "configWayPointMission: waypointMissionBuilder was unexpectedly null. Creating a new one.")
            waypointMissionBuilder = WaypointMission.Builder()
            waypointMissionBuilder!!
        }

        builder.waypointList(waypointList).waypointCount(waypointList.size)

        builder.finishedAction(finishedAction)
            .headingMode(headingMode)
            .autoFlightSpeed(speed)
            .maxFlightSpeed(speed)
            .flightPathMode(WaypointMissionFlightPathMode.NORMAL)
            .gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
            .isGimbalPitchRotationEnabled = true

        for (waypoint in waypointList) {
            waypoint.waypointActions.removeAll { it.actionType == WaypointActionType.START_TAKE_PHOTO }
            if (waypoint.waypointActions.none { it.actionType == WaypointActionType.GIMBAL_PITCH }) {
                waypoint.addAction(WaypointAction(WaypointActionType.GIMBAL_PITCH, -90))
            }
        }

        getWaypointMissionOperator()?.let { operator ->
            val missionToLoad = builder.build()
            // *** 수정된 부분 시작 ***
            val error = operator.loadMission(missionToLoad)
            if (error == null) {
                if (missionToLoad.waypointCount > 0) {
                    setResultToToast("미션 로드 성공 / 업데이트됨")
                } else {
                    setResultToToast("미션 비워짐 (로드할 웨이포인트 없음)")
                }
                Log.d(TAG, "loadWaypointMission succeeded. Waypoint Count: ${missionToLoad.waypointCount}")
            } else {
                setResultToToast("미션 로드 실패: ${error.description} (Code: ${error.errorCode})")
                Log.e(TAG, "loadWaypointMission failed: ${error.description}, Code: ${error.errorCode}")
            }
            // *** 수정된 부분 끝 ***
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