package com.example.test_camera2

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import androidx.camera.core.MeteringPoint
import androidx.constraintlayout.widget.ConstraintSet.Motion
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.fragment.app.Fragment
import com.example.test_camera2.CameraHelper.AutoFitTexutreView
import com.example.test_camera2.CameraHelper.CompareSizesByArea
import com.example.test_camera2.CameraHelper.FocusTool
import com.example.test_camera2.CameraHelper.ImageSaver
import com.example.test_camera2.databinding.FragmentCameraBinding
import com.example.test_camera2.ml.LiteModelEfficientdetLite0DetectionMetadata1
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


class CameraFragment : Fragment(),
    ActivityCompat.OnRequestPermissionsResultCallback{

    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a [TextureView].
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        // SurfaceTexture가 생성되고 준비 되었을 때
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            Log.v(TAG, "[onSurfaceTextureAvailable] TextureView width : ${textureView.width} x height : ${textureView.height}")
            openCamera(width, height)
        }
        // i) 사용자가 화면 방향을 바꿨을 때
        // ii) TextureView 크기가 변경됐을 때
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            Log.v(TAG, "[onSurfaceTextureSizeChanged] TextureView width : ${textureView.width} x height : ${textureView.height}")
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            // Object Detection 시작
            if(startObjectDetection){
                imageView.visibility = View.VISIBLE
                bitmap = textureView.bitmap!!

                // Creates inputs for reference.
                var image = TensorImage.fromBitmap(bitmap)
                image = imageProcessor.process(image)

                // Runs model inference and gets result.
                val outputs = model.process(image)

                // Gets result from DetectionResult.
                locations = outputs.locationAsTensorBuffer.floatArray
                val categories = outputs.categoryAsTensorBuffer.floatArray
                scores = outputs.scoreAsTensorBuffer.floatArray
                val numberOfDetections = outputs.numberOfDetectionsAsTensorBuffer.floatArray

                var mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(mutable)
                val path = Path()

                val h = mutable.height
                val w = mutable.width

                val rectLength = 120

                paint.textSize = h/15f
                paint.strokeWidth = h/250f
                var x = 0
                var detectionCount = 0
                scores.forEachIndexed{ index, fl ->
                    x = index
                    x *= 4
                    if(fl > OBJECT_DETECT_PERCENTAGE) {
                        if(detectionCount >= OBJECT_DETECT_SIZE) return
                        detectionCount += 1
                        paint.setColor(ContextCompat.getColor(requireContext(), R.color.focus))
                        paint.style = Paint.Style.STROKE

                        val centerX = locations.get(x+1)*w + (locations.get(x+3)*w - locations.get(x+1)*w)/2
                        val centerY = locations.get(x)*h + (locations.get(x+2)*h - locations.get(x)*h)/2

                        path.moveTo(centerX - rectLength/2 - paint.strokeWidth/2, centerY - rectLength/2)
                        path.lineTo(centerX - rectLength/2 + rectLength/3, centerY - rectLength/2)
                        path.moveTo(centerX - rectLength/2 + 2*(rectLength/3), centerY - rectLength/2)
                        path.lineTo(centerX + rectLength/2, centerY - rectLength/2)

                        path.lineTo(centerX + rectLength/2, centerY - rectLength/2 + rectLength/3)
                        path.moveTo(centerX + rectLength/2, centerY - rectLength/2 + 2*(rectLength/3))
                        path.lineTo(centerX + rectLength/2, centerY + rectLength/2)

                        path.lineTo(centerX + rectLength/2 - rectLength/3, centerY + rectLength/2)
                        path.moveTo(centerX + rectLength/2 - 2*(rectLength/3), centerY + rectLength/2)
                        path.lineTo(centerX - rectLength/2, centerY + rectLength/2)

                        path.lineTo(centerX - rectLength/2, centerY + rectLength/2 - rectLength/3)
                        path.moveTo(centerX - rectLength/2, centerY + rectLength/2 - 2*(rectLength/3))
                        path.lineTo(centerX - rectLength/2, centerY - rectLength/2)

                        path.close()
                        canvas.drawPath(path, paint)
//                        canvas.drawRect(RectF(locations.get(x+1)*w, locations.get(x)*h, locations.get(x+3)*w, locations.get(x+2)*h),paint)

                    }
                }
                imageView.setImageBitmap(mutable)
            }

          }

    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener {
        Log.v("chaewon", "onImageAvailableListener")
        backgroundHandler?.post(ImageSaver(it.acquireNextImage(), requireContext()))
//        ImageSaver(it.acquireNextImage(), requireContext()).run()
    }


    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@CameraFragment.cameraDevice = cameraDevice
            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraFragment.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            this@CameraFragment.activity?.finish()
        }

    }

    /**
     * A [CameraCaptureSession.CaptureCallback] that handles events related to JPEG capture.
     */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {

        private fun process(result: CaptureResult) {
            when (state) {
                STATE_PREVIEW -> Unit // Do nothing when the camera preview is working normally.
                STATE_WAITING_LOCK -> capturePicture(result)
                STATE_WAITING_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        state = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        state = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        private fun capturePicture(result: CaptureResult) {
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            Log.v("chaewon", "$afState check")
            if (afState == null) {
                captureStillPicture()
            } else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                || afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) {
                // CONTROL_AE_STATE can be null on some devices
                Log.v("chaewon", "지나가니 $afState")
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                    state = STATE_PICTURE_TAKEN
                    Log.v(TAG, "state : ${state}")
                    captureStillPicture()
                } else {
                    runPrecaptureSequence()
                }
            }
//            else if (afState == CaptureResult.CONTROL_AF_STATE_INACTIVE){
//                captureStillPicture()
//            }
        }

        override fun onCaptureProgressed(session: CameraCaptureSession,
                                         request: CaptureRequest,
                                         partialResult: CaptureResult) {
            process(partialResult)
        }

        override fun onCaptureCompleted(session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult) {
            process(result)
        }

    }

    lateinit var activity :MainActivity

    private lateinit var binding : FragmentCameraBinding

    private var imageReader: ImageReader? = null

    private var sensorOrientation = 0

    private lateinit var previewSize: Size

    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    private var flashSupported = false

    lateinit var cameraId: String

    private val cameraOpenCloseLock = Semaphore(1)

    private var cameraDevice: CameraDevice? = null

    var captureSession: CameraCaptureSession? = null

    private lateinit var previewRequest: CaptureRequest

    private var state = STATE_PREVIEW

    private lateinit var mediaPlayer : MediaPlayer

    private var minimumFocusDistance : Float = 0F

    private var hyperFocalDistance : Float = 0F

    private var checkedRadioBtnIndex : Int = 0

    private lateinit var shutterBtnRotation: ObjectAnimator

    private lateinit var modeRadioGroup : RadioGroup

    private lateinit var distanceOptionRadioGroup: RadioGroup

    lateinit var imageView:ImageView
    lateinit var bitmap : Bitmap
    lateinit var model : LiteModelEfficientdetLite0DetectionMetadata1
    lateinit var imageProcessor: ImageProcessor
    val paint = Paint()

    private var isSingle = false
    private var isBurst = false
    private var isObjectFocus = false
    private var isDistanceFocus = false
    private var isAutoRewind = false

    private var startObjectDetection = false

    private var manualFocusEngaged = false

    data class CenterPoint(val centerX: Float, val centerY: Float)
    private val detectionCenterPointList = ArrayList<CenterPoint>()
    private lateinit var locations : FloatArray
    private lateinit var scores : FloatArray
    private lateinit var FocusTool: FocusTool
    private var isFocusSuccessful = false
    private var detectionIndex = 0


    /**
     * 백그라운드에서 카메라 관련 작업을 하는 스레드와 핸들러
     */
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    lateinit var textureView : AutoFitTexutreView

    override fun onAttach(context: Context) {
        super.onAttach(context)
        activity = context as MainActivity
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentCameraBinding.inflate(inflater, container, false)

        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 초기화
        textureView = binding.textureView
        modeRadioGroup = binding.modeRadioGroup
        distanceOptionRadioGroup = binding.distanceOptionRadioGroup
        mediaPlayer = MediaPlayer.create(context, R.raw.end_sound)
        imageView = binding.imageView
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(320, 320, ResizeOp.ResizeMethod.BILINEAR)).build()
        model = LiteModelEfficientdetLite0DetectionMetadata1.newInstance(requireContext())
        FocusTool = FocusTool(this@CameraFragment)

        imageView.visibility = View.GONE

        // shutter btn 애니메이션
        binding.shutterBtn.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.shutterBtn.viewTreeObserver.removeOnGlobalLayoutListener(this)

                shutterBtnRotation = ObjectAnimator.ofFloat(binding.shutterBtn, View.ROTATION, 0f, 360f)
                shutterBtnRotation.apply {
                    duration = 1000 // 애니메이션 시간 (밀리초)
                    interpolator = AccelerateDecelerateInterpolator() // 가속도 감속도 애니메이션 인터폴레이터
                    repeatCount = ObjectAnimator.INFINITE // 애니메이션 반복 횟수 (INFINITE: 무한반복)
                    repeatMode = ObjectAnimator.RESTART // 애니메이션 반복 모드 (RESTART: 처음부터 다시 시작)
                }
            }
        })

        // shutter Btn 눌렸을 때
        binding.shutterBtn.setOnClickListener {
            shutterBtnRotation.start()
            binding.shutterBtn.isEnabled = false // shutter Btn 비활성화

            // Basic Btn 모드 촬영
            if(binding.basicRadioBtn.isChecked){

                // Single 모드 촬영
                if(!binding.basicToggleBtn.isChecked) {
                    isSingle = true
                    lockFocus()
                } else { // Burst 모드 촬영
                    isBurst = true
                    lockFocus()
                }
            }
            // Object Focus 모드 촬영
            else if(binding.objectFocusRadioBtn.isChecked){
                detectionIndex = 0
                detectionCenterPointList.clear()

                var mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val h = mutable.height
                val w = mutable.width

                var x = 0
                var detectionCount = 0
                scores.forEachIndexed { index, fl ->
                    x = index
                    x *= 4
                    if (fl > OBJECT_DETECT_PERCENTAGE) {
                        if (detectionCount >= OBJECT_DETECT_SIZE) return@forEachIndexed
                        detectionCount += 1

                        val centerX =
                            locations.get(x + 1) * w + (locations.get(x + 3) * w - locations.get(x + 1) * w) / 2
                        val centerY =
                            locations.get(x) * h + (locations.get(x + 2) * h - locations.get(x) * h) / 2

                        // 감지된 객체의 중앙 좌표 저장
                        detectionCenterPointList.add(CenterPoint(centerX, centerY))
                    }
                }

                takeObjectFocus()

            }
            //Distance Focus 모드 촬영
            else if(binding.distanceFocusRadioBtn.isChecked){
                isDistanceFocus = true
                // 수동
                if (binding.distanceManualRadioBtn.isChecked){
                    isSingle = true
                    lockFocus()
                } else { // 자동
                    // TODO : 자동 모드 촬영

                }

            }

        } // end of shutterBtn.setOnClickListener...

        // 카메라 촬영 모드
        modeRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            checkedRadioBtnIndex = modeRadioGroup.indexOfChild(modeRadioGroup.findViewById<RadioButton>(checkedId))

            // 선택된 라디오 버튼 Bold
            for (i in 0 until modeRadioGroup.childCount){
                val radioBtn = modeRadioGroup.getChildAt(i) as RadioButton
                if (radioBtn.id == checkedId){
                    radioBtn.setTypeface(null, Typeface.BOLD)
                } else {
                    radioBtn.setTypeface(null, Typeface.NORMAL)
                }
            }

            when(checkedId){
                R.id.basicRadioBtn ->
                    showRelatedView(true, false, false)
                R.id.objectFocusRadioBtn ->
                    showRelatedView(false, true, false)
                R.id.distanceFocusRadioBtn -> {
                    showRelatedView(false, false, true)
                    binding.distanceManualRadioBtn.isChecked = true
                }
                R.id.autoRewindRadioBtn ->
                    showRelatedView(false, false, false)
            }
        } // end of modeRadioGroup.setOnCheckedChangeListener...

        // distance 수동, 자동 Btn
        distanceOptionRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            // TODO : Manual과 Auto에 따라 seekbar 유무 + tv 색상 변경
            when(checkedId){
                // 수동
                R.id.distanceManualRadioBtn -> {
                    binding.seekBarLinearLayout.visibility = View.VISIBLE
                    binding.distanceManualTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                    binding.distanceAutoTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.middle_gray_80))
                }
                // 자동
                R.id.distanceAutoRadioBtn -> {
                    binding.seekBarLinearLayout.visibility = View.GONE
                    binding.distanceManualTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.middle_gray_80))
                    binding.distanceAutoTv.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                }
            }
        }

        // distance 수동 Seek Bar 조절
        binding.distanceFocusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Log.v(TAG, "[setOnSeekBarChangeListener] progress = ${progress}, fromUser = ${fromUser}")
                val focusDistance = (minimumFocusDistance/binding.distanceFocusSeekBar.max.toFloat())*progress.toFloat()
                Log.v(TAG, "[setOnSeekBarChangeListener] focusDistance = ${focusDistance}")
                updatePreview(focusDistance)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        @Suppress("ClickableViewAccessibility")
        textureView.setOnTouchListener { view: View, motionEvent: MotionEvent ->
            Log.v(TAG, "[textureView.setOnTouchListener] TOUCH!!!!!")
            val actionMasked = motionEvent.actionMasked
            if (actionMasked != MotionEvent.ACTION_DOWN)
                return@setOnTouchListener false

            if (manualFocusEngaged)
                return@setOnTouchListener true

            val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(cameraId)

            val focusAreaTouch = FocusTool.convertPointToMeteringRect(motionEvent.x, motionEvent.y)

            Log.v(TAG, "[setOnTouchListener] focusAreaTouch X x Y : ${focusAreaTouch.x} x ${focusAreaTouch.y}")

            val captureCallbackHandler = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    manualFocusEngaged = false
                    if (request.tag == "FOCUS_TAG") {
                        // the focus trigger is complete -
                        // resume repeating (preview surface will get frames), clear AF trigger
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
                        captureSession!!.setRepeatingRequest(previewRequestBuilder.build(), captureCallback, backgroundHandler)
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    super.onCaptureFailed(session, request, failure)
                    Log.e(TAG, "Manual AF failure: $failure")
                    manualFocusEngaged = false
                }
            }

            // first stop the existing repeating request
            captureSession!!.stopRepeating()

            // cancel any existing AF trigger (repeated touches, etc.)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            captureSession!!.capture(previewRequestBuilder.build(), captureCallbackHandler, backgroundHandler)

            // Now add a new AF trigger with focus region
            if (isMeteringAreaAFSupported(characteristics)) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusAreaTouch))
            }
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            previewRequestBuilder.setTag("FOCUS_TAG") // we'll capture this later for resuming the preview

            // then we ask for a single request (not repeating!)
            captureSession!!.capture(previewRequestBuilder.build(), captureCallbackHandler, backgroundHandler)
            manualFocusEngaged = true

            return@setOnTouchListener true
        }
    }

    override fun onStart() {
        super.onStart()
        Log.v(TAG, "[onStart] !!")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        // textureView가 사용 가능 여부
        if(textureView.isAvailable){ // textureView 적절히 생성 O
            Log.v(TAG, "[onResume] TextureView width : ${textureView.width} x height : ${textureView.height}")
            openCamera(textureView.width, textureView.height)
        } else { // textureView 아직 생성 X
            textureView.surfaceTextureListener = surfaceTextureListener
        }

        // SharedPreferences 객체를 가져옵니다.
        val sharedPref = activity?.getPreferences(Context.MODE_PRIVATE)

        // 저장된 값을 가져옵니다.
        val checkedRadioBtnIndexShare = sharedPref?.getInt("checkedRadioBtnIndex", 0)

        for (i in 0 until modeRadioGroup.childCount){
            val radioBtn = modeRadioGroup.getChildAt(i) as RadioButton
            if (i == checkedRadioBtnIndexShare){
                radioBtn.isChecked = true
                radioBtn.setTypeface(null, Typeface.BOLD)
                break
            }
        }



    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()

        // SharedPreferences 객체를 가져옵니다.
        val sharedPref = activity?.getPreferences(Context.MODE_PRIVATE)

        // SharedPreferences.Editor를 사용하여 상태 값을 저장합니다.
        with (sharedPref?.edit()) {
            this?.putInt("checkedRadioBtnIndex", checkedRadioBtnIndex)
            this?.apply()
        }

        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        model.close()
    }

    private fun isMeteringAreaAFSupported(characteristics: CameraCharacteristics): Boolean {
        return characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)!! >= 1
    }

    private fun takeObjectFocus(){
        if(detectionIndex == detectionCenterPointList.size) {
            unlockFocus()
            return
        }
            isObjectFocus = true
            val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(cameraId)

            Log.v("chaewon", "for 2 isObjectFocus : $isObjectFocus")
            Log.v("chaewon", "for 3 state : $state")

            val x = detectionCenterPointList[detectionIndex].centerX
            val y = detectionCenterPointList[detectionIndex].centerY
            val focusAreaTouch = FocusTool.convertPointToMeteringRect(x, y)

            val captureCallbackHandler = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)

                    if (request.tag == "FOCUS_TAG") {
                        // the focus trigger is complete -
                        // resume repeating (preview surface will get frames), clear AF trigger
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
                        captureSession!!.setRepeatingRequest(
                            previewRequestBuilder.build(),
                            captureCallback,
                            backgroundHandler
                        )

                        Log.v("chaewon", "complete")
                        lockFocus()

                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    super.onCaptureFailed(session, request, failure)
                    Log.e(TAG, "Manual AF failure: $failure")
                }
            }

            // first stop the existing repeating request
            captureSession!!.stopRepeating()

