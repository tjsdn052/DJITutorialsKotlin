// File: com/riis/gsdemo_kotlin/MavicMiniMissionOperator.kt
package com.riis.gsdemo_kotlin
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.riis.gsdemo_kotlin.DJIDemoApplication.getCameraInstance
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.error.DJIMissionError
import dji.common.flightcontroller.LocationCoordinate3D
import dji.common.flightcontroller.virtualstick.*
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.mission.MissionState
import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.model.LocationCoordinate2D
import dji.common.util.CommonCallbacks
import dji.sdk.camera.Camera
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import dji.common.gimbal.GimbalState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MMMissionOperator"

class MavicMiniMissionOperator(context: Context) {

    private var isLanding: Boolean = false
    private var isLanded: Boolean = false
    private var isAirborne: Boolean = false
    private var photoIsSuccess: Boolean = false
    private var observeGimbal: Boolean = false
    private val activity: AppCompatActivity
    private val mContext = context
    private var gimbalObserver: Observer<Float>? = null
    private var currentDroneLatitude: Double = 0.0
    private var currentDroneLongitude: Double = 0.0
    private var currentDroneAltitude: Double = 0.0
    private var currentDroneHeading: Float = 0f


    private var state: MissionState = WaypointMissionState.INITIAL_PHASE
    private lateinit var mission: WaypointMission
    private lateinit var waypoints: MutableList<Waypoint>
    private lateinit var currentWaypoint: Waypoint

    private var operatorListener: WaypointMissionOperatorListener? = null
    var droneLocationMutableLiveData: MutableLiveData<LocationCoordinate3D> =
        MutableLiveData()
    val droneLocationLiveData: LiveData<LocationCoordinate3D> = droneLocationMutableLiveData

    private var travelledLongitude = false
    private var travelledLatitude = false
    private var waypointTracker = 0

    private var sendDataTimer =
        Timer() //used to schedule tasks for future execution in a background thread
    private lateinit var sendDataTask: SendDataTask

    private var originalLongitudeDiff = -1.0
    private var originalLatitudeDiff = -1.0
    private var directions = Direction(altitude = 0f)

    private var currentGimbalPitch: Float = 0f
    private var gimbalPitchLiveData: MutableLiveData<Float> = MutableLiveData()

    private var distanceToWaypoint = 0.0
    private var photoTakenToggle = false


    init {
        initFlightController()
        initGimbalListener()
        activity = context as AppCompatActivity
    }

    private fun initFlightController() {
        DJIDemoApplication.getFlightController()?.let { flightController ->
            flightController.setVirtualStickModeEnabled(
                true,
                null
            )//enables the aircraft to be controlled virtually

            //setting the modes for controlling the drone's roll, pitch, and yaw
            flightController.rollPitchControlMode = RollPitchControlMode.VELOCITY
            flightController.yawControlMode = YawControlMode.ANGLE
            flightController.verticalControlMode = VerticalControlMode.POSITION

            //setting the drone's flight coordinate system
            flightController.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND

            flightController.setStateCallback { flightControllerState ->
                currentDroneLatitude = flightControllerState.aircraftLocation.latitude
                currentDroneLongitude = flightControllerState.aircraftLocation.longitude
                currentDroneAltitude = flightControllerState.aircraftLocation.altitude.toDouble()
                currentDroneHeading = flightControllerState.aircraftHeadDirection.toFloat()
                droneLocationMutableLiveData.postValue(flightControllerState.aircraftLocation)
            }
        }
    }

    private fun initGimbalListener() {
        DJIDemoApplication.getGimbal()?.setStateCallback { gimbalState: GimbalState ->
            currentGimbalPitch = gimbalState.attitudeInDegrees.pitch
            gimbalPitchLiveData.postValue(currentGimbalPitch)
        }
    }

