package com.example.test_camera2.CameraHelper

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.MeteringRectangle
import com.example.test_camera2.CameraFragment

class FocusTool (cameraFragment: CameraFragment) {

    private var cameraFragment : CameraFragment

    init {
        this.cameraFragment = cameraFragment
    }

    fun convertPointToMeteringRect(x : Float, y : Float) : MeteringRectangle {

        val manager = cameraFragment.activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraFragment.cameraId)
        val sensorArraySize = characteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]!!
        val sensorOrientation = characteristics[CameraCharacteristics.SENSOR_ORIENTATION]!!

        val halfTouchWidth = 100
        val halfTouchHeight = 100

        val textureViewWidth = cameraFragment.textureView.width
        val textureViewHeight = cameraFragment.textureView.height

        val sensorWidth = sensorArraySize!!.width()
        val sensorHeight = sensorArraySize.height()

        // Calculate the touch point coordinates relative to the sensor area
        val touchPointX = (x / textureViewWidth) * sensorWidth
        val touchPointY = (y / textureViewHeight) * sensorHeight


        val rotatedTouchPointX: Float
        val rotatedTouchPointY: Float
        when (sensorOrientation) {
            90 -> {
                rotatedTouchPointX = touchPointY
                rotatedTouchPointY = sensorHeight - touchPointX
            }
            180 -> {
                rotatedTouchPointX = sensorWidth - touchPointX
                rotatedTouchPointY = sensorHeight - touchPointY
            }
            270 -> {
                rotatedTouchPointX = sensorWidth - touchPointY
                rotatedTouchPointY = touchPointX
            }
            else -> {
                rotatedTouchPointX = touchPointX
                rotatedTouchPointY = touchPointY
            }
        }

        return MeteringRectangle(
            maxOf(rotatedTouchPointX.toInt(), 0),
            maxOf(rotatedTouchPointY.toInt(), 0),
            halfTouchWidth * 2,
            halfTouchHeight * 2,
            MeteringRectangle.METERING_WEIGHT_MAX - 1
        )
    }
}