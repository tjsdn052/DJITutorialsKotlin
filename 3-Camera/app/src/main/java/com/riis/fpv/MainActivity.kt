package com.riis.fpv

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.provider.MediaStore
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dji.common.camera.SettingsDefinitions.CameraMode
import dji.common.camera.SettingsDefinitions.ShootPhotoMode
import dji.common.product.Model
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.products.Aircraft
import dji.sdk.products.HandHeld
import dji.sdk.sdkmanager.DJISDKManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
This activity provides an interface to access a connected DJI Product's camera and use
it to take photos and record videos
*/
class MainActivity : AppCompatActivity(), TextureView.SurfaceTextureListener, View.OnClickListener {

    //Class Variables
    private val TAG = MainActivity::class.java.name

    //listener that is used to receive video data coming from the connected DJI product
    private var receivedVideoDataListener: VideoFeeder.VideoDataListener? = null
    private var codecManager: DJICodecManager? = null //handles the encoding and decoding of video data

    private lateinit var videoSurface: TextureView //Used to display the DJI product's camera video stream
    private lateinit var captureBtn: Button
    private lateinit var shootPhotoModeBtn: Button
    private lateinit var recordVideoModeBtn: Button
    private lateinit var recordBtn: ToggleButton
    private lateinit var recordingTime: TextView

    // 오버레이 텍스트 뷰
    private lateinit var latitudeTextView: TextView
    private lateinit var longitudeTextView: TextView
    private lateinit var altitudeTextView: TextView

    private val ACCESS_KEY = "test1234" // 실제 Access Key로 변경해주세요.

    // 드론 현재 위치 저장 변수
    private var currentDroneLatitude: Double = 0.0
    private var currentDroneLongitude: Double = 0.0
    private var currentDroneAltitude: Double = 0.0


    //Creating the Activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main) //inflating the activity_main.xml layout as the activity's view
        initUi() //initializing the UI