//                 cancel any existing AF trigger (repeated touches, etc.)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            captureSession!!.capture(previewRequestBuilder.build(), captureCallbackHandler, backgroundHandler)

            // Now add a new AF trigger with focus region
            if (isMeteringAreaAFSupported(characteristics)) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusAreaTouch))
            }
            captureSession!!.setRepeatingRequest(previewRequestBuilder.build(), captureCallbackHandler, backgroundHandler)



            // Now add a new AF trigger with focus region
            if (isMeteringAreaAFSupported(characteristics)) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusAreaTouch))
            }
            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            previewRequestBuilder.setTag("FOCUS_TAG") // we'll capture this later for resuming the preview

            Log.v("chaewon", "초점 !!!!!!!!!!!!!!!!!!!!")
            captureSession!!.capture(previewRequestBuilder.build(), captureCallbackHandler, backgroundHandler)

//        }

    }


    private fun showRelatedView(basic: Boolean, objectF: Boolean, distanceF: Boolean){
        showBasicRelatedView(basic)
        showObjectRelatedView(objectF)
        showDistanceRelatedView(distanceF)
    }
    // Basic 모드일 때, 관련 뷰 띄우기
    private fun showBasicRelatedView(isClick: Boolean){
        if (isClick) binding.basicToggleBtn.visibility = View.VISIBLE
        else binding.basicToggleBtn.visibility = View.GONE
    }
    // Distance Focus 모드일 때, 관련 뷰 띄우기
    private fun showDistanceRelatedView(isClick: Boolean){
        if (isClick) {
            binding.distanceOptionLinearLayout.visibility = View.VISIBLE
            binding.seekBarLinearLayout.visibility = View.VISIBLE
        } else {
            binding.distanceOptionLinearLayout.visibility = View.GONE
            binding.seekBarLinearLayout.visibility = View.GONE
        }
    }
    // Object Focus 모드일 때, 관련 뷰 띄우기
    private fun showObjectRelatedView(isClick: Boolean) {
        if (isClick) {
            startObjectDetection = true
            imageView.visibility = View.VISIBLE
        } else {
            startObjectDetection = false
            imageView.visibility = View.GONE
        }
    }

    // 프리뷰 업데이트 메서드
    private fun updatePreview(focusDistance: Float) {
        try {
            // CaptureRequest 객체 생성
            val previewBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            // 렌즈 초점 거리 지정
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            previewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)

            val texture = textureView.surfaceTexture
            texture?.setDefaultBufferSize(previewSize.width, previewSize.height)
            Log.v(TAG, "[createCameraPreviewSession] previewSize width x height = ${previewSize.width} x ${previewSize.height}")
            val surface = Surface(texture)

            // Surface 설정
            previewBuilder.addTarget(surface)

            // 반복적인 캡처 요청
            captureSession!!.setRepeatingRequest(previewBuilder.build(), captureCallback, backgroundHandler)

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun checkPermissions() {
        Log.v(TAG, "[checkPermissions] checkPermissions() 진입")
        //거절되었거나 아직 수락하지 않은 권한(퍼미션)을 저장할 문자열 배열 리스트
        var rejectedPermissionList = ArrayList<String>()

        //필요한 퍼미션들을 하나씩 끄집어내서 현재 권한을 받았는지 체크
        for(permission in REQUIRED_PERMISSIONS){
            if(checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                //만약 권한이 없다면 rejectedPermissionList에 추가
                rejectedPermissionList.add(permission)
            }
        }
        //거절된 퍼미션이 있다면...
        if(rejectedPermissionList.isNotEmpty()){
            //권한 요청!
            val array = arrayOfNulls<String>(rejectedPermissionList.size)
            ActivityCompat.requestPermissions(activity, rejectedPermissionList.toArray(array), REQUEST_PERMISSION)
        }
    }

    /**
     * startBackgroundThread()
     *          - 카메라 관련 작업을 하기 위한 스레드와 핸들러 생성
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper!!)
    }

    /**
     * stopBackgroundThread()
     */
    private fun stopBackgroundThread(){
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException){
            Log.e(TAG, e.toString())
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in [.captureCallback] from [.lockFocus].
     */
    private fun runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            // Tell #captureCallback to wait for the precapture sequence to be set.
            state = STATE_WAITING_PRECAPTURE
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    private fun captureStillPicture() {
        Log.v("chaewon", "${detectionIndex} captureStillPicture() 진입")
        Log.v("chaewon", "가")
        try {
            if (activity == null || cameraDevice == null) return
            val rotation = activity.windowManager.defaultDisplay.rotation

            // This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE)?.apply {
                addTarget(imageReader?.surface!!)

                // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
                // We have to take that into account and rotate JPEG properly.
                // For devices with orientation of 90, we return our mapping from ORIENTATIONS.
                // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
                set(CaptureRequest.JPEG_ORIENTATION,
                    (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360)

                // Use the same AE and AF modes as the preview.
//                set(CaptureRequest.CONTROL_AF_MODE,
//                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF)
            }?.also { setAutoFlash(it) }
            Log.v("chaewon", "나")
            val captureCallback = object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureCompleted(session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: TotalCaptureResult) {
                    Log.v("chaewon", "타")
                    activity.runOnUiThread {
                        binding.shutterBtn.isEnabled = true // shutter Btn 활성화
                        shutterBtnRotation.cancel()
                    }

                    unlockFocus()

                }
            }
            val captureFocusCallback = object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureCompleted(session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: TotalCaptureResult) {
                    Log.v("chaewon", "카")
                    activity.runOnUiThread {
                        binding.shutterBtn.isEnabled = true // shutter Btn 활성화
                        shutterBtnRotation.cancel()
                    }

//                    unlockFocus()

                    isObjectFocus = false
                    detectionIndex+=1
                    takeObjectFocus()

                    Log.v("chaewon", "detectionIndex : $detectionIndex, deteionList.size : ${detectionCenterPointList.size}")


                }
            }
            Log.v("chaewon", "다")
            // Single 모드 일때, Object Focus 모드 일때,
            if(isSingle){
                Log.v("chaewon", "라")
                captureSession?.apply {
                    stopRepeating()
                    abortCaptures()
                    capture(captureBuilder?.build()!!, captureCallback, null)
                    isSingle = false
                }
                Log.v("chaewon", "마")
            }
            Log.v("chaewon", "바")
            if(isObjectFocus){
                Log.v("chaewon", "사")
                Log.v("chaewon", "captureStill object focus")
                captureSession?.apply {
                    stopRepeating()
                    abortCaptures()
                    capture(captureBuilder?.build()!!, captureFocusCallback, null)
                }
            }
            Log.v("chaewon", "아")
            // Burst 모드 일때
            if(isBurst){
                Log.v("chaewon", "자")
                captureSession?.apply {
                    stopRepeating()
                    abortCaptures()

                    // Set up a list of capture requests to be captured in a burst
                    val captureRequestList = mutableListOf<CaptureRequest>()
                    for (i in 0 until BURST_SIZE) {
                        captureBuilder?.let { captureRequestList.add(it.build()) }
                    }

                    // Capture the burst of images
                    captureBurst(captureRequestList, captureCallback, null)
                    isBurst = false
                }
            }
            Log.v("chaewon", "차")

        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private fun lockFocus() {
        try {
            Log.v("chaewon", "lockFocus")
            // This is how to tell the camera to lock focus.
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START)
            // Tell #captureCallback to wait for the lock.
            state = STATE_WAITING_LOCK
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    private fun unlockFocus() {
        try {
            // Reset the auto-focus trigger
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            setAutoFlash(previewRequestBuilder)
            captureSession?.capture(previewRequestBuilder.build(), captureCallback,
                backgroundHandler)
            // After this, the camera will go back to the normal state of preview.
            state = STATE_PREVIEW
            captureSession?.setRepeatingRequest(previewRequest, captureCallback,
                backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }

    }

    private fun createCameraPreviewSession(){
        try{
            val texture = textureView.surfaceTexture
            texture?.setDefaultBufferSize(previewSize.width, previewSize.height)
            Log.v(TAG, "[createCameraPreviewSession] previewSize width x height = ${previewSize.width} x ${previewSize.height}")
            val surface = Surface(texture)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )
            previewRequestBuilder.addTarget(surface)

            cameraDevice?.createCaptureSession(Arrays.asList(surface, imageReader?.surface),
            object : CameraCaptureSession.StateCallback(){
                override fun onConfigured(session: CameraCaptureSession) {
                    if(cameraDevice == null) return

                    captureSession = session
                    try{
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

                        setAutoFlash(previewRequestBuilder)

                        previewRequest = previewRequestBuilder.build()

                        captureSession?.setRepeatingRequest(previewRequest,
                            captureCallback, backgroundHandler)

                    }catch (e: CameraAccessException) {
                        Log.e(TAG, e.toString())
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed onConfigured")
                }
            }, null)

        } catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        }
    }

    private fun setAutoFlash(requestBuilder: CaptureRequest.Builder) {
        if (flashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        }
    }

    private fun setUpCameraOutputs(width: Int, height: Int){
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try{
            for(cameraId in manager.cameraIdList){
                // CameraCharacteristics : 카메라 정보를 가짐
                val characteristics = manager.getCameraCharacteristics(cameraId)

                val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                if(cameraDirection != null &&
                        cameraDirection == CameraCharacteristics.LENS_FACING_FRONT)
                    continue

                // 프리뷰 설정하는데 사용
                // 현재 카메라의 스트림 구성 맵 가져오기
                val map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                // map.getOutputSizes(format) : 현재 카메라에서 지원하는 JPEG 이미지 출력 크기 가져오기
                // CompareSizesByArea() : 지원하는 출력 크기 목록에서 가장 큰 크기 찾기
                val largest = Collections.max(
                    Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)),
                    CompareSizesByArea())

                Log.v(TAG, "[setUpCameraOutputs] largest : width x height = ${largest.width} x ${largest.height}")

                imageReader = ImageReader.newInstance(largest.width, largest.height,
                ImageFormat.JPEG, /*maxImages*/10).apply{
                    setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
                }
                Log.v(TAG, "[setUpCameraOutputs] imageReader width x height = ${imageReader!!.width} x ${imageReader!!.height}")

                // 카메라 이미지 센서와 디스플레이 회전 방향에 따른 가로 세로 길이 결정
                val displayRotation = activity.windowManager.defaultDisplay.rotation

                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
                val swappedDimensions = areDimensionsSwapped(displayRotation)

                val displaySize = Point()

                activity.windowManager.defaultDisplay.getSize(displaySize)
                val rotatedPreviewWidth = if (swappedDimensions) height else width
                val rotatedPreviewHeight = if (swappedDimensions) width else height
                Log.v(TAG, "[setUpCameraOutputs] rotatedPreviewWidth x rotatedPreviewHeight = ${rotatedPreviewWidth} x ${rotatedPreviewHeight}")
                var maxPreviewWidth = if (swappedDimensions) displaySize.y else displaySize.x
                var maxPreviewHeight = if (swappedDimensions) displaySize.x else displaySize.y
                Log.v(TAG, "[setUpCameraOutputs] displaySize.x x displaySize.y = ${displaySize.x} x ${displaySize.y}")
                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) maxPreviewWidth = MAX_PREVIEW_WIDTH
                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) maxPreviewHeight = MAX_PREVIEW_HEIGHT
                Log.v(TAG, "[setUpCameraOutputs] maxPreviewWidth x maxPreviewHeight = ${maxPreviewWidth} x ${maxPreviewHeight}")

                val activeArraySize = characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
                Log.v(TAG, "[setUpCameraOutputs] activeArraySize  = ${activeArraySize}")

                // 미리보기 크기를 결정하는 부분
                previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                    rotatedPreviewWidth, rotatedPreviewHeight,
                    maxPreviewWidth, maxPreviewHeight,
                    largest)

                Log.v(TAG, "[setUpCameraOutputs] previewSize width x height = ${previewSize.width} x ${previewSize.height}")

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    textureView.setAspectRatio(previewSize.width, previewSize.height)

                } else {
                    textureView.setAspectRatio(previewSize.height, previewSize.width)
                }

                flashSupported =
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                this.cameraId = cameraId

                minimumFocusDistance = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                hyperFocalDistance = characteristics.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f

                Log.v(TAG, "[setUpCameraOutputs] minimumFocusDistance = ${minimumFocusDistance}")
                Log.v(TAG, "[setUpCameraOutputs] hyperFocalDistance = ${hyperFocalDistance}")

                return
            }
        }catch (e: CameraAccessException) {
            Log.e(TAG, e.toString())
        } catch (e: NullPointerException) {
            Log.e(TAG, "This device doesn't support Camera2 API.")
        }
    }

    // 디스플레이 방향, 센서 방향 비교해서  미리보기 화면의 가로 세로 비율이 올바르게 나오도록 조정
    private fun areDimensionsSwapped(displayRotation : Int) : Boolean {
        var swappedDimensions = false
        when (displayRotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> {
                if (sensorOrientation == 90 || sensorOrientation == 270){
                    swappedDimensions = true
                }
            }
            Surface.ROTATION_90, Surface.ROTATION_270 ->{
                if(sensorOrientation == 0 || sensorOrientation == 180){
                    swappedDimensions = true
                }
            }
            else -> {
                Log.e(TAG, "Display rotation is invalid: $displayRotation")
            }
        }
        return swappedDimensions
    }

    // 왜 하는지 모르겠음
    // onSurfaceTextureSizeChanged()에서 사용하긴 함!
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        val rotation = activity.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width)
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }


    /**
     * openCamera(width: Int, height: Int)
     */
    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int){
        Log.v(TAG, "[openCamera] TextureView width : ${width}, height : ${height}")

        val permission = checkSelfPermission(activity, Manifest.permission.CAMERA)
        val permissionAudio = checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)

        Log.v(TAG, "[openCamera] PackageManager.PERMISSION_GRANTED : ${PackageManager.PERMISSION_GRANTED}")
        Log.v(TAG, "[openCamera] permission : ${permission}")
        Log.v(TAG, "[openCamera] permissionAudio : ${permissionAudio}")

       if (permission != PackageManager.PERMISSION_GRANTED
            || permissionAudio != PackageManager.PERMISSION_GRANTED) {
            checkPermissions()
            return
        }

        Log.v(TAG, "[openCamera] Permission Check END: ${permission}, ${permissionAudio}")

        setUpCameraOutputs(width, height)
        configureTransform(width, height)

        val statusBarHeight = getStatusBarHeightDP(requireContext())
        val params: ViewGroup.LayoutParams = binding.bottomMenu.getLayoutParams()
        val topMenuParams: ViewGroup.LayoutParams = binding.topMenu.getLayoutParams()
        val displaySize = Point()
        activity.windowManager.defaultDisplay.getSize(displaySize)
