package com.varun.depthai.helpers

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.ArrayBlockingQueue

/**
 * Detects taps and passes them between the UI thread and GL render thread.
 */
class TapHelper(context: Context) : View.OnTouchListener {

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            queuedSingleTaps.offer(e)
            return true
        }
        override fun onDown(e: MotionEvent): Boolean = true
    })

    private val queuedSingleTaps = ArrayBlockingQueue<MotionEvent>(16)

    fun poll(): MotionEvent? = queuedSingleTaps.poll()

    override fun onTouch(view: View, motionEvent: MotionEvent): Boolean =
        gestureDetector.onTouchEvent(motionEvent)
}