        /*
        The receivedVideoDataListener receives the raw video data and the size of the data from the DJI product.
        It then sends this data to the codec manager for decoding.
        */
        receivedVideoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            codecManager?.sendDataToDecoder(videoBuffer, size)
        }

        /*
        NOTES:
        - getCameraInstance() is used to get an instance of the camera running on the DJI product as
          a Camera class object.

         - SystemState is a subclass of Camera that provides general information and current status of the camera.

         - Whenever a change in the camera's SystemState occurs, SystemState.Callback is an interface that
           asynchronously updates the camera's SystemState.

         - setSystemStateCallback is a method of the Camera class which allows us to define what else happens during
           the systemState callback. In this case, we update the UI on the UI thread whenever the SystemState shows
           that the camera is video recording.
        */

        getCameraInstance()?.let { camera ->
            camera.setSystemStateCallback {
                it.let { systemState ->
                    //Getting elapsed video recording time in minutes and seconds, then converting into a time string
                    val recordTime = systemState.currentVideoRecordingTimeInSeconds
                    val minutes = (recordTime % 3600) / 60
                    val seconds = recordTime % 60
                    val timeString = String.format("%02d:%02d", minutes, seconds)

                    //Accessing the UI thread to update the activity's UI
                    runOnUiThread {
                        //If the camera is video recording, display the time string on the recordingTime TextView
                        recordingTime.text = timeString
                        if (systemState.isRecording) {
                            recordingTime.visibility = View.VISIBLE

                        } else {
                            recordingTime.visibility = View.INVISIBLE
                        }
                    }
                }
            }
        }

        // 드론의 비행 컨트롤러로부터 GPS 데이터 수신
        val aircraft = getProductInstance() as? Aircraft
        aircraft?.flightController?.setStateCallback { flightControllerState ->
            val location = flightControllerState.aircraftLocation
            val altitude = location?.altitude ?: 0.0

            runOnUiThread {
                if (location != null && location.latitude != -1.0 && location.longitude != -1.0) {
                    currentDroneLatitude = location.latitude // 현재 위도 저장
                    currentDroneLongitude = location.longitude // 현재 경도 저장
                    currentDroneAltitude = altitude.toDouble() // 현재 고도 저장 (Float to Double)

                    // 명시적으로 Double로 캐스팅
                    latitudeTextView.text = getString(R.string.latitude_label, location.latitude as Double)
                    longitudeTextView.text = getString(R.string.longitude_label, location.longitude as Double)
                } else {
                    currentDroneLatitude = 0.0 // 위치 정보 없을 시 기본값
                    currentDroneLongitude = 0.0
                    currentDroneAltitude = 0.0

                    latitudeTextView.text = getString(R.string.latitude_label, 0.0)
                    longitudeTextView.text = getString(R.string.longitude_label, 0.0)
                }
                // 명시적으로 Double로 캐스팅
                altitudeTextView.text = getString(R.string.altitude_label, altitude.toDouble())
            }
        }
    }

    //Function to initialize the activity's UI elements
    private fun initUi() {
        //referencing the layout views using their resource ids
        videoSurface = findViewById(R.id.video_previewer_surface)
        recordingTime = findViewById(R.id.timer)
        captureBtn = findViewById(R.id.btn_capture)
        recordBtn = findViewById(R.id.btn_record)
        shootPhotoModeBtn = findViewById(R.id.btn_shoot_photo_mode)
        recordVideoModeBtn = findViewById(R.id.btn_record_video_mode)

        // 오버레이 텍스트 뷰 초기화
        latitudeTextView = findViewById(R.id.latitude_text_view)
        longitudeTextView = findViewById(R.id.longitude_text_view)
        altitudeTextView = findViewById(R.id.altitude_text_view)

        /*
        Giving videoSurface a listener that checks for when a surface texture is available.
        The videoSurface will then display the surface texture, which in this case is a camera video stream.
        */
        videoSurface.surfaceTextureListener = this

        //Giving the non-toggle button elements a click listener
        captureBtn.setOnClickListener(this)
        shootPhotoModeBtn.setOnClickListener(this)
        recordVideoModeBtn.setOnClickListener(this)

        recordingTime.visibility = View.INVISIBLE

        /*
        recordBtn is a ToggleButton that when checked, the DJI product's camera starts video recording.
        When unchecked, the camera stops video recording.
        */
        recordBtn.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                startRecord()
            } else {
                stopRecord()
            }
        }
    }

    //Function to make the DJI drone's camera start video recording
    private fun startRecord() {
        val camera = getCameraInstance() ?:return //get camera instance or null if it doesn't exist

        /*
        starts the camera video recording and receives a callback. If the callback returns an error that
        is null, the operation is successful.
        */
        camera.startRecordVideo {
            if (it == null) {
                showToast("Record Video: Success")
            } else {
                showToast("Record Video Error: ${it.description}")
            }
        }
    }

    //Function to make the DJI product's camera stop video recording
    private fun stopRecord() {
        val camera = getCameraInstance() ?: return //get camera instance or null if it doesn't exist

        /*
        stops the camera video recording and receives a callback. If the callback returns an error that
        is null, the operation is successful.
        */
        camera.stopRecordVideo {
            if (it == null) {
                showToast("Stop Recording: Success")
            } else {
                showToast("Stop Recording: Error ${it.description}")
            }
        }
    }

    //Function that initializes the display for the videoSurface TextureView
    private fun initPreviewer() {

        //gets an instance of the connected DJI product (null if nonexistent)
        val product: BaseProduct = getProductInstance() ?: return

        //if DJI product is disconnected, alert the user
        if (!product.isConnected) {
            showToast(getString(R.string.disconnected))
        } else {
            /*
            if the DJI product is connected and the aircraft model is not unknown, add the
            receivedVideoDataListener to the primary video feed.
            */
            videoSurface.surfaceTextureListener = this
            if (product.model != Model.UNKNOWN_AIRCRAFT) {
                receivedVideoDataListener?.let {
                    VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(
                        it
                    )
                }
            }
        }
    }

    //Function that uninitializes the display for the videoSurface TextureView
    private fun uninitPreviewer() {
        // This method is called in onPause and onDestroy, ensure resources are released.
        // For video preview, clearing the listener is often sufficient.
        if (VideoFeeder.getInstance().primaryVideoFeed.listeners.contains(receivedVideoDataListener)) {
            receivedVideoDataListener?.let {
                VideoFeeder.getInstance().primaryVideoFeed.removeVideoDataListener(it)
            }
        }
        codecManager?.cleanSurface()
        codecManager = null
    }

    //Function that displays toast messages to the user
    private fun showToast(msg: String?) {
        runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    //When the MainActivity is created or resumed, initialize the video feed display
    override fun onResume() {
        super.onResume()
        initPreviewer()
    }

    //When the MainActivity is paused, clear the video feed display
    override fun onPause() {
        uninitPreviewer()
        super.onPause()
    }

    //When the MainActivity is destroyed, clear the video feed display
    override fun onDestroy() {
        uninitPreviewer()
        super.onDestroy()
    }

    //When a TextureView's SurfaceTexture is ready for use, use it to initialize the codecManager
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (codecManager == null) {
            codecManager = DJICodecManager(this, surface, width, height)
        }
    }

    //when a SurfaceTexture's size changes...
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    //when a SurfaceTexture is about to be destroyed, uninitialize the codedManager
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        codecManager?.cleanSurface()
        codecManager = null
        return false
    }

    //When a SurfaceTexture is updated...
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    //Handling what happens when certain layout views are clicked
    override fun onClick(v: View?) {
        when(v?.id) {
            //If the capture button is pressed, take a photo with the DJI product's camera
            R.id.btn_capture -> {
                takeScreenshotAndUpload() // 캡쳐 및 업로드 함수 호출
                sendWaypointData(currentDroneLatitude, currentDroneLongitude, currentDroneAltitude) // 드론 위치 전송 함수 호출
            }
            //If the shoot photo mode button is pressed, set camera to only take photos
            R.id.btn_shoot_photo_mode -> {
                switchCameraMode(CameraMode.SHOOT_PHOTO)
            }
            //If the record video mode button is pressed, set camera to only record videos
            R.id.btn_record_video_mode -> {
                switchCameraMode(CameraMode.RECORD_VIDEO)
            }
            else -> {}
        }
    }

    // Function to take a screenshot and upload it
    private fun takeScreenshotAndUpload() {
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = videoSurface.bitmap // Get bitmap from TextureView
            if (bitmap != null) {
                // 1. 공용 저장소에 저장
                val publicFileUri = saveBitmapToPublicStorage(bitmap)
                if (publicFileUri != null) {
                    withContext(Dispatchers.Main) {
                        showToast("스크린샷이 갤러리에 저장되었습니다.")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showToast("스크린샷 갤러리 저장 실패.")
                    }
                }

                // 2. 임시 파일 생성 후 S3 업로드
                val tempFile = createTempFileForUpload(bitmap)
                if (tempFile != null) {
                    uploadFile(tempFile)
                } else {
                    withContext(Dispatchers.Main) {
                        showToast("업로드용 임시 파일 생성 실패.")
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    showToast("비디오 화면에서 비트맵을 가져올 수 없습니다.")
                }
            }
        }
    }

    // 비트맵을 공용 저장소(Pictures 폴더)에 저장하는 함수
    private fun saveBitmapToPublicStorage(bitmap: Bitmap): String? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val filename = "DRONE_SCREENSHOT_$timestamp.png"
        var fos: OutputStream? = null
        var imageUri: String? = null

        try {
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/DJIFPV") // Pictures/DJIFPV 폴더에 저장
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                fos = resolver.openOutputStream(uri)
                if (fos != null) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    imageUri = uri.toString()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            fos?.close()
        }
        return imageUri
    }

    // S3 업로드용 임시 파일을 생성하는 함수
    private fun createTempFileForUpload(bitmap: Bitmap): File? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_temp", Locale.getDefault()).format(Date())
        val filename = "SCREENSHOT_$timestamp.png"
        val directory = cacheDir // 앱의 캐시 디렉터리에 임시 저장
        val file = File(directory, filename)

        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }


    // Function to upload the file to the server
    private suspend fun uploadFile(file: File) {
        val client = OkHttpClient()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("accessKey", ACCESS_KEY)
            .addFormDataPart("file", file.name, file.asRequestBody("image/png".toMediaTypeOrNull()))
            .build()

        val request = Request.Builder()
            .url("http://117.17.189.176:3000/upload/file") // HTTPS 미적용 상태이므로 HTTP 사용
            .post(requestBody)
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            withContext(Dispatchers.Main) {
                if (response.isSuccessful) {
                    val jsonResponse = responseBody?.let { JSONObject(it) }
                    val fileUrl = jsonResponse?.getString("file_url")
                    showToast("업로드 성공: $fileUrl")
                } else {
                    val errorMessage = try {
                        responseBody?.let { JSONObject(it).getString("message") } ?: "알 수 없는 오류"
                    } catch (e: Exception) {
                        "알 수 없는 오류"
                    }
                    showToast("업로드 실패: ${response.code} - $errorMessage")
                }
            }
        } catch (e: IOException) {
            withContext(Dispatchers.Main) {
                showToast("네트워크 오류: ${e.message}")
            }
            e.printStackTrace()
        } finally {
            file.delete() // 업로드 시도 후 임시 파일 삭제
        }
    }

    // Function to send drone waypoint data
    private fun sendWaypointData(latitude: Double, longitude: Double, altitude: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()

            val jsonBody = JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("altitude", altitude)
            }.toString()

            val requestBody = jsonBody.toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("http://3.37.127.247:8080/waypoint")
                .post(requestBody)
                .build()

            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        showToast("드론 위치 전송 성공: $jsonBody")
                    } else {
                        val errorMessage = responseBody ?: "알 수 없는 오류"
                        showToast("드론 위치 전송 실패: ${response.code} - $errorMessage")
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    showToast("드론 위치 전송 네트워크 오류: ${e.message}")
                }
                e.printStackTrace()
            }
        }
    }

    //Function for setting the camera mode. If the resulting callback returns an error that
    //is null, then the operation was successful.
    private fun switchCameraMode(cameraMode: CameraMode) {
        val camera: Camera = getCameraInstance() ?: return

        camera.setMode(cameraMode, object : CommonCallbacks.CompletionCallback<dji.common.error.DJIError> {
            override fun onResult(error: dji.common.error.DJIError?) {
                if (error == null) {
                    showToast("카메라 모드 전환 성공")
                } else {
                    showToast("카메라 모드 전환 오류: ${error.description}")
                }
            }
        })
    }

    /*
    Note:
    Depending on the DJI product, the mobile device is either connected directly to the drone,
    or it is connected to a remote controller (RC) which is then used to control the drone.
    */

    //Function used to get the DJI product that is directly connected to the mobile device
    private fun getProductInstance(): BaseProduct? {
        return DJISDKManager.getInstance().product
    }

    /*
    Function used to get an instance of the camera in use from the DJI product
    */
    private fun getCameraInstance(): Camera? {
        if (getProductInstance() == null) return null

        return when {
            getProductInstance() is Aircraft -> {
                (getProductInstance() as Aircraft).camera
            }
            getProductInstance() is HandHeld -> {
                (getProductInstance() as HandHeld).camera
            }
            else -> null
        }
    }

    //Function that returns True if a DJI aircraft is connected
    private fun isAircraftConnected(): Boolean {
        return getProductInstance() != null && getProductInstance() is Aircraft
    }

    //Function that returns True if a DJI product is connected
    private fun isProductModuleAvailable(): Boolean {
        return (getProductInstance() != null)
    }

    //Function that returns True if a DJI product's camera is available
    private fun isCameraModuleAvailable(): Boolean {
        return isProductModuleAvailable() && (getProductInstance()?.camera != null)
    }

    //Function that returns True if a DJI camera's playback feature is available
    private fun isPlaybackAvailable(): Boolean {
        return isCameraModuleAvailable() && (getProductInstance()?.camera?.playbackManager != null)
    }
}