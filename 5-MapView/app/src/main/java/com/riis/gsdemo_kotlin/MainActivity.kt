package com.riis.gsdemo_kotlin

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import dji.common.util.CommonCallbacks
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.products.Aircraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay // delay 함수를 사용하기 위해 추가
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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

/*
이 액티비티는 연결된 DJI 제품의 카메라에 접근하고,
사진을 촬영하거나 비디오를 녹화하는 인터페이스를 제공합니다.
*/
class CameraActivity : AppCompatActivity(), TextureView.SurfaceTextureListener, View.OnClickListener {

    // 클래스 변수
    private val TAG = CameraActivity::class.java.name

    // 연결된 DJI 제품으로부터 비디오 데이터를 수신하는 리스너
    private var receivedVideoDataListener: VideoFeeder.VideoDataListener? = null
    private var codecManager: DJICodecManager? = null

    private lateinit var videoSurface: TextureView
    private lateinit var captureBtn: Button
    private lateinit var shootPhotoModeBtn: Button
    private lateinit var recordVideoModeBtn: Button
    private lateinit var recordBtn: ToggleButton
    private lateinit var recordingTime: TextView

    // 오버레이 텍스트 뷰
    private lateinit var latitudeTextView: TextView
    private lateinit var longitudeTextView: TextView
    private lateinit var altitudeTextView: TextView
    private lateinit var headingTextView: TextView // 헤딩(방향) TextView

    // 드론 현재 위치 저장 변수
    private var currentDroneLatitude: Double = 0.0
    private var currentDroneLongitude: Double = 0.0
    private var currentDroneAltitude: Double = 0.0
    private var currentDroneHeading: Float = 0f // 드론의 현재 헤딩(방향) 저장 변수

    // 새 버튼 선언
    private lateinit var openWaypoint1Button: Button

    // 카메라 상태를 저장하는 변수들 추가
    private var isStoringPhoto: Boolean = false
    private var isRecording: Boolean = false

    // 비디오 스트림의 원래 종횡비 (예: 16:9 또는 4:3)
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0