    // Function for taking a single photo and uploading it along with location
    private fun captureScreenAndUpload() {
        val camera = getCameraInstance()
        if (camera == null) {
            showToast(mContext, "카메라에 연결할 수 없습니다.")
            return
        }

        camera.setShootPhotoMode(SettingsDefinitions.ShootPhotoMode.SINGLE) { djiError ->
            if (djiError == null) {
                camera.startShootPhoto { djiErrorSecond ->
                    if (djiErrorSecond == null) {
                        Log.d(TAG, "사진 촬영 성공!")
                        showToast(mContext, "사진 촬영 성공!")
                        photoIsSuccess = true

                        activity.lifecycleScope.launch(Dispatchers.IO) {
                            withContext(Dispatchers.Main) {
                                showToast(mContext, "사진 및 위치 전송 시도...")
                            }
                            sendWaypointAndPhotoToServer()
                        }

                    } else {
                        Log.d(TAG, "사진 촬영 실패: ${djiErrorSecond.description}")
                        showToast(mContext, "사진 촬영 실패: ${djiErrorSecond.description}")
                        photoIsSuccess = false
                    }
                }
            } else {
                Log.d(TAG, "카메라 모드 설정 실패: ${djiError.description}")
                showToast(mContext, "카메라 모드 설정 실패: ${djiError.description}")
            }
        }
    }

    private suspend fun sendWaypointAndPhotoToServer() {
        // GPS 정보 전송
        val lat = currentDroneLatitude
        val lng = currentDroneLongitude
        val alt = currentDroneAltitude
        val heading = currentDroneHeading

        if (lat == 0.0 && lng == 0.0) {
            withContext(Dispatchers.Main) {
                showToast(mContext, "위치 정보를 전송할 수 없습니다. GPS를 확인해주세요.")
            }
            return
        }

        if (lat.isNaN() || lng.isNaN() || alt.isNaN() || heading.isNaN()) {
            withContext(Dispatchers.Main) {
                showToast(mContext, "유효하지 않은 위치 정보입니다. GPS를 확인해주세요.")
            }
            return
        }

        try {
            val client = OkHttpClient()
            val json = JSONObject().apply {
                put("latitude", lat)
                put("longitude", lng)
                put("altitude", alt)
                put("sequence", waypointTracker) // 현재 웨이포인트 인덱스를 시퀀스로 사용
                put("heading", heading)
            }

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("http://3.37.127.247:8080/waypoint")
                .post(requestBody)
                .addHeader("accept", "/")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            withContext(Dispatchers.Main) {
                if (response.isSuccessful) {
                    showToast(mContext, "GPS 위치 전송 성공!")
                } else {
                    val errorMessage = responseBody ?: "알 수 없는 오류"
                    showToast(mContext, "GPS 위치 전송 실패: ${response.code} - $errorMessage")
                }
            }
        } catch (e: IOException) {
            withContext(Dispatchers.Main) {
                showToast(mContext, "GPS 위치 전송 네트워크 오류: ${e.message}")
            }
            e.printStackTrace()
        }
    }


    //Function used to set the current waypoint mission and waypoint list
    fun loadMission(mission: WaypointMission?): DJIError? {
        return if (mission == null) {
            this.state = WaypointMissionState.NOT_READY
            DJIMissionError.NULL_MISSION
        } else {
            this.mission = mission
            this.waypoints = mission.waypointList
            this.state = WaypointMissionState.READY_TO_UPLOAD
            null
        }
    }

    private fun pauseMission(){
        if (this.state == WaypointMissionState.EXECUTING){
            Log.d(TAG, "trying to pause")
//            WaypointMissionOperator().pauseMission {
//            }
            this.state = WaypointMissionState.EXECUTION_PAUSED
        }
    }

