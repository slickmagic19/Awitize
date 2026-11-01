package com.awitize.app

import com.awitize.app.data.copyWithProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

// JVM checks of the byte-streaming helper Downloads.save() uses to report
// progress — every branch the phone hits while saving or resuming a track.
class DownloadProgressTest {

    @Test
    fun copiesEverythingAndReportsUpTo100Percent() {
        val payload = ByteArray(250_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Long>()
        val copied = copyWithProgress(ByteArrayInputStream(payload), out, payload.size.toLong()) { seen.add(it) }
        assertEquals(payload.size.toLong(), copied)
        assertEquals(payload.size, out.size())
        assertTrue("expected progress updates", seen.isNotEmpty())
        assertEquals("final report must be the full size", payload.size.toLong(), seen.last())
        assertTrue("progress must be monotonic", seen.zipWithNext().all { (a, b) -> b >= a })
        assertTrue("at most one update per percent", seen.size <= 101)
    }

    @Test
    fun unknownSizeCopiesWithoutReporting() {
        val payload = ByteArray(123_456) { 7 }
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Long>()
        val copied = copyWithProgress(ByteArrayInputStream(payload), out, -1L) { seen.add(it) }
        assertEquals(payload.size.toLong(), copied)
        assertEquals(payload.size, out.size())
        assertTrue("no progress should be reported when the size is unknown", seen.isEmpty())
    }

    @Test
    fun tinyReadsStillComplete() {
        val payload = ByteArray(50_000) { (it * 31 % 256).toByte() }
        val drip = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < payload.size) payload[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= payload.size) return -1
                val n = minOf(len, 7, payload.size - pos)
                System.arraycopy(payload, pos, b, off, n)
                pos += n
                return n
            }
        }
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Long>()
        val copied = copyWithProgress(drip, out, payload.size.toLong()) { seen.add(it) }
        assertEquals(payload.size.toLong(), copied)
        assertEquals(payload.size, out.size())
        assertEquals(payload.size.toLong(), seen.last())
    }

    @Test
    fun resumedCopyAccountsForTheOffset() {
        val payload = ByteArray(120_000) { 3 }
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Long>()
        val copied = copyWithProgress(ByteArrayInputStream(payload), out, 200_000L, 80_000L) { seen.add(it) }
        assertEquals(120_000L, copied)
        assertEquals(120_000, out.size())
        assertTrue("first report must never go below the resume point", seen.first() >= 80_000L)
        assertEquals("final report must be the full size", 200_000L, seen.last())
        assertTrue("progress must be monotonic", seen.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun emptyStreamAtFullOffsetReportsDone() {
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Long>()
        val copied = copyWithProgress(ByteArrayInputStream(ByteArray(0)), out, 500L, 500L) { seen.add(it) }
        assertEquals(0L, copied)
        assertEquals("a fully-resumed file must report completion", 500L, seen.last())
    }

    @Test
    fun capturesAllBytesEvenWhenServerLiedAboutSize() {
        val out = ByteArrayOutputStream()
        val seen = mutableListOf<Long>()
        copyWithProgress(ByteArrayInputStream(ByteArray(1000)), out, 100L) { seen.add(it) }
        assertEquals("all copied bytes must be reported, UI clamps the display", 1000L, seen.last())
        assertEquals(1000, out.size())
    }
}
