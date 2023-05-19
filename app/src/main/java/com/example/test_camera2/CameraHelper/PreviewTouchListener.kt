package com.example.test_camera2.CameraHelper

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.example.test_camera2.CameraFragment

//class PreviewTouchListener(private val cameraId: String, private val previewRequestBuilder: CaptureRequest.Builder) : View.OnTouchListener {
class PreviewTouchListener(private val fragment : CameraFragment) : View.OnTouchListener {
    private val afTriggerId = CaptureRequest.CONTROL_AF_TRIGGER

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 터치한 지점의 좌표 가져오기
                val touchPoint = PointF(event.x, event.y)

                Log.v("PreviewTouchListener", "[onTouch] TouchEvent : ${event.x} x ${event.y}")
                Log.v("PreviewTouchListener", "[onTouch] TouchPoint : ${touchPoint.x} x ${touchPoint.y}")

                // 초점을 터치한 지점에 맞추기
                setFocusPoint(touchPoint)

                // AF 모드로 변경하여 초점 잠금 해제
                fragment.previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                fragment.previewRequestBuilder.set(afTriggerId, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                fragment.previewRequestBuilder.set(afTriggerId, CameraMetadata.CONTROL_AF_TRIGGER_START)
                try {
                    fragment.captureSession!!.setRepeatingRequest(fragment.previewRequestBuilder.build(), null, null)
                } catch (e: CameraAccessException) {
                    e.printStackTrace()
                }
            }
        }
        return true
    }

    private fun setFocusPoint(touchPoint: PointF) {
        val meteringRectangle = createMeteringRectangle(touchPoint)

        // 초점을 터치한 지점에 맞추기
        fragment.previewRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle))
        fragment.previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
    }

    private fun createMeteringRectangle(touchPoint: PointF): MeteringRectangle {

        val manager = fragment.activity?.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(fragment.cameraId)

        val viewRect = RectF(0f, 0f, 1f, 1f)
//        val sensorRect = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorRect = characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
        val focusAreaRect = MeteringRectangle(
            (touchPoint.x / viewRect.width() * sensorRect!!.width()).toInt(),
            (touchPoint.y / viewRect.height() * sensorRect!!.height()).toInt(),
            100,
            100,
            MeteringRectangle.METERING_WEIGHT_MAX - 1
        )

        Log.v("PreviewTouchListener", "[createMeteringRectangle] focusAreaRect : ${focusAreaRect.x} x ${focusAreaRect.y}")
        return focusAreaRect
    }
}
