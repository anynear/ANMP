package app.anynear.anmp.core

import app.anynear.anmp.AnmpKeys
import kotlin.time.Duration

/**
 * "Wer es hat, liefert es" (ADR 0014): tasks only one member should do are not always done by the same
 * device but by a ranking per occasion. Rank 0 acts after a short pause, the others step in one after the
 * other if nobody acted until then. Every device computes the same ranking as far as it knows the same members.
 */
internal class Duty(private val timings: CoreTimings) {
    /** Rank of [me] among [me] and [others] for [occasion]; 0 = first. */
    suspend fun rank(occasion: String, me: String, others: Collection<String>): Int {
        val order = (others.toSet() + me).map { it to AnmpKeys.sha256("$occasion\n$it".encodeToByteArray()) }
            .sortedWith { a, b -> compareBytes(a.second, b.second).takeIf { it != 0 } ?: a.first.compareTo(b.first) }
        return order.indexOfFirst { it.first == me }
    }

    fun delayFor(rank: Int): Duration = if (rank <= 0) timings.dutyFirst else minOf(timings.dutyStep * rank, timings.dutyMax)

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val c = (a[i].toInt() and 0xff).compareTo(b[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }
}
