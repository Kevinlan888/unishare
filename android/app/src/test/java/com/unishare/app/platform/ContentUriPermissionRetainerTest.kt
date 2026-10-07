package com.unishare.app.platform

import android.net.Uri
import com.unishare.app.data.transfer.ContentPermissionException
import com.unishare.app.data.transfer.ContentReadPermissionLease
import com.unishare.app.data.transfer.ContentUriPermissionRetainer
import com.unishare.app.data.transfer.RetainedContentUri
import com.unishare.app.data.transfer.fakeUri
import com.unishare.app.domain.ShareErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentUriPermissionRetainerTest {
    @Test
    fun picker_result_is_retained_before_it_is_delivered_to_session_work() {
        val events = mutableListOf<String>()
        var closed = false
        val retainer = ContentUriPermissionRetainer { uri ->
            events += "retain"
            RetainedContentUri(uri, ContentReadPermissionLease { closed = true })
        }

        deliverPickerResult(
            uri = fakeUri(),
            retainer = retainer,
            onSelected = {
                events += "deliver"
                assertFalse(closed)
                it?.lease?.close()
            },
            onPermissionFailure = { throw AssertionError("unexpected permission failure") },
        )

        assertEquals(listOf("retain", "deliver"), events)
        assertTrue(closed)
    }

    @Test
    fun repeated_selections_share_one_owned_platform_grant_until_the_last_lease_closes() {
        val access = FakePersistableUriPermissionAccess()
        val retainer = AndroidContentUriPermissionRetainer(access)
        val uri = fakeUri()

        val first = retainer.retain(uri)
        val second = retainer.retain(uri)
        first.lease.close()

        assertEquals(listOf(uri.toString()), access.taken.map(Uri::toString))
        assertTrue(access.released.isEmpty())

        second.lease.close()

        assertEquals(listOf(uri.toString()), access.released.map(Uri::toString))
    }

    @Test
    fun an_existing_app_persisted_grant_is_adopted_and_released_when_the_lease_retires() {
        val uri = fakeUri()
        val access = FakePersistableUriPermissionAccess().apply { existing += uri.toString() }
        val retainer = AndroidContentUriPermissionRetainer(access)

        val retained = retainer.retain(uri)
        retained.lease.close()

        assertTrue("an app-owned persisted grant must not leak across the completed lease", access.existing.isEmpty())
        assertTrue(access.taken.isEmpty())
        assertEquals(listOf(uri.toString()), access.released.map(Uri::toString))
    }

    @Test
    fun provider_without_persistable_grants_reports_permission_denied_without_delivery() {
        val access = FakePersistableUriPermissionAccess().apply {
            takeFailure = SecurityException("provider does not support persistence")
        }
        val retainer = AndroidContentUriPermissionRetainer(access)
        var delivered = false
        var error: ShareErrorKind? = null

        deliverPickerResult(
            uri = fakeUri(),
            retainer = retainer,
            onSelected = { delivered = true },
            onPermissionFailure = { error = it },
        )

        assertFalse(delivered)
        assertEquals(ShareErrorKind.PERMISSION_DENIED, error)
        assertTrue(access.released.isEmpty())
    }
}

private class FakePersistableUriPermissionAccess : PersistableUriPermissionAccess {
    val taken = mutableListOf<Uri>()
    val released = mutableListOf<Uri>()
    val existing = mutableSetOf<String>()
    var takeFailure: RuntimeException? = null

    override fun hasPersistedRead(uri: Uri) = uri.toString() in existing

    override fun takePersistableRead(uri: Uri) {
        takeFailure?.let { throw it }
        taken += uri
        existing += uri.toString()
    }

    override fun releasePersistableRead(uri: Uri) {
        released += uri
        existing -= uri.toString()
    }
}
