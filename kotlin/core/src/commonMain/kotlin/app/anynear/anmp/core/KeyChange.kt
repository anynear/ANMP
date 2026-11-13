package app.anynear.anmp.core

/**
 * Key change ([newSecret]) or removal (`null`) by another member (ADR 0012), unchanged from the app's
 * `circles/KeyChange.kt`. [viaPrevious]: came through the space before our last change. The caller checks
 * that the sender is a known member.
 */
internal class KeyChange(
    val from: String,
    val epoch: Int,
    val ts: Long,
    val newSecret: String?,
    val removedDevice: String?,
    val viaPrevious: Boolean,
) {
    sealed interface Outcome {
        data object Ignored : Outcome

        data class Switched(val circle: CircleState) : Outcome

        data class Removed(val circle: CircleState) : Outcome

        data class Readmitted(val circle: CircleState) : Outcome

        data class StillRemoved(val circle: CircleState) : Outcome
    }

    /**
     * Valid only with the right epoch: from the current space the next epoch; from the previous space a
     * simultaneous change with the same epoch if it was earlier and not from the device our change removed;
     * when removed, an earlier change with the epoch that removed us. [graceUntil]: the other space is read until then.
     */
    fun apply(circle: CircleState, graceUntil: Long): Outcome {
        if (circle.removed) {
            if (epoch != circle.removedEpoch || !CircleState.isEarlier(ts, from, circle.removedTs, circle.removedBy)) return Outcome.Ignored
            if (newSecret == null) return Outcome.StillRemoved(circle.copy(removedTs = ts, removedBy = from))
            return Outcome.Readmitted(
                circle.copy(
                    removed = false, secret = newSecret, epoch = epoch, epochTs = ts, epochBy = from, epochRemoved = removedDevice,
                    previousSecret = circle.secret, graceUntil = graceUntil, removedEpoch = 0, removedTs = 0, removedBy = "",
                ),
            )
        }
        val isNext = !viaPrevious && epoch == circle.epoch + 1
        val isRace = viaPrevious && epoch == circle.epoch && from != circle.epochRemoved &&
            CircleState.isEarlier(ts, from, circle.epochTs, circle.epochBy)
        if (!isNext && !isRace) return Outcome.Ignored
        if (newSecret == null) {
            val pollSecret = if (isNext) circle.secret else circle.previousSecret ?: circle.secret
            return Outcome.Removed(
                circle.copy(
                    removed = true, secret = pollSecret, epoch = epoch - 1, previousSecret = null, graceUntil = graceUntil,
                    removedEpoch = epoch, removedTs = ts, removedBy = from,
                ),
            )
        }
        return Outcome.Switched(
            circle.copy(
                secret = newSecret, epoch = epoch, epochTs = ts, epochBy = from, epochRemoved = removedDevice,
                previousSecret = if (isNext) circle.secret else circle.previousSecret, graceUntil = graceUntil,
            ),
        )
    }
}
