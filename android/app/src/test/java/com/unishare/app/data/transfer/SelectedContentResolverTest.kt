package com.unishare.app.data.transfer

import android.net.Uri
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.ShareErrorKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SelectedContentResolverTest {
    @Test
    fun blank_name_is_normalized_without_reading_payload_bytes() {
        val uri = fakeUri()
        val source = FakeMetadataSource(RawSelectedContent("  ", "image/png", 42))

        val result = SelectedContentResolver(source).resolve(uri, ContentKind.IMAGE)

        assertEquals("selected-image", result.displayName)
        assertEquals(42L, result.byteCount)
        assertSame(uri, result.uri)
        assertEquals(1, source.reads)
    }

    @Test
    fun path_segments_and_control_characters_are_removed_from_display_name() {
        val result = SelectedContentResolver(FakeMetadataSource(
            RawSelectedContent("C:\\private/path/evil\u0000.jpg", "image/jpeg", 12),
        )).resolve(fakeUri(), ContentKind.IMAGE)

        assertEquals("evil.jpg", result.displayName)
    }

    @Test
    fun unknown_or_negative_exact_size_is_rejected() {
        for (size in listOf(null, -1L)) {
            val failure = expectFailure {
                SelectedContentResolver(FakeMetadataSource(RawSelectedContent("file.bin", "application/octet-stream", size)))
                    .resolve(fakeUri(), ContentKind.FILE)
            }
            assertEquals(ShareErrorKind.INVALID_INPUT, failure.errorKind)
        }
    }

    @Test
    fun image_selection_requires_an_image_mime_type() {
        val failure = expectFailure {
            SelectedContentResolver(FakeMetadataSource(RawSelectedContent("fake.jpg", "application/pdf", 10)))
                .resolve(fakeUri(), ContentKind.IMAGE)
        }

        assertEquals(ShareErrorKind.INVALID_INPUT, failure.errorKind)
    }

    @Test
    fun revoked_uri_is_reported_as_storage_failure_without_retaining_provider_details() {
        val source = ThrowingMetadataSource(SecurityException("content://secret/path"))
        val failure = expectFailure { SelectedContentResolver(source).resolve(fakeUri(), ContentKind.FILE) }

        assertEquals(ShareErrorKind.STORAGE_FAILED, failure.errorKind)
        assertEquals("STORAGE_FAILED", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun provider_exception_does_not_retain_message_path_or_cause() {
        val source = ThrowingMetadataSource(IllegalStateException("/storage/emulated/0/private.txt"))
        val failure = expectFailure { SelectedContentResolver(source).resolve(fakeUri(), ContentKind.FILE) }

        assertEquals(ShareErrorKind.STORAGE_FAILED, failure.errorKind)
        assertEquals("STORAGE_FAILED", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun retirement_cancels_a_blocking_metadata_open_and_closes_its_session() {
        val session = BlockingMetadataSession()
        val resolver = SelectedContentResolver(SelectedContentMetadataSource { session })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<SelectedContent> { resolver.resolve(fakeUri(), ContentKind.FILE) }
            assertTrue(session.started.await(2, TimeUnit.SECONDS))

            resolver.close()

            try {
                result.get(2, TimeUnit.SECONDS)
                fail("resolution should be retired")
            } catch (_: Exception) {
                // The stable resolver exception is wrapped by Future.get.
            }
            assertTrue(session.cancelled)
            assertTrue(session.closed)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun expectFailure(block: () -> Unit): SelectedContentException = try {
        block()
        throw AssertionError("Expected SelectedContentException")
    } catch (failure: SelectedContentException) {
        failure
    }
}

internal fun fakeUri(): Uri {
    val implementation = Class.forName("android.net.TestUri")
    return implementation.getMethod("create").invoke(null) as Uri
}

private class FakeMetadataSource(private val metadata: RawSelectedContent) : SelectedContentMetadataSource {
    var reads = 0
    override fun open(): SelectedContentMetadataSession = object : SelectedContentMetadataSession {
        override fun read(uri: Uri): RawSelectedContent { reads++; return metadata }
        override fun cancel() = Unit
        override fun close() = Unit
    }
}

private class ThrowingMetadataSource(private val failure: RuntimeException) : SelectedContentMetadataSource {
    override fun open(): SelectedContentMetadataSession = object : SelectedContentMetadataSession {
        override fun read(uri: Uri): RawSelectedContent = throw failure
        override fun cancel() = Unit
        override fun close() = Unit
    }
}

private class BlockingMetadataSession : SelectedContentMetadataSession {
    val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    @Volatile var cancelled = false
    @Volatile var closed = false

    override fun read(uri: Uri): RawSelectedContent {
        started.countDown()
        release.await()
        if (cancelled) throw IllegalStateException("cancelled provider path")
        return RawSelectedContent("file.bin", "application/octet-stream", 1)
    }

    override fun cancel() { cancelled = true; release.countDown() }
    override fun close() { closed = true }
}