    // 액티비티 생성
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_camera) // activity_camera.xml 레이아웃을 액티비티 뷰로 설정
        initUi() // UI 초기화

        receivedVideoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            codecManager?.sendDataToDecoder(videoBuffer, size)
            // 비디오 프레임 크기 정보를 받아서 videoWidth, videoHeight 업데이트
            if (videoWidth == 0 || videoHeight == 0) {
                videoWidth = codecManager?.videoWidth ?: 0
                videoHeight = codecManager?.videoHeight ?: 0
                if (videoWidth > 0 && videoHeight > 0) {
                    runOnUiThread {
                        adjustTextureViewAspect(videoWidth, videoHeight)
                    }
                }
            }
        }

        DJIDemoApplication.getCameraInstance()?.let { camera ->
            camera.setSystemStateCallback { systemState ->
                systemState?.let { state ->
                    val recordTime = state.currentVideoRecordingTimeInSeconds
                    val minutes = (recordTime % 3600) / 60
                    val seconds = recordTime % 60
                    val timeString = String.format("%02d:%02d", minutes, seconds)

                    // 카메라 상태 업데이트
                    isStoringPhoto = state.isStoringPhoto
                    isRecording = state.isRecording

                    runOnUiThread {
                        recordingTime.text = timeString
                        if (state.isRecording) {
                            recordingTime.visibility = View.VISIBLE
                        } else {
                            recordingTime.visibility = View.INVISIBLE
                        }
                    }
                }
            }
        }

        val aircraft = DJIDemoApplication.getProductInstance() as? Aircraft
        aircraft?.flightController?.setStateCallback { flightControllerState ->
            val location = flightControllerState.aircraftLocation
            // 시뮬레이터 사용 시 altitude가 float이 아닐 수 있어 toFloat() 추가 또는 타입 명시
            val altitude = location?.altitude?.toDouble() ?: 0.0
            currentDroneHeading = flightControllerState.aircraftHeadDirection.toFloat()

            runOnUiThread {
                if (location != null && location.latitude != -1.0 && location.longitude != -1.0) {
                    currentDroneLatitude = location.latitude
                    currentDroneLongitude = location.longitude
                    currentDroneAltitude = altitude

                    latitudeTextView.text = getString(R.string.latitude_label, location.latitude)
                    longitudeTextView.text = getString(R.string.longitude_label, location.longitude)
                } else {
                    currentDroneLatitude = 0.0
                    currentDroneLongitude = 0.0
                    currentDroneAltitude = 0.0

                    latitudeTextView.text = getString(R.string.latitude_label, 0.0)
                    longitudeTextView.text = getString(R.string.longitude_label, 0.0)
                }
                altitudeTextView.text = getString(R.string.altitude_label, altitude)
                headingTextView.text = getString(R.string.heading_label, currentDroneHeading)
            }
        }
    }

    private fun initUi() {
        videoSurface = findViewById(R.id.video_previewer_surface)
        recordingTime = findViewById(R.id.timer)
        captureBtn = findViewById(R.id.btn_capture)
        recordBtn = findViewById(R.id.btn_record)
        shootPhotoModeBtn = findViewById(R.id.btn_shoot_photo_mode)
        recordVideoModeBtn = findViewById(R.id.btn_record_video_mode)

        latitudeTextView = findViewById(R.id.latitude_text_view)
        longitudeTextView = findViewById(R.id.longitude_text_view)
        altitudeTextView = findViewById(R.id.altitude_text_view)
        headingTextView = findViewById(R.id.heading_text_view)

        videoSurface.surfaceTextureListener = this

        captureBtn.setOnClickListener(this)
        shootPhotoModeBtn.setOnClickListener(this)
        recordVideoModeBtn.setOnClickListener(this)

        recordingTime.visibility = View.INVISIBLE

        recordBtn.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startRecord()
            } else {
                stopRecord()
            }
        }

        openWaypoint1Button = findViewById(R.id.btn_open_waypoint1)
        openWaypoint1Button.setOnClickListener(this)
    }

    private fun startRecord() {
        val camera = DJIDemoApplication.getCameraInstance() ?:return
        camera.startRecordVideo {
            if (it == null) {
                showToast("비디오 녹화 시작: 성공")
            } else {
                showToast("비디오 녹화 시작 오류: ${it.description}")
            }
        }
    }

    private fun stopRecord() {
        val camera = DJIDemoApplication.getCameraInstance() ?: return
        camera.stopRecordVideo {
            if (it == null) {
                showToast("비디오 녹화 중지: 성공")
            } else {
                showToast("비디오 녹화 중지: 오류 ${it.description}")
            }
        }
    }

    private fun initPreviewer() {
        val product: BaseProduct = DJIDemoApplication.getProductInstance() ?: return
        if (!product.isConnected) {
            showToast(getString(R.string.disconnected))
        } else {
            videoSurface.surfaceTextureListener = this
            if (product.model != dji.common.product.Model.UNKNOWN_AIRCRAFT) {
                receivedVideoDataListener?.let {
                    VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(it)
                }
            }
        }
    }

    private fun uninitPreviewer() {
        if (VideoFeeder.getInstance().primaryVideoFeed.listeners.contains(receivedVideoDataListener)) {
            receivedVideoDataListener?.let {
                VideoFeeder.getInstance().primaryVideoFeed.removeVideoDataListener(it)
            }
        }
        codecManager?.cleanSurface()
        codecManager = null
    }

    private fun showToast(msg: String?) {
        runOnUiThread { Toast.makeText(this@CameraActivity, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onResume() {
        super.onResume()
        initPreviewer()
    }

    override fun onPause() {
        uninitPreviewer()
        super.onPause()
    }

    override fun onDestroy() {
        uninitPreviewer()
        super.onDestroy()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (codecManager == null) {
            codecManager = DJICodecManager(this, surface, width, height)
        }
        // SurfaceTexture가 사용 가능해졌을 때 초기 비디오 크기를 알 수 없으므로,
        // 이곳에서는 먼저 Surface의 크기를 설정하고, 실제 비디오 프레임이 오면 다시 조정합니다.
        // 초기 렌더링을 위해 현재 Surface의 크기를 사용합니다.
        adjustTextureViewAspect(width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // TextureView의 크기가 변경될 때마다 비디오 종횡비를 다시 조정합니다.
        // 실제 비디오 크기를 알고 있다면 그 값을 사용하고, 모른다면 현재 Surface 크기를 사용합니다.
        val targetWidth = if (videoWidth > 0) videoWidth else width
        val targetHeight = if (videoHeight > 0) videoHeight else height
        adjustTextureViewAspect(targetWidth, targetHeight)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        codecManager?.cleanSurface()
        codecManager = null
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    // 비디오 종횡비를 기준으로 TextureView의 변환을 조정하는 함수
    private fun adjustTextureViewAspect(videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0) return

        val viewWidth = videoSurface.width.toFloat()
        val viewHeight = videoSurface.height.toFloat()

        if (viewWidth == 0f || viewHeight == 0f) return

        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val screenRatio = viewWidth / viewHeight

        val matrix = Matrix()
        val scaleX: Float
        val scaleY: Float

        if (videoRatio > screenRatio) {
            // 영상이 화면보다 가로로 더 넓은 경우 (세로에 맞춰 조절)
            scaleX = videoRatio / screenRatio
            scaleY = 1f
        } else {
            // 영상이 화면보다 세로로 더 긴 경우 (가로에 맞춰 조절)
            scaleX = 1f
            scaleY = screenRatio / videoRatio
        }

        // 스케일링된 영상을 TextureView 중앙에 위치시키기 위한 변환
        matrix.setScale(scaleX, scaleY, viewWidth / 2, viewHeight / 2)
        videoSurface.setTransform(matrix)
    }

    override fun onClick(v: View?) {
        when(v?.id) {
            R.id.btn_capture -> {
                // 캡처 버튼 누르면 화면 캡처 및 위치 전송
                captureScreenAndUpload()
                sendWaypointToServer() // 위치 정보 전송
            }
            R.id.btn_shoot_photo_mode -> {
                switchCameraMode(CameraMode.SHOOT_PHOTO)
            }
            R.id.btn_record_video_mode -> {
                switchCameraMode(CameraMode.RECORD_VIDEO)
            }
            R.id.btn_open_waypoint1 -> {
                val intent = Intent(this, Waypoint1Activity::class.java)
                startActivity(intent)
            }
            else -> {}
        }
    }

    // 드론 화면 캡처 및 업로드하는 함수 (간단 버전)
    private fun captureScreenAndUpload() {
        try {
            // TextureView에서 비트맵 캡처
            val bitmap = videoSurface.getBitmap()
            if (bitmap != null) {
                showToast("화면 캡처 성공! 저장 및 업로드 중...")

                lifecycleScope.launch(Dispatchers.IO) {
                    saveBitmapAndUpload(bitmap)
                }
            } else {
                showToast("화면 캡처 실패. 영상이 표시되고 있는지 확인해주세요.")
            }
        } catch (e: Exception) {
            showToast("화면 캡처 중 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    // 비트맵을 갤러리에 저장하는 함수
    private suspend fun saveBitmapToGallery(bitmap: Bitmap): Boolean {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "drone_screen_$timeStamp.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10 (API 29) 이상에서는 MediaStore 사용
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DroneCaptures")
                }

                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let { imageUri ->
                    val outputStream: OutputStream? = contentResolver.openOutputStream(imageUri)
                    outputStream?.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    }
                    true
                } ?: false
            } else {
                // Android 9 (API 28) 이하에서는 직접 파일 저장
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val droneDir = File(picturesDir, "DroneCaptures")
                if (!droneDir.exists()) {
                    droneDir.mkdirs()
                }

                val imageFile = File(droneDir, fileName)
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }

                // 갤러리에 파일 추가 알림
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, imageFile.absolutePath)
                    put(MediaStore.Images.Media.TITLE, fileName)
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                    put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 비트맵을 파일로 저장하고 서버에 업로드
    private suspend fun saveBitmapAndUpload(bitmap: Bitmap) {
        try {
            // 1. 갤러리에 저장
            val gallerySaved = saveBitmapToGallery(bitmap)

            withContext(Dispatchers.Main) {
                if (gallerySaved) {
                    showToast("갤러리에 저장 완료! 서버 업로드 중...")
                } else {
                    showToast("갤러리 저장 실패. 서버 업로드는 계속 진행...")
                }
            }

            // 2. 임시 파일 생성 (서버 업로드용)
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "drone_screen_$timeStamp.jpg"
            val tempFile = File(cacheDir, fileName)

            // 비트맵을 JPEG 파일로 저장
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) // 85% 품질
            }

            // 3. 서버에 업로드
            uploadPhotoToServer(tempFile)

        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                showToast("이미지 저장 및 업로드 중 오류: ${e.message}")
            }
            e.printStackTrace()
        }
    }

    // 서버에 사진 업로드하는 함수 (GPS 데이터 포함 안함)
    private suspend fun uploadPhotoToServer(imageFile: File) {
        try {
            val client = OkHttpClient()

            // Multipart 요청 생성
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", // curl 요청과 동일하게 'file'로 변경
                    imageFile.name,
                    imageFile.asRequestBody("image/png".toMediaTypeOrNull()) // curl 요청과 동일하게 'image/png'로 변경
                )
                .build()

            val request = Request.Builder()
                .url("http://3.37.127.247:8080/upload/file?accessKey=test1234&domain=arc") // 요청 URL 변경
                .post(requestBody)
                .addHeader("accept", "*/*")
                .addHeader("Content-Type", "multipart/form-data")
                .build()

            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }

            val responseBody = response.body?.string()

            withContext(Dispatchers.Main) {
                if (response.isSuccessful) {
                    showToast("드론 이미지 업로드 성공!")
                } else {
                    val errorMessage = responseBody ?: "알 수 없는 오류"
                    showToast("이미지 업로드 실패: ${response.code} - $errorMessage")
                }
            }

            // 임시 파일 삭제
            imageFile.delete()

        } catch (e: IOException) {
            withContext(Dispatchers.Main) {
                showToast("이미지 업로드 네트워크 오류: ${e.message}")
            }
            e.printStackTrace()
            // 오류 발생 시에도 임시 파일 삭제
            imageFile.delete()
        }
    }

    // 서버에 웨이포인트(GPS) 정보 전송하는 함수
    private fun sendWaypointToServer() {
        // 잘못된 위치 값 (0.0, 0.0) 및 NaN 값 전송 방지
        if (currentDroneLatitude == 0.0 && currentDroneLongitude == 0.0) {
            showToast("위치 정보를 전송할 수 없습니다. GPS를 확인해주세요.")
            return
        }

        // NaN 값 체크
        if (currentDroneLatitude.isNaN() || currentDroneLongitude.isNaN() ||
            currentDroneAltitude.isNaN() || currentDroneHeading.isNaN()) {
            showToast("유효하지 않은 위치 정보입니다. GPS를 확인해주세요.")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val json = JSONObject().apply {
                    put("latitude", currentDroneLatitude)
                    put("longitude", currentDroneLongitude)
                    put("altitude", currentDroneAltitude)
                    put("sequence", 0) // 필요에 따라 시퀀스 값 설정
                    put("heading", currentDroneHeading)
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
                        showToast("GPS 위치 전송 성공!")
                    } else {
                        val errorMessage = responseBody ?: "알 수 없는 오류"
                        showToast("GPS 위치 전송 실패: ${response.code} - $errorMessage")
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    showToast("GPS 위치 전송 네트워크 오류: ${e.message}")
                }
                e.printStackTrace()
            }
        }
    }

    // 카메라 모드 전환 함수에 콜백 추가
    private fun switchCameraMode(cameraMode: CameraMode, callback: (Boolean) -> Unit = {}) {
        val camera: Camera = DJIDemoApplication.getCameraInstance() ?: run {
            showToast("카메라에 연결할 수 없습니다.")
            callback(false)
            return
        }
        camera.setMode(cameraMode) { error ->
            if (error == null) {
                showToast("카메라 모드 전환 성공: $cameraMode")
                callback(true)
            } else {
                showToast("카메라 모드 전환 오류: ${error.description}")
                callback(false)
            }
        }
    }
}