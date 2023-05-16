package com.example.test_camera2.CameraHelper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.test_camera2.CameraFragment

class OverlayView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private var detectedList: List<CameraFragment.DetectionResult> = emptyList()
    fun updateResults(results: List<CameraFragment.DetectionResult>) {
        detectedList = results
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 객체 감지 결과를 기반으로 네모 박스를 그립니다.
        for (result in detectedList) {
            val boundingBox = result.boundingBox

            val paint = Paint().apply {
                color = Color.RED         // 색상 설정
                strokeWidth = 5f          // 선 굵기 설정
                style = Paint.Style.STROKE // 선 스타일 설정 (FILL, STROKE, FILL_AND_STROKE)
            }

            // 네모 박스를 그리는 로직을 구현합니다.
            canvas.drawRect(boundingBox, paint)
        }
    }
}

