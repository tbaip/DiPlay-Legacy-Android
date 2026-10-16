package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBtTimingTest {

    private class FakeClock {
        var now: Long = 1_000L
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun successOnFirstAttempt() {
        val clock = FakeClock()
        val sleeps = mutableListOf<Long>()
        val (result, timing) = UsbBtTiming.runTimed<ByteArray>(
            name = "cmd",
            policy = UsbBtTiming.RetryPolicy(timeoutMs = 500, maxAttempts = 3, backoffBaseMs = 100),
            sleeper = { sleeps.add(it); clock.advance(it) },
            clock = { clock.now },
            block = { Result.success(byteArrayOf(0x00)) },
        )
        assertTrue(result.isSuccess)
        assertEquals(1, timing.attempts)
        assertTrue(timing.success)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun retriesWithExponentialBackoff() {
        val clock = FakeClock()
        val sleeps = mutableListOf<Long>()
        var calls = 0
        val (result, timing) = UsbBtTiming.runTimed<ByteArray>(
            name = "cmd",
            policy = UsbBtTiming.RetryPolicy(
                timeoutMs = 500,
                maxAttempts = 3,
                backoffBaseMs = 100,
                backoffMultiplier = 2.0,
            ),
            sleeper = { sleeps.add(it); clock.advance(it) },
            clock = { clock.now },
            block = {
                calls++
                if (calls < 3) Result.failure(RuntimeException("boom $calls"))
                else Result.success(byteArrayOf(0x00))
            },
        )
        assertTrue(result.isSuccess)
        assertEquals(3, timing.attempts)
        // Backoff before attempt 2 = 100ms, before attempt 3 = 200ms.
        assertEquals(listOf(100L, 200L), sleeps)
    }

    @Test
    fun givesUpAfterMaxAttempts() {
        val clock = FakeClock()
        val sleeps = mutableListOf<Long>()
        val (result, timing) = UsbBtTiming.runTimed<ByteArray>(
            name = "cmd",
            policy = UsbBtTiming.RetryPolicy(timeoutMs = 500, maxAttempts = 2, backoffBaseMs = 50),
            sleeper = { sleeps.add(it); clock.advance(it) },
            clock = { clock.now },
            block = { Result.failure(RuntimeException("always fails")) },
        )
        assertTrue(result.isFailure)
        assertEquals(2, timing.attempts)
        assertFalse(timing.success)
        assertEquals("always fails", timing.lastError)
        assertEquals(listOf(50L), sleeps)
    }

    @Test
    fun backoffSchedule() {
        val p = UsbBtTiming.RetryPolicy(timeoutMs = 1_000, maxAttempts = 4, backoffBaseMs = 250, backoffMultiplier = 2.0)
        assertEquals(0L, p.backoffFor(0))
        assertEquals(250L, p.backoffFor(1))
        assertEquals(500L, p.backoffFor(2))
        assertEquals(1000L, p.backoffFor(3))
    }

    @Test
    fun timingReportText() {
        val report = UsbBtTiming.TimingReport(
            steps = listOf(
                UsbBtTiming.StepTiming("HCI_Reset", 120L, 1, true, null),
                UsbBtTiming.StepTiming("Read_BD_ADDR", 2500L, 2, false, "timeout"),
            ),
            totalMs = 2620L,
        )
        assertFalse(report.allOk)
        val text = report.toText()
        assertTrue(text.contains("HCI_Reset"))
        assertTrue(text.contains("OK"))
        assertTrue(text.contains("FAIL"))
        assertTrue(text.contains("attempts=2"))
    }

    @Test
    fun defaultPoliciesAreSane() {
        // Reset: the canary — a few quick attempts, then firmware recovery.
        assertEquals(3, UsbBtTiming.RESET_POLICY.maxAttempts)
        assertTrue(UsbBtTiming.RESET_POLICY.timeoutMs >= 1_000)
        // Reads: one retry. Writes: single attempt.
        assertEquals(2, UsbBtTiming.READ_POLICY.maxAttempts)
        assertEquals(1, UsbBtTiming.WRITE_POLICY.maxAttempts)
    }
}
