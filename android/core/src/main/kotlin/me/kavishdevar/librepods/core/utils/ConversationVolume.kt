package me.kavishdevar.librepods.core.utils

/** Keeps a conversation's original volume and invalidates unfinished, older ramps. */
class ConversationVolume(
    private val current: () -> Int,
    private val maximum: () -> Int,
    private val set: (Int) -> Unit,
    private val schedule: (Runnable) -> Unit
) {
    private var original: Int? = null
    @Volatile private var generation = 0L

    @Synchronized fun start() { if (original == null) original = current() }

    @Synchronized fun duck(percent: Int) {
        start()
        transition(minOf(maximum() * percent.coerceIn(0, 100) / 100, original!!))
    }

    @Synchronized fun restore() {
        val target = original ?: return
        ramp(target) { original = null }
    }

    @Synchronized fun transition(target: Int) = ramp(target) {}

    private fun ramp(target: Int, completed: () -> Unit) {
        val boundedTarget = target.coerceIn(0, maximum())
        val rampGeneration = ++generation
        var volume = current()
        val step = if (volume < boundedTarget) 1 else -1
        schedule(object : Runnable {
            override fun run() {
                synchronized(this@ConversationVolume) {
                    if (rampGeneration != generation) return
                    if (volume != boundedTarget) {
                        volume += step
                        set(volume)
                    }
                    if (volume == boundedTarget) completed() else schedule(this)
                }
            }
        })
    }
}
