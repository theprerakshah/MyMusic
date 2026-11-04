package com.prerak.mymusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopGuardTest {
    private val now = 1_000_000L

    @Test
    fun recovers_whenNoCrashHistory() {
        assertTrue(CrashLoopGuard.shouldRecover(emptyList(), now))
    }

    @Test
    fun recovers_belowLimit() {
        assertTrue(CrashLoopGuard.shouldRecover(listOf(now - 2000, now - 1000), now))
    }

    @Test
    fun refuses_atLimitWithinWindow() {
        assertFalse(CrashLoopGuard.shouldRecover(listOf(now - 3000, now - 2000, now - 1000), now))
    }

    @Test
    fun ignores_crashesOutsideWindow() {
        val old = now - CrashLoopGuard.WINDOW_MS
        assertTrue(CrashLoopGuard.shouldRecover(listOf(old - 2000, old - 1000, old), now))
    }

    @Test
    fun ignores_timestampsFromTheFuture() {
        // The clock can move backwards; such entries must not block recovery forever.
        val future = now + 5000
        assertTrue(CrashLoopGuard.shouldRecover(listOf(future, future + 1, future + 2), now))
    }

    @Test
    fun encoding_roundTrips() {
        val times = listOf(1L, 22L, 333L)
        assertEquals(times, CrashLoopGuard.decode(CrashLoopGuard.encode(times)))
    }

    @Test
    fun decode_toleratesMissingOrCorruptData() {
        assertEquals(emptyList<Long>(), CrashLoopGuard.decode(null))
        assertEquals(emptyList<Long>(), CrashLoopGuard.decode(""))
        assertEquals(listOf(5L), CrashLoopGuard.decode("oops,5,,x"))
    }
}
