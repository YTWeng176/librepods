package me.kavishdevar.librepods.core.utils

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StabilityRegressionTest {
    @Test fun eofStopsReadingEvenWhenTransportStillReportsConnected() = runTest {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("bulk read expected")
            override fun read(buffer: ByteArray): Int {
                reads++
                check(reads == 1) { "EOF was retried" }
                return -1
            }
        }
        readPackets(input, { true }, {}, {})
        assertEquals(1, reads)
    }

    @Test fun ioFailureStopsReading() = runTest {
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("bulk read expected")
            override fun read(buffer: ByteArray): Int {
                reads++
                check(reads == 1) { "failed read was retried" }
                throw IOException("peer disconnected")
            }
        }
        readPackets(input, { true }, {}, {})
        assertEquals(1, reads)
    }

    @Test fun malformedPacketDoesNotStopNextPacket() = runTest {
        var reads = 0
        var received = 0
        var invalid = 0
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun read(buffer: ByteArray): Int {
                if (reads++ >= 2) return -1
                buffer[0] = reads.toByte()
                return 1
            }
        }
        readPackets(input, { true }, {
            received++
            if (it[0] == 1.toByte()) throw IllegalArgumentException("malformed")
        }, { invalid++ })
        assertEquals(2, received)
        assertEquals(1, invalid)
    }

    @Test fun cancellationDuringReadDoesNotDeliverStalePacket() = runTest {
        var received = 0
        lateinit var reader: Job
        val input = object : InputStream() {
            override fun read(): Int = error("bulk read expected")
            override fun read(buffer: ByteArray): Int {
                reader.cancel()
                buffer[0] = 1
                return 1
            }
        }
        reader = backgroundScope.launch(start = CoroutineStart.LAZY) {
            readPackets(input, { true }, { received++ }, {})
        }
        reader.start()
        runCurrent()
        assertEquals(0, received)
    }

    private class Transport : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    @Test fun repeatedConnectUsesOneReaderAndCloseCancelsInit() = runTest {
        val session = SocketSession<Transport>(backgroundScope)
        val socket = Transport()
        var readers = 0
        var initCancelled = false
        val connect = {
            session.connect({ socket }, {
                try { awaitCancellation() } finally { initCancelled = true }
            }, { readers++; awaitCancellation() })
        }
        connect()
        runCurrent()
        connect()
        runCurrent()
        assertEquals(1, readers)
        session.close()
        runCurrent()
        assertTrue(socket.closed)
        assertTrue(initCancelled)
        assertNull(session.resource)
    }

    @Test fun endedReaderClosesSocketAndAllowsFreshConnection() = runTest {
        val session = SocketSession<Transport>(backgroundScope)
        val first = Transport()
        session.connect({ first }, { awaitCancellation() }, {})
        runCurrent()
        assertTrue(first.closed)
        val second = Transport()
        session.connect({ second }, { awaitCancellation() }, { awaitCancellation() })
        runCurrent()
        assertSame(second, session.resource)
        session.close()
    }

    @Test fun scanStartStopAreIdempotentAndZeroDelayDoesNotFlush() = runTest {
        var starts = 0
        var stops = 0
        var flushes = 0
        var timeouts = 0
        val scan = ScanLifecycle(backgroundScope, { starts++ }, { stops++ }, { flushes++ }, { timeouts++ })
        scan.start(0)
        scan.start(0)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(1, starts)
        assertEquals(0, flushes)
        assertTrue(timeouts > 0)
        scan.stop()
        scan.stop()
        assertEquals(1, stops)
        val oldTimeouts = timeouts
        scan.start(500)
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(timeouts > oldTimeouts)
        assertTrue(flushes > 0)
        scan.stop()
    }

    @Test fun stoppedScanDoesNotRetryLater() = runTest {
        var starts = 0
        val scan = ScanLifecycle(backgroundScope, {
            starts++
            throw IllegalStateException("scanning too frequently")
        }, {}, {}, {})
        scan.start(500)
        runCurrent()
        scan.stop()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, starts)
    }

    @Test fun duplicateCaseAdvertisementsAreBoundedAndKeysInvalidateThem() {
        var decryptions = 0
        val cache = AdvertisementCache<String>(2)
        fun resolve(data: Int) = cache.resolve(byteArrayOf(data.toByte())) { decryptions++; "device" }
        assertEquals("device", resolve(1))
        assertEquals("device", resolve(1))
        assertEquals(1, decryptions)
        resolve(2); resolve(3); resolve(1)
        assertEquals(4, decryptions)
        cache.clear()
        resolve(1)
        assertEquals(5, decryptions)
    }

    @Test fun failedCaseDecryptionIsCachedToo() {
        val cache = AdvertisementCache<String>(2)
        var decryptions = 0
        repeat(2) { cache.resolve(byteArrayOf(7)) { decryptions++; null } }
        assertEquals(1, decryptions)
    }

    @Test fun audioDisconnectWaitsForBothProfilesAndGracePeriod() = runTest {
        val closed = mutableListOf<String>()
        val audio = AudioConnectionLifecycle<String>(backgroundScope) { closed += it }
        audio.update("buds", 2, true)
        audio.update("buds", 1, true)
        audio.update("buds", 2, false)
        advanceTimeBy(4_000); runCurrent()
        assertTrue(closed.isEmpty())
        audio.update("buds", 1, false)
        advanceTimeBy(2_999); runCurrent()
        assertTrue(closed.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("buds"), closed)
    }

    @Test fun reconnectAndAppAudioReleaseCancelPendingClose() = runTest {
        val closed = mutableListOf<String>()
        val audio = AudioConnectionLifecycle<String>(backgroundScope) { closed += it }
        audio.update("buds", 2, false)
        advanceTimeBy(2_000)
        audio.update("buds", 1, true)
        advanceTimeBy(4_000); runCurrent()
        assertTrue(closed.isEmpty())
        audio.retainForEarDetection("buds", true)
        audio.update("buds", 1, false)
        advanceTimeBy(4_000); runCurrent()
        assertTrue(closed.isEmpty())
        audio.retainForEarDetection("buds", false)
        audio.update("buds", 2, true)
        audio.update("buds", 2, false)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf("buds"), closed)
        closed.clear()
        audio.update("buds", 2, false)
        audio.clear()
        advanceTimeBy(4_000); runCurrent()
        assertTrue(closed.isEmpty())
    }

    @Test fun duckingNeverRaisesLowListeningVolumeAndStateTwoRestores() {
        var volume = 1
        val queue = ArrayDeque<Runnable>()
        val control = ConversationVolume({ volume }, { 15 }, { volume = it }, { queue += it })
        control.duck(80)
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(1, volume)
        volume = 9
        control.restore()
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(1, volume)
    }

    @Test fun restoreInvalidatesQueuedDuckingSteps() {
        var volume = 10
        val queue = ArrayDeque<Runnable>()
        val control = ConversationVolume({ volume }, { 15 }, { volume = it }, { queue += it })
        control.duck(10)
        assertTrue("Ducking must schedule a transition", queue.isNotEmpty())
        queue.removeFirst().run()
        control.restore()
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(10, volume)
    }

    @Test fun interruptedRestoreKeepsOriginalConversationVolume() {
        var volume = 40
        val queue = ArrayDeque<Runnable>()
        val control = ConversationVolume({ volume }, { 100 }, { volume = it }, { queue += it })
        control.duck(30)
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(30, volume)
        control.restore()
        queue.removeFirst().run()
        assertEquals(31, volume)
        control.duck(10)
        while (queue.isNotEmpty()) queue.removeFirst().run()
        control.restore()
        while (queue.isNotEmpty()) queue.removeFirst().run()
        assertEquals(40, volume)
    }

    @Test fun slowSamplingPreservesAlternatingMotionWhileFastSamplingSmooths() {
        val slow = GestureSmoother()
        slow.sample(900.0, 0.0, 1_000)
        assertEquals(-900.0, slow.sample(-900.0, 0.0, 1_200).first, 0.001)
        val fast = GestureSmoother()
        fast.sample(900.0, 0.0, 1_000)
        assertEquals(0.0, fast.sample(-900.0, 0.0, 1_040).first, 0.001)
        slow.clear()
        assertEquals(300.0, slow.sample(900.0, 0.0, 2_000).first, 0.001)
    }
}
