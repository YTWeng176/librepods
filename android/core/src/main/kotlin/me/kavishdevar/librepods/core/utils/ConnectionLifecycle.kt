package me.kavishdevar.librepods.core.utils

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** EOF and failed reads are terminal, even if BluetoothSocket.isConnected remains true. */
internal suspend fun readPackets(
    input: InputStream,
    connected: () -> Boolean,
    receive: (ByteArray) -> Unit,
    invalid: (Exception) -> Unit
) {
    val buffer = ByteArray(1024)
    while (connected()) {
        currentCoroutineContext().ensureActive()
        val count = try { input.read(buffer) } catch (_: IOException) { return }
        currentCoroutineContext().ensureActive()
        if (count < 0) return
        if (count == 0) { yield(); continue }
        try {
            receive(buffer.copyOf(count))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            invalid(e)
        }
    }
}

/** A socket owns both jobs; closing it also unblocks a pending platform read. */
internal class SocketSession<T : Closeable>(private val scope: CoroutineScope) {
    @Volatile var resource: T? = null
        private set
    private var reader: Job? = null
    private var initializer: Job? = null
    private var onClosed: (() -> Unit)? = null

    @Synchronized
    fun connect(
        create: () -> T, initialize: suspend (T) -> Unit, read: suspend (T) -> Unit,
        onConnected: () -> Unit = {}, onDisconnected: () -> Unit = {}
    ): Boolean {
        if (resource != null && reader?.isActive == true) return true
        close()
        onClosed = onDisconnected
        try {
            val opened = create()
            resource = opened
            initializer = scope.launch(start = CoroutineStart.LAZY) { initialize(opened) }
            reader = scope.launch(start = CoroutineStart.LAZY) {
                try { read(opened) } finally { closeIfCurrent(opened) }
            }
            // Publish success under the same owner as termination, before either
            // job can finish. An immediate EOF must never be overwritten by success.
            onConnected()
            initializer?.start()
            reader?.start()
            return resource === opened && reader?.isActive == true
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    @Synchronized private fun closeIfCurrent(opened: T) {
        if (resource === opened) close()
    }

    @Synchronized fun close() {
        val opened = resource
        val notify = onClosed
        onClosed = null
        try {
            // Device state and device-owned jobs share this connection owner.
            // No separate manager/device monitor is acquired by the callback.
            notify?.invoke()
        } finally {
            resource = null
            initializer?.cancel()
            initializer = null
            reader?.cancel()
            reader = null
            try { opened?.close() } catch (_: IOException) { /* Already disconnected. */ }
        }
    }
}

/** Owns the scan jobs so retries cannot resurrect a stopped scanner. */
internal class ScanLifecycle(
    private val scope: CoroutineScope,
    private val startScan: () -> Unit,
    private val stopScan: () -> Unit,
    private val flush: () -> Unit,
    private val checkTimeout: () -> Unit
) {
    private var requested = false
    private var scanning = false
    private var retry: Job? = null
    private var flushJob: Job? = null
    private var timeout: Job? = null

    @Synchronized fun start(reportDelay: Long) {
        if (requested) return
        requested = true
        attemptStart(reportDelay.coerceAtLeast(0))
    }

    @Synchronized private fun attemptStart(reportDelay: Long) {
        if (!requested || scanning) return
        try {
            startScan()
        } catch (e: Exception) {
            if (e.message?.contains("too frequently") == true) {
                retry = scope.launch {
                    delay(5_000)
                    attemptStart(reportDelay)
                }
            } else {
                requested = false
            }
            return
        }
        scanning = true
        timeout = scope.launch {
            while (isActive) { delay(2_500); checkTimeout() }
        }
        // Immediate callback mode has no batched results to flush.
        if (reportDelay > 0) flushJob = scope.launch {
            while (isActive) { delay(reportDelay); flush() }
        }
    }

    @Synchronized fun stop() {
        requested = false
        retry?.cancel(); retry = null
        flushJob?.cancel(); flushJob = null
        timeout?.cancel(); timeout = null
        if (scanning) {
            scanning = false
            stopScan()
        }
    }
}

/** Cache success and failure by ciphertext, with bounded memory and explicit key invalidation. */
internal class AdvertisementCache<V>(private val capacity: Int) {
    private val entries = object : LinkedHashMap<List<Byte>, V?>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Byte>, V?>): Boolean = size > capacity
    }
    @Synchronized fun resolve(data: ByteArray, decrypt: () -> V?): V? {
        val key = data.toList()
        if (entries.containsKey(key)) return entries[key]
        return decrypt().also { entries[key] = it }
    }
    @Synchronized fun clear() = entries.clear()
}

/** Only the service's known devices enter this map. Profile IDs remain platform independent. */
class AudioConnectionLifecycle<K>(private val scope: CoroutineScope, private val close: (K) -> Unit) {
    private class State {
        val connected = mutableSetOf<Int>()
        var retained = false
        var pending: Job? = null
    }
    private val devices = mutableMapOf<K, State>()

    @Synchronized fun update(key: K, profile: Int, connected: Boolean) {
        val state = devices.getOrPut(key) { State() }
        state.pending?.cancel()
        state.pending = null
        if (connected) state.connected.add(profile) else state.connected.remove(profile)
        if (state.connected.isNotEmpty() || state.retained) return
        state.pending = scope.launch {
            delay(3_000)
            synchronized(this@AudioConnectionLifecycle) {
                if (devices[key] === state && state.connected.isEmpty() && !state.retained) {
                    state.pending = null
                    close(key)
                }
            }
        }
    }

    @Synchronized fun retainForEarDetection(key: K, retain: Boolean) {
        val state = devices.getOrPut(key) { State() }
        state.retained = retain
        if (retain) { state.pending?.cancel(); state.pending = null }
    }

    @Synchronized fun allowsComponentSideEffects(key: K, controlConnected: Boolean): Boolean {
        val state = devices[key] ?: return false
        return controlConnected && (state.connected.isNotEmpty() || state.retained)
    }

    @Synchronized fun disconnected(key: K) { devices.remove(key)?.pending?.cancel() }
    @Synchronized fun clear() {
        devices.values.forEach { it.pending?.cancel() }
        devices.clear()
    }
}
