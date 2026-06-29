package cloud.kl8techgroup.kl8wall.ha

import android.util.Log
import cloud.kl8techgroup.kl8wall.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

class DashboardContextManager(
    private val settingsRepository: SettingsRepository,
    private val navigate: (String) -> Unit
) {
    companion object {
        private const val TAG = "DashboardContextMgr"
        private const val CHECK_INTERVAL_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var lastNavigatedUrl: String? = null

    fun start() {
        stop()
        lastNavigatedUrl = null
        job = scope.launch {
            while (isActive) {
                evaluate()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun forceEvaluate() {
        scope.launch { evaluate() }
    }

    private fun evaluate() {
        val calendar = Calendar.getInstance()
        val currentMins = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val morningUrl = settingsRepository.dashboardMorningUrl.value.trim()
        val morningStart = settingsRepository.dashboardMorningStartHour.value * 60
        val morningEnd = settingsRepository.dashboardMorningEndHour.value * 60

        val nightUrl = settingsRepository.dashboardNightUrl.value.trim()
        val nightStart = settingsRepository.dashboardNightStartHour.value * 60
        val nightEnd = settingsRepository.dashboardNightEndHour.value * 60

        val defaultUrl = settingsRepository.startUrl.value.trim()

        val desired = when {
            morningUrl.isNotEmpty() && inWindow(currentMins, morningStart, morningEnd) -> morningUrl
            nightUrl.isNotEmpty() && inWindow(currentMins, nightStart, nightEnd) -> nightUrl
            else -> defaultUrl
        }

        if (desired.isNotEmpty() && desired != lastNavigatedUrl) {
            Log.i(TAG, "Context switch → $desired")
            lastNavigatedUrl = desired
            navigate(desired)
        }
    }

    private fun inWindow(now: Int, start: Int, end: Int): Boolean =
        if (start <= end) now in start until end
        else now >= start || now < end
}
