package com.example.camerademo.view


import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.widget.FrameLayout
import androidx.core.view.isVisible
import com.example.camerademo.R

/**
 * A [SurfaceView] that can be adjusted to a specified aspect ratio and
 * performs center-crop transformation of input frames.
 */
class FocusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {
    private val focusPoint: View by lazy { findViewById(R.id.focus) }

    init {
        inflate(context, R.layout.focus_view_layout, this)
    }

    fun showFocus(x: Int, y: Int) {
        val leftMargin = ((x - focusPoint.width / 2).takeIf { it >=0 } ?: 0).takeIf { it <= (width - focusPoint.width) } ?: (width - focusPoint.width)
        val topMargin = ((y - focusPoint.height / 2).takeIf { it >= 0 } ?: 0).takeIf { it <= (height - focusPoint.height) } ?: (height - focusPoint.height)
        val param = LayoutParams(focusPoint.layoutParams).apply {
            setMargins(leftMargin, topMargin, 0, 0)
        }
        focusPoint.layoutParams = param
        focusPoint.isVisible = true
        val scaleAnimation = ScaleAnimation(
            1.3f,
            1.0f,
            1.3f,
            1.0f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f
        )
        scaleAnimation.duration = 200L
        focusPoint.animation = scaleAnimation
        postDelayed(
            { focusPoint.isVisible = false },
            1000L
        )
    }
}