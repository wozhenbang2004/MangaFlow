package dev.mangaflow.reader

/** Explicit controls; numeric values have only type/semantic validity, never product maxima. */
data class ReaderRecoveryPreferences(
    val autoRepair: Boolean = true,
    val autoSwitch: Boolean = true,
    val sourceLocked: Boolean = false,
    val consecutiveFailures: Int = 3,
    val failureWindow: Int = 6,
    val failuresInWindow: Int = 3,
    val switchCooldownSeconds: Int = 60,
    val standbyRetryCount: Int = 2,
    val standbyRetryDelaySeconds: Int = 30,
    val manifestTimeoutSeconds: Int = 25,
    val imageTimeoutSeconds: Int = 45,
    val slowLoadSeconds: Int = 8,
) {
    fun checked() = copy(
        consecutiveFailures = consecutiveFailures.coerceAtLeast(1),
        failureWindow = failureWindow.coerceAtLeast(1),
        failuresInWindow = failuresInWindow.coerceAtLeast(1),
        switchCooldownSeconds = switchCooldownSeconds.coerceAtLeast(0),
        standbyRetryCount = standbyRetryCount.coerceAtLeast(0),
        standbyRetryDelaySeconds = standbyRetryDelaySeconds.coerceAtLeast(0),
        manifestTimeoutSeconds = manifestTimeoutSeconds.coerceAtLeast(1),
        imageTimeoutSeconds = imageTimeoutSeconds.coerceAtLeast(1),
        slowLoadSeconds = slowLoadSeconds.coerceAtLeast(1),
    )
}

/** Only actual page outcomes enter this ledger. Queues, cancellation and local protection do not. */
internal class ReaderSourceFailureWindow {
    private val outcomes = mutableMapOf<Int, Boolean>()
    fun failed(index: Int) { if (index >= 0) outcomes[index] = false }
    fun succeeded(index: Int) { if (index >= 0) outcomes[index] = true }
    fun isFailed(index: Int): Boolean = outcomes[index] == false
    fun shouldSwitch(index: Int, policy: ReaderRecoveryPreferences): Boolean {
        if (!policy.autoSwitch || policy.sourceLocked || outcomes[index] != false) return false
        val failures = outcomes.filterValues { !it }.keys.sorted()
        var consecutive = 0L
        var previous: Int? = null
        for (page in failures) {
            consecutive = if (previous?.toLong()?.plus(1L) == page.toLong()) consecutive + 1L else 1L
            previous = page
            if (consecutive >= policy.consecutiveFailures.coerceAtLeast(1).toLong() &&
                index.toLong() in (page.toLong() - consecutive + 1L)..page.toLong()) return true
        }
        val span = policy.failureWindow.coerceAtLeast(1).toLong()
        val required = policy.failuresInWindow.coerceAtLeast(1).toLong()
        var left = 0
        for (right in failures.indices) {
            while (failures[right].toLong() - failures[left].toLong() >= span) left++
            if (right.toLong() - left.toLong() + 1L >= required &&
                index in failures[left]..failures[right]) return true
        }
        return false
    }
}
