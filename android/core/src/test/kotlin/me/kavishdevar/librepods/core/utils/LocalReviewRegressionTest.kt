package me.kavishdevar.librepods.core.utils

import java.io.Closeable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalReviewRegressionTest {
    private class Transport : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun immediateEofCannotPublishConnectedAfterTermination() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val session = SocketSession<Transport>(scope)
        val states = mutableListOf<String>()
        val socket = Transport()
        val connected = session.connect(
            { socket }, { awaitCancellation() }, {},
            onConnected = { states += "connected" },
            onDisconnected = { states += "disconnected" }
        )
        assertEquals(listOf("connected", "disconnected"), states)
        assertFalse(connected)
        assertTrue(socket.closed)
        assertNull(session.resource)
    }

    @Test fun lateOldReaderCannotTerminateReplacementSession() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val session = SocketSession<Transport>(scope)
        val finishOldReader = CompletableDeferred<Unit>()
        var state = "disconnected"
        var oldEnds = 0
        var newEnds = 0
        session.connect({ Transport() }, { awaitCancellation() }, {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { finishOldReader.await() }
            }
        }, onConnected = { state = "connected" }, onDisconnected = { state = "disconnected"; oldEnds++ })
        session.close()
        val replacement = Transport()
        session.connect({ replacement }, { awaitCancellation() }, { awaitCancellation() },
            onConnected = { state = "connected" }, onDisconnected = { state = "disconnected"; newEnds++ })
        finishOldReader.complete(Unit)
        runCurrent()
        assertEquals("connected", state)
        assertSame(replacement, session.resource)
        assertFalse(replacement.closed)
        assertEquals(1, oldEnds)
        assertEquals(0, newEnds)
        session.close()
    }

    @Test fun eofRunsDeviceCleanupForPollingAndGestureTasks() = runTest {
        val session = SocketSession<Transport>(backgroundScope)
        val deviceScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob())
        var gestureRunning = false
        val polling = deviceScope.launch { awaitCancellation() }
        session.connect({ Transport() }, { awaitCancellation() }, {},
            onConnected = { gestureRunning = true },
            onDisconnected = {
                deviceScope.coroutineContext.cancelChildren()
                gestureRunning = false
            })
        runCurrent()
        assertFalse(polling.isActive)
        assertFalse(gestureRunning)
        assertNull(session.resource)
        deviceScope.coroutineContext.cancelChildren()
    }

    @Test fun failedSocketOpenCleansDeviceStateWithoutPublishingConnected() = runTest {
        val session = SocketSession<Transport>(backgroundScope)
        var connected = 0
        var disconnected = 0
        try {
            session.connect({ throw java.io.IOException("cannot connect") }, {}, {},
                onConnected = { connected++ }, onDisconnected = { disconnected++ })
            fail("opening must fail")
        } catch (_: java.io.IOException) { }
        assertEquals(0, connected)
        assertEquals(1, disconnected)
        assertNull(session.resource)
    }

    @Test fun manualAudioDisconnectAndBleWearingHaveNoMediaPermission() = runTest {
        val audio = AudioConnectionLifecycle<String>(backgroundScope) {}
        audio.update("buds", 2, true)
        audio.update("buds", 2, false)
        // Still connected to AACP during the three-second grace period.
        assertFalse(audio.allowsComponentSideEffects("buds", controlConnected = true))
        advanceTimeBy(3_000); runCurrent()
        audio.disconnected("buds")
        // BLE NONE -> IN_EAR must not restore audio or pause/play this phone.
        assertFalse(audio.allowsComponentSideEffects("buds", controlConnected = false))
    }

    @Test fun appOwnedAudioReleaseKeepsWearingRestorePermission() = runTest {
        val audio = AudioConnectionLifecycle<String>(backgroundScope) {}
        audio.update("buds", 2, true)
        audio.retainForEarDetection("buds", true)
        audio.update("buds", 2, false)
        advanceTimeBy(4_000); runCurrent()
        assertTrue(audio.allowsComponentSideEffects("buds", controlConnected = true))
        assertFalse(audio.allowsComponentSideEffects("buds", controlConnected = false))
    }

    @Test fun normalManualReconnectRestoresComponentPermission() = runTest {
        val audio = AudioConnectionLifecycle<String>(backgroundScope) {}
        audio.update("buds", 2, false)
        audio.disconnected("buds")
        audio.update("buds", 2, true)
        assertFalse(audio.allowsComponentSideEffects("buds", controlConnected = false))
        assertTrue(audio.allowsComponentSideEffects("buds", controlConnected = true))
    }
}
