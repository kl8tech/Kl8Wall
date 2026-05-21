package cloud.kl8techgroup.kl8wall.kiosk

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import cloud.kl8techgroup.kl8wall.settings.HotCorner

/**
 * Detects a long press in a configurable screen corner to trigger
 * settings access.
 *
 * Intercepts touch events at the parent [View] level before the WebView
 * consumes them. Tracks touch-down position and duration in the corner
 * region, fires the callback after [HOLD_DURATION_MS], and passes
 * the touch through to the WebView otherwise.
 */
class HotCornerDetector(
    private val cornerProvider: () -> HotCorner,
    private val onTriggered: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var touchDownInCorner = false
    private var triggerRunnable: Runnable? = null

    /**
     * Call from the parent view's [View.dispatchTouchEvent].
     *
     * Returns true if the event was consumed (hold completed),
     * false to pass it through to child views.
     */
    fun onTouchEvent(event: MotionEvent, viewWidth: Int, viewHeight: Int): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isInCorner(event.x, event.y, viewWidth, viewHeight)) {
                    touchDownInCorner = true
                    triggerRunnable = Runnable {
                        onTriggered()
                        touchDownInCorner = false
                    }
                    handler.postDelayed(triggerRunnable!!, HOLD_DURATION_MS)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPendingTrigger()
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchDownInCorner && !isInCorner(event.x, event.y, viewWidth, viewHeight)) {
                    cancelPendingTrigger()
                }
            }
        }
        return false
    }

    private fun cancelPendingTrigger() {
        triggerRunnable?.let { handler.removeCallbacks(it) }
        triggerRunnable = null
        touchDownInCorner = false
    }

    private fun isInCorner(x: Float, y: Float, viewWidth: Int, viewHeight: Int): Boolean {
        val zoneWidth = viewWidth * CORNER_FRACTION
        val zoneHeight = viewHeight * CORNER_FRACTION

        return when (cornerProvider()) {
            HotCorner.TOP_LEFT -> x < zoneWidth && y < zoneHeight
            HotCorner.TOP_RIGHT -> x > viewWidth - zoneWidth && y < zoneHeight
            HotCorner.BOTTOM_LEFT -> x < zoneWidth && y > viewHeight - zoneHeight
            HotCorner.BOTTOM_RIGHT -> x > viewWidth - zoneWidth && y > viewHeight - zoneHeight
        }
    }

    companion object {
        private const val HOLD_DURATION_MS = 3000L
        private const val CORNER_FRACTION = 0.1f
    }
}
