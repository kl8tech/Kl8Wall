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

    private val TAG = "HotCornerDetector"
    private val handler = Handler(Looper.getMainLooper())
    private var touchDownInCorner = false
    private var triggerRunnable: Runnable? = null
    private var startX = 0f
    private var startY = 0f

    /**
     * Call from the parent view's [View.dispatchTouchEvent].
     *
     * Returns true if the event was consumed (hold completed),
     * false to pass it through to child views.
     */
    fun onTouchEvent(event: MotionEvent, viewWidth: Int, viewHeight: Int, density: Float): Boolean {
        val action = event.actionMasked
        val activeCorner = cornerProvider()
        
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                android.util.Log.d(TAG, "ACTION_DOWN: x=${event.x}, y=${event.y}, viewWidth=$viewWidth, viewHeight=$viewHeight, activeCorner=$activeCorner")
                if (isInCorner(event.x, event.y, viewWidth, viewHeight)) {
                    android.util.Log.d(TAG, "Touch inside hot corner zone!")
                    touchDownInCorner = true
                    startX = event.x
                    startY = event.y
                    triggerRunnable = Runnable {
                        android.util.Log.i(TAG, "Hot corner hold triggered successfully!")
                        onTriggered()
                        touchDownInCorner = false
                    }
                    handler.postDelayed(triggerRunnable!!, HOLD_DURATION_MS)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchDownInCorner) {
                    android.util.Log.d(TAG, "Touch released/cancelled. Cancelling hold.")
                }
                cancelPendingTrigger()
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchDownInCorner) {
                    val tolerancePx = MOVE_TOLERANCE_DP * density
                    val dist = Math.hypot((event.x - startX).toDouble(), (event.y - startY).toDouble())
                    if (dist > tolerancePx) {
                        android.util.Log.d(TAG, "Drifted too far (dist=$dist px, tolerance=$tolerancePx px). Cancelling hold.")
                        cancelPendingTrigger()
                    }
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
        val zoneWidth = Math.max(viewWidth * CORNER_FRACTION, 200f)
        val zoneHeight = Math.max(viewHeight * CORNER_FRACTION, 200f)

        val inZone = when (cornerProvider()) {
            HotCorner.TOP_LEFT -> x < zoneWidth && y < zoneHeight
            HotCorner.TOP_RIGHT -> x > viewWidth - zoneWidth && y < zoneHeight
            HotCorner.BOTTOM_LEFT -> x < zoneWidth && y > viewHeight - zoneHeight
            HotCorner.BOTTOM_RIGHT -> x > viewWidth - zoneWidth && y > viewHeight - zoneHeight
        }
        android.util.Log.d(TAG, "isInCorner: x=$x, y=$y, zoneW=$zoneWidth, zoneH=$zoneHeight, inZone=$inZone")
        return inZone
    }

    companion object {
        private const val HOLD_DURATION_MS = 3000L
        private const val CORNER_FRACTION = 0.15f
        private const val MOVE_TOLERANCE_DP = 80f
    }
}
