package app.anynear.anmp.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Delivery plan (ADR 0090): how long to wait after the n-th failed try. First the [steps] in turn, then always
 * [steady]. The count of tries is kept with the entry, so after a restart it goes on at the same step.
 */
data class RetryPlan(val steps: List<Duration>, val steady: Duration) {
    /** Wait after [tries] failed tries (1 = after the first). */
    fun waitAfter(tries: Int): Duration = steps.getOrNull(tries - 1) ?: steady
}

/**
 * Wake-up for waits of a delivery plan (ADR 0090): [kick] ends every running [sleep] at once, e.g. when the network
 * is back or the app comes to the foreground. The step of the plan stays as it is.
 */
internal class Wake {
    private val count = MutableStateFlow(0L)

    fun kick() = count.update { it + 1 }

    /** Waits [duration] or until the next [kick]. */
    suspend fun sleep(duration: Duration) {
        val seen = count.value
        withTimeoutOrNull(duration) { count.first { it != seen } }
    }
}
