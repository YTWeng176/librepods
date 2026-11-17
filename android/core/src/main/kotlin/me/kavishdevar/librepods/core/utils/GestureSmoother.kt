package me.kavishdevar.librepods.core.utils

/** At call-time sampling rates, averaging opposing nod samples erases the motion. */
internal class GestureSmoother {
    private val horizontal = ArrayDeque<Double>()
    private val vertical = ArrayDeque<Double>()
    private var lastSampleAt: Long? = null
    init { clear() }

    @Synchronized fun sample(h: Double, v: Double, at: Long): Pair<Double, Double> {
        val slow = lastSampleAt?.let { at - it > 100 } == true
        lastSampleAt = at
        horizontal.addLast(h); horizontal.removeFirst()
        vertical.addLast(v); vertical.removeFirst()
        return if (slow) h to v else horizontal.average() to vertical.average()
    }

    @Synchronized fun clear() {
        horizontal.clear(); vertical.clear()
        repeat(3) { horizontal.addLast(0.0); vertical.addLast(0.0) }
        lastSampleAt = null
    }
}