//        params.height = textureView.height * 20 / 100 // 예시로 TextureView 높이의 20%로 설정
        Log.v(TAG, "[openCamera] displaySize.y = ${displaySize.y} , " +
                "topMenuParams.height = ${topMenuParams.height}, textureView.height = ${textureView.height}" +
                " status Hieght = ${statusBarHeight}")
        params.height = displaySize.y - topMenuParams.height - textureView.height - statusBarHeight
        Log.v(TAG, "[openCamera] params.height : ${params.height}")

        binding.bottomMenu.setLayoutParams(params)

        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try{
            if(!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MICROSECONDS)){
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            Log.v(TAG, "[openCamera] CameraManager 생성 후, manager.openCamera(...) 시도 전")
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        }catch (e: CameraAccessException) {
            Log.v(TAG, "[openCamera] CameraAccessException")
            Log.e(TAG, e.toString())
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.", e)
        }
    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    fun getStatusBarHeightDP(context: Context): Int {
        var result = 0
        val resourceId: Int = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimension(resourceId).toInt()
        }
        return result
    }

    companion object{
        private val TAG = "CameraFragment"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA,
                                                Manifest.permission.RECORD_AUDIO)
        private const val REQUEST_CAMERA_PERMISSION = 1
        private const val REQUEST_PERMISSION = 123
        private val ORIENTATIONS = SparseIntArray()

        private val MAX_PREVIEW_WIDTH = 1920
        private val MAX_PREVIEW_HEIGHT = 1080

        private val INPUT_SIZE = 320
        private val NUM_DETECTIONS = 5
        private val MIN_CONFIDENCE = 0.2f

        /**
         * Camera state: Showing camera preview.
         */
        private val STATE_PREVIEW = 0

        /**
         * Camera state: Waiting for the focus to be locked.
         */
        private val STATE_WAITING_LOCK = 1

        /**
         * Camera state: Waiting for the exposure to be precapture state.
         */
        private val STATE_WAITING_PRECAPTURE = 2

        /**
         * Camera state: Waiting for the exposure state to be something other than precapture.
         */
        private val STATE_WAITING_NON_PRECAPTURE = 3

        /**
         * Camera state: Picture was taken.
         */
        private val STATE_PICTURE_TAKEN = 4

        private val BURST_SIZE = 10

        private val OBJECT_DETECT_SIZE = 5

        private val OBJECT_DETECT_PERCENTAGE = 0.4

        @JvmStatic private fun chooseOptimalSize(
            choices: Array<Size>,
            textureViewWidth: Int,
            textureViewHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
            aspectRatio: Size
        ): Size {

            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = ArrayList<Size>()
            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = ArrayList<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight &&
                    option.height == option.width * h / w) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.
            if (bigEnough.size > 0) {
                return Collections.min(bigEnough, CompareSizesByArea())
            } else if (notBigEnough.size > 0) {
                return Collections.max(notBigEnough, CompareSizesByArea())
            } else {
                Log.e(TAG, "Couldn't find any suitable preview size")
                return choices[0]
            }
        }
    }
}