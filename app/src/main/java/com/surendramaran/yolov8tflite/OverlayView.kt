package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.DetectionUtils.classThresholds
import java.util.LinkedList
import kotlin.math.max

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {


    private var results = listOf<BoundingBox>()
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()



    private var bounds = Rect()

    init {
        initPaints()
    }


    fun clear() {
        results = listOf()
        invalidate()
    }

    private fun initPaints() {
        textBackgroundPaint.apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            textSize = 50f
        }

        textPaint.apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            textSize = 50f
        }

        boxPaint.apply {
            color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
            strokeWidth = 8F
            style = Paint.Style.STROKE
        }
    }



    private val gridPaint = Paint().apply {
        color = Color.argb(100, 255, 255, 255) // Semi-transparent white
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f) // Dashed lines
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        drawPositionGrid(canvas)

        val prioritized = DetectionUtils.filterAndPrioritize(results).take(1)
        prioritized.forEach { box ->
            drawBoundingBox(canvas, box)
            drawPositionIndicator(canvas, box)
        }


    }

    private fun drawPositionGrid(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()

        // Floor zone (bottom 20%)
        val floorLineY = height * 0.8f
        canvas.drawLine(0f, floorLineY, width, floorLineY, gridPaint)

        // Center path boundaries (30% and 70% of width)
        val leftCenterLineX = width * 0.3f
        val rightCenterLineX = width * 0.7f
        canvas.drawLine(leftCenterLineX, 0f, leftCenterLineX, floorLineY, gridPaint)
        canvas.drawLine(rightCenterLineX, 0f, rightCenterLineX, floorLineY, gridPaint)

        // Label each zone
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
        }

        // Floor label
        canvas.drawText("FLOOR ZONE", width * 0.4f, height * 0.9f, textPaint)

        // Center label
        canvas.drawText("CENTER PATH", width * 0.4f, height * 0.4f, textPaint)

        // Side labels
        canvas.drawText("LEFT", width * 0.1f, height * 0.4f, textPaint)
        canvas.drawText("RIGHT", width * 0.8f, height * 0.4f, textPaint)
    }


    private fun drawPositionIndicator(canvas: Canvas, box: BoundingBox) {
        val displayName = DetectionUtils.getDisplayClassName(box.clsName)
        val position = DetectionUtils.getPositionDescription(box).uppercase()
        val text = "$displayName - $position"
        val textY = box.y2 * height + 50f // 50px below box

        canvas.drawText(
            text,
            box.x1 * width,
            textY,
            textPaint
        )
    }

    private fun drawBoundingBox(canvas: Canvas, box: BoundingBox) {
        val left = box.x1 * width
        val top = box.y1 * height
        val right = box.x2 * width
        val bottom = box.y2 * height

        // Draw bounding box
        canvas.drawRect(left, top, right, bottom, boxPaint)

        // Draw label
        val boxWidthPx = (box.w * width).toInt()
        val boxHeightPx = (box.h * height).toInt()
        val displayName = DetectionUtils.getDisplayClassName(box.clsName)
        val labelText = "$displayName (${"%.2f".format(box.cnf)}) [${boxWidthPx}x${boxHeightPx}]"

        textBackgroundPaint.getTextBounds(labelText, 0, labelText.length, bounds)
        canvas.drawRect(
            left,
            top,
            left + bounds.width() + BOUNDING_RECT_TEXT_PADDING,
            top + bounds.height() + BOUNDING_RECT_TEXT_PADDING,
            textBackgroundPaint
        )
        canvas.drawText(labelText, left, top + bounds.height(), textPaint)
    }


    fun setResults(boundingBoxes: List<BoundingBox>) {
        results = boundingBoxes
        invalidate()
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}