    //Function used to get the current waypoint mission ready to start
    fun uploadMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        if (this.state == WaypointMissionState.READY_TO_UPLOAD) {
            this.state = WaypointMissionState.READY_TO_START
            callback?.onResult(null)
        } else {
            this.state = WaypointMissionState.NOT_READY
            callback?.onResult(DJIMissionError.UPLOADING_WAYPOINT)
        }
    }

    //Function used to make the drone takeoff and then begin execution of the current waypoint mission
    fun startMission(callback: CommonCallbacks.CompletionCallback<DJIError>?) {
        gimbalObserver = Observer {gimbalPitch: Float ->
            if (gimbalPitch == -90f && !isAirborne) {
                isAirborne = true
                showToast(mContext, "Starting to Takeoff")
                Log.d(TAG, "startMission: Start take off")
                DJIDemoApplication.getFlightController()?.startTakeoff { error ->
                    if (error == null) {
                        callback?.onResult(null)
                        this.state = WaypointMissionState.READY_TO_EXECUTE

                        getCameraInstance()?.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO) { error ->
                            if (error == null) {
                                showToast(mContext, "Switch Camera Mode Succeeded")
                            } else {
                                showToast(mContext, "Switch Camera Error: ${error.description}")
                            }
                        }

                        val handler = Handler(Looper.getMainLooper())
                        handler.postDelayed({
                            executeMission()
                        }, 8000)
                    } else {
                        callback?.onResult(error)
                    }
                }
            }
        }
        if (this.state == WaypointMissionState.READY_TO_START) {
            rotateGimbalDown()
            gimbalObserver?.let {
                gimbalPitchLiveData.observe(activity, it)
            }
        } else {
            callback?.onResult(DJIMissionError.FAILED)
        }
    }

    private fun rotateGimbalDown() {
        val rotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(-90f).build()
        try {
            val gimbal = DJIDemoApplication.getGimbal()

            gimbal?.rotate(
                rotation
            ) { djiError ->
                if (djiError == null) {
                    Log.d(TAG, "rotate gimbal success")
                    showToast(mContext, "rotate gimbal success")

                } else {
                    Log.d(TAG, "rotate gimbal error " + djiError.description)
                    showToast(mContext, djiError.description)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Drone is likely not connected")
        }
    }

    /*
 * Calculate the euclidean distance between two points.
 * Ignore curvature of the earth.
 *
 * @param a: The first point
 * @param b: The second point
 * @return: The square of the distance between a and b
 */
    private fun distanceInMeters(a: LocationCoordinate2D, b: LocationCoordinate2D): Double {
        return sqrt((a.longitude - b.longitude).pow(2.0) + (a.latitude - b.latitude).pow(2.0)) * 111139.0
    }

    //Function used to execute the current waypoint mission
    private fun executeMission() {
        state = WaypointMissionState.EXECUTION_STARTING
        operatorListener?.onExecutionStart()
        //running the execution in a coroutine to prevent blocking the main thread
        activity.lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                if (waypointTracker >= waypoints.size) return@withContext
                currentWaypoint = waypoints[waypointTracker] //getting the current waypoint
                droneLocationLiveData.observe(activity, locationObserver)
            }
        }
    }

    private val locationObserver = Observer<LocationCoordinate3D> { currentLocation ->
        //observing changes to the drone's location coordinates
        state = WaypointMissionState.EXECUTING

        distanceToWaypoint = distanceInMeters(
            LocationCoordinate2D(
                currentWaypoint.coordinate.latitude,
                currentWaypoint.coordinate.longitude,

                ),
            LocationCoordinate2D(
                currentLocation.latitude,
                currentLocation.longitude
            )
        )
        if (!isLanded && !isLanding) {
            //If the drone has arrived at the destination, take a photo and send location.
            if (!photoTakenToggle && (distanceToWaypoint < 1.5)) {//if you haven't taken a photo
                captureScreenAndUpload() // 사진 촬영 및 위치 전송
                photoTakenToggle = true
                Log.d(
                    TAG,
                    "attempting to take photo: $photoTakenToggle, $photoIsSuccess"
                )
            } else if (photoTakenToggle && (distanceToWaypoint >= 1.5)) {
                photoTakenToggle = false
                photoIsSuccess = false
            }
        }

        val longitudeDiff =
            currentWaypoint.coordinate.longitude - currentLocation.longitude
        val latitudeDiff =
            currentWaypoint.coordinate.latitude - currentLocation.latitude

        if (abs(latitudeDiff) > originalLatitudeDiff) {
            originalLatitudeDiff = abs(latitudeDiff)
        }

        if (abs(longitudeDiff) > originalLongitudeDiff) {
            originalLongitudeDiff = abs(longitudeDiff)
        }

        //terminating the sendDataTimer and creating a new one
        sendDataTimer.cancel()
        sendDataTimer = Timer()

        if (!travelledLongitude) {//!travelledLongitude
            val speed = kotlin.math.max(
                (mission.autoFlightSpeed * (abs(longitudeDiff) / (originalLongitudeDiff))).toFloat(),
                0.5f
            )

            directions.pitch = if (longitudeDiff > 0) speed else -speed

        }

        if (!travelledLatitude) {
            val speed = kotlin.math.max(
                (mission.autoFlightSpeed * (abs(latitudeDiff) / (originalLatitudeDiff))).toFloat(),
                0.5f
            )

            directions.roll = if (latitudeDiff > 0) speed else -speed

        }

        //when the longitude difference becomes insignificant:
        if (abs(longitudeDiff) < 0.000002) {
            Log.i(TAG, "finished travelling LONGITUDE")
            directions.pitch = 0f
            travelledLongitude = true
        }


        if (abs(latitudeDiff) < 0.000002) {
            Log.i(TAG, "finished travelling LATITUDE")
            directions.roll = 0f
            travelledLatitude = true
        }

        //when the latitude difference becomes insignificant and there
        //... is no longitude difference (current waypoint has been reached):
        if (travelledLatitude && travelledLongitude) {
            //move to the next waypoint in the waypoints list
            waypointTracker++
            if (waypointTracker < waypoints.size) {
                currentWaypoint = waypoints[waypointTracker]
                originalLatitudeDiff = -1.0
                originalLongitudeDiff = -1.0
                travelledLongitude = false
                travelledLatitude = false
                directions = Direction()
            } else { //If all waypoints have been reached, stop the mission
                state = WaypointMissionState.EXECUTION_STOPPING
                operatorListener?.onExecutionFinish(null)
                stopMission(null)
                isLanding = true
                sendDataTimer.cancel()
                if (isLanding && currentLocation.altitude == 0f) {
                    if (!isLanded) {
                        sendDataTimer.cancel()
                        isLanded = true
                    }

                }
                removeObserver()
            }
            sendDataTimer.cancel() //cancel all scheduled data tasks
        } else {
            // checking for pause state
            if (state == WaypointMissionState.EXECUTING) {
                directions.altitude = currentWaypoint.altitude
            } else if (state == WaypointMissionState.EXECUTION_PAUSED) {
                directions = Direction(0f, 0f, 0f, currentWaypoint.altitude)
            }
            move(directions)

        }

    }

    private fun removeObserver() {
        droneLocationLiveData.removeObserver(locationObserver)
        gimbalObserver?.let {
            gimbalPitchLiveData.removeObserver(it)
        }
        observeGimbal = false
        isAirborne = false
        waypointTracker = 0
        isLanded = false
        isLanding = false
        travelledLatitude = false
        travelledLongitude = false
    }


    @SuppressLint("LongLogTag")
    //Function used to move the drone in the provided direction
    private fun move(dir: Direction) {
        Log.d(TAG, "PITCH: ${dir.pitch}, ROLL: ${dir.roll}, ALT: ${dir.altitude}")
        sendDataTask =
            SendDataTask(dir.pitch, dir.roll, dir.yaw, dir.altitude)
        sendDataTimer.schedule(sendDataTask, 0, 200)
    }

