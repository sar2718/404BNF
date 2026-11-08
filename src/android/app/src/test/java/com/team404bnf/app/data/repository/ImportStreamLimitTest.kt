package com.team404bnf.app.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportStreamLimitTest {
    private class UnknownSizeStream(private var remaining: Long) : InputStream() {
        override fun available() = 0
        override fun read(): Int = if(remaining-- > 0) 0 else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if(remaining == 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
            remaining -= count
            return count
        }
    }
    private class Counter : OutputStream() {
        var bytes = 0L
        override fun write(value: Int) { bytes++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes += length }
    }

    @Test fun `file exactly at selected limit is copied even when size is unknown`() = runBlocking {
        val limit = ImportFileLimit.MB_100
        val output = Counter()
        val copied = copyImportStream(UnknownSizeStream(limit.bytes), output, limit)
        assertEquals(limit.bytes, copied)
        assertEquals(limit.bytes, output.bytes)
    }

    @Test fun `one byte over selected limit fails without writing past the limit`() = runBlocking {
        val limit = ImportFileLimit.MB_100
        val output = Counter()
        try {
            copyImportStream(UnknownSizeStream(limit.bytes + 1), output, limit)
            fail("Oversized content must be rejected regardless of provider metadata")
        } catch (error: AudioImportException) {
            assertTrue(error.message.orEmpty().contains("100 МБ"))
            assertTrue(output.bytes <= limit.bytes)
        }
    }

    @Test fun `copy preserves bytes of a smaller input`() = runBlocking {
        val input = ByteArray(100_001) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val copied = copyImportStream(ByteArrayInputStream(input), output, ImportFileLimit.MB_100)
        assertEquals(input.size.toLong(), copied)
        assertArrayEquals(input, output.toByteArray())
    }

    @Test fun `manual limit is enforced at the exact byte boundary`() = runBlocking {
        val limit = requireNotNull(ImportFileLimit.fromMegabytes("1"))
        val exact = Counter()
        assertEquals(limit.bytes, copyImportStream(UnknownSizeStream(limit.bytes), exact, limit))
        assertEquals(limit.bytes, exact.bytes)
        val oversized = Counter()
        try {
            copyImportStream(UnknownSizeStream(limit.bytes + 1), oversized, limit)
            fail("A custom limit must stop an oversized stream")
        } catch (error: AudioImportException) {
            assertTrue(error.message.orEmpty().contains("1 МБ"))
            assertTrue(oversized.bytes <= limit.bytes)
        }
    }

    @Test fun `unlimited stream can exceed the former one GiB cap`() = runBlocking {
        val size = ImportFileLimit.GB_1.bytes + 1
        val output = Counter()
        assertEquals(size, copyImportStream(UnknownSizeStream(size), output, ImportFileLimit.UNLIMITED))
        assertEquals(size, output.bytes)
    }
}
