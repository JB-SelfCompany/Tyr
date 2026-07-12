package com.jbselfcompany.tyr.chat.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    companion object {
        private const val MAX_SCALE = 8f
    }

    private val photoMatrix = Matrix()

    // Minimum scale to fit the image on screen (set in resetMatrix)
    private var minScale = 1f
    private var currentScale = 1f

    // Pointer tracking for stable pan deltas
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val newScale = (currentScale * factor).coerceIn(minScale * 0.5f, MAX_SCALE)
                val realFactor = newScale / currentScale
                currentScale = newScale
                photoMatrix.postScale(realFactor, realFactor, detector.focusX, detector.focusY)
                constrainTranslation()
                imageMatrix = photoMatrix
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Snap back if zoomed out below fit scale
                if (currentScale < minScale) {
                    animateToFit()
                }
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                animateToFit()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }



    fun resetMatrix() {
        val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return
        if (width == 0 || height == 0) return

        minScale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        currentScale = minScale

        photoMatrix.reset()
        photoMatrix.setScale(minScale, minScale)
        photoMatrix.postTranslate(
            (width - bmp.width * minScale) / 2f,
            (height - bmp.height * minScale) / 2f
        )
        imageMatrix = photoMatrix
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger landed — stop tracking pan until it lifts
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx != -1) {
                        val dx = event.getX(idx) - lastX
                        val dy = event.getY(idx) - lastY
                        photoMatrix.postTranslate(dx, dy)
                        constrainTranslation()
                        imageMatrix = photoMatrix
                        lastX = event.getX(idx)
                        lastY = event.getY(idx)
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // When one of multiple fingers lifts, re-anchor to the remaining pointer
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    activePointerId = event.getPointerId(newIndex)
                    lastX = event.getX(newIndex)
                    lastY = event.getY(newIndex)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                // Snap back if under-zoomed
                if (currentScale < minScale) {
                    animateToFit()
                }
            }
        }

        return true
    }

    /** Clamp translation so the image cannot be panned fully off-screen. */
    private fun constrainTranslation() {
        val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return
        val scaledW = bmp.width * currentScale
        val scaledH = bmp.height * currentScale

        val values = FloatArray(9)
        photoMatrix.getValues(values)
        var tx = values[Matrix.MTRANS_X]
        var ty = values[Matrix.MTRANS_Y]

        // Horizontal bounds
        tx = if (scaledW <= width) {
            // Image narrower than view — keep it centred (allow small drag tolerance)
            tx.coerceIn(-(scaledW * 0.1f), width - scaledW + scaledW * 0.1f)
        } else {
            tx.coerceIn(width - scaledW, 0f)
        }

        // Vertical bounds
        ty = if (scaledH <= height) {
            ty.coerceIn(-(scaledH * 0.1f), height - scaledH + scaledH * 0.1f)
        } else {
            ty.coerceIn(height - scaledH, 0f)
        }

        values[Matrix.MTRANS_X] = tx
        values[Matrix.MTRANS_Y] = ty
        photoMatrix.setValues(values)
    }

    /** Instantly reset to fit-to-screen state (double-tap / snap-back). */
    private fun animateToFit() {
        val bmp = (drawable as? BitmapDrawable)?.bitmap ?: return
        if (width == 0 || height == 0) return

        currentScale = minScale
        photoMatrix.reset()
        photoMatrix.setScale(minScale, minScale)
        photoMatrix.postTranslate(
            (width - bmp.width * minScale) / 2f,
            (height - bmp.height * minScale) / 2f
        )
        imageMatrix = photoMatrix
    }
}