//TODO
// -   fun resumeMission() {
// -
// -   }
// -
// -   fun pauseMission() {
// -
// -   }

    //Function used to stop the current waypoint mission and land the drone
    fun stopMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        if (!isLanding) {
            showToast(mContext, "trying to land")
        }
        DJIDemoApplication.getFlightController()?.setGoHomeHeightInMeters(30){
            DJIDemoApplication.getFlightController()?.startGoHome(callback)
        }
    }

    /*
     * Roll: POSITIVE is SOUTH, NEGATIVE is NORTH, Range: [-30, 30]
     * Pitch: POSITIVE is EAST, NEGATIVE is WEST, Range: [-30, 30]
     * YAW: POSITIVE is RIGHT, NEGATIVE is LEFT, Range: [-360, 360]
     * THROTTLE: UPWARDS MOVEMENT
     */

    fun addListener(listener: WaypointMissionOperatorListener) {
        this.operatorListener = listener
    }

    fun removeListener() {
        this.operatorListener = null
    }

    class SendDataTask(pitch: Float, roll: Float, yaw: Float, throttle: Float) : TimerTask() {
        private val mPitch = pitch
        private val mRoll = roll
        private val mYaw = yaw
        private val mThrottle = throttle
        override fun run() {
            DJIDemoApplication.getFlightController()?.sendVirtualStickFlightControlData(
                FlightControlData(
                    mPitch,
                    mRoll,
                    mYaw,
                    mThrottle
                ),
                null
            )
            this.cancel()
        }
    }

    inner class Direction(
        var pitch: Float = 0f,
        var roll: Float = 0f,
        var yaw: Float = 0f,
        var altitude: Float = currentWaypoint.altitude
    )

    private fun showToast(context: Context, message: String){
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}