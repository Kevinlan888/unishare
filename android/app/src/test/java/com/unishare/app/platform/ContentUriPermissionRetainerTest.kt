package com.unishare.app.platform

import android.net.Uri
import com.unishare.app.data.transfer.ContentPermissionException
import com.unishare.app.data.transfer.ContentReadPermissionLease
import com.unishare.app.data.transfer.ContentUriPermissionRetainer
import com.unishare.app.data.transfer.RetainedContentUri
import com.unishare.app.data.transfer.fakeUri
import com.unishare.app.domain.ShareErrorKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

        assertEquals(listOf(uri.toString()), access.releasedStrings)
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
        assertEquals(listOf(uri.toString()), access.releasedStrings)
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

    @Test
    fun ownership_journal_records_before_grant_acquisition_and_removes_after_release() {
        val events = mutableListOf<String>()
        val journal = FakeContentUriGrantJournal(events = events)
        val access = FakePersistableUriPermissionAccess(events = events)
        val uri = fakeUri()
        val retainer = AndroidContentUriPermissionRetainer(access, journal)

        val retained = retainer.retain(uri)
        retained.lease.close()

        assertEquals(
            listOf("journal:record", "access:has", "access:take", "access:release", "journal:remove"),
            events,
        )
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun startup_cleanup_clears_a_record_created_before_a_grant_even_when_no_grant_exists() {
        val uri = fakeUri()
        val journal = FakeContentUriGrantJournal(setOf(uri.toString()))
        val access = FakePersistableUriPermissionAccess().apply {
            releaseFailure = SecurityException("grant was never acquired")
        }

        AndroidContentUriPermissionRetainer(access, journal)

        assertTrue(access.releasedStrings.isEmpty())
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun startup_cleanup_releases_a_recorded_outgoing_grant_without_touching_receive_tree_access() {
        val outgoing = fakeUri()
        val receiveTree = "content://test/receive-tree"
        val journal = FakeContentUriGrantJournal(setOf(outgoing.toString()))
        val access = FakePersistableUriPermissionAccess().apply {
            existing += outgoing.toString()
            existing += receiveTree
        }

        AndroidContentUriPermissionRetainer(access, journal)

        assertEquals(setOf(receiveTree), access.existing)
        assertEquals(listOf(outgoing.toString()), access.releasedStrings)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun startup_cleanup_keeps_the_record_when_a_persisted_grant_release_is_indeterminate() {
        val uri = fakeUri()
        val journal = FakeContentUriGrantJournal(setOf(uri.toString()))
        val access = FakePersistableUriPermissionAccess().apply {
            existing += uri.toString()
            releaseFailure = IllegalStateException("system service temporarily unavailable")
        }

        AndroidContentUriPermissionRetainer(access, journal)

        assertEquals(setOf(uri.toString()), access.existing)
        assertEquals(setOf(uri.toString()), journal.entries)
    }

    @Test
    fun retain_query_failure_keeps_the_journal_until_a_healthy_process_releases_the_orphan() {
        val uri = fakeUri()
        val journal = FakeContentUriGrantJournal(setOf(uri.toString()))
        val access = FakePersistableUriPermissionAccess().apply {
            existing += uri.toString()
            hasFailure = SecurityException("system grant query temporarily unavailable")
        }
        val retainer = AndroidContentUriPermissionRetainer(access, journal)

        val failure = runCatching { retainer.retain(uri) }.exceptionOrNull()

        assertTrue(failure is ContentPermissionException)
        assertEquals(ShareErrorKind.PERMISSION_DENIED, (failure as ContentPermissionException).errorKind)
        assertEquals(setOf(uri.toString()), journal.entries)
        assertTrue(access.taken.isEmpty())
        assertTrue(access.releasedStrings.isEmpty())

        access.hasFailure = null
        AndroidContentUriPermissionRetainer(access, journal)

        assertTrue(access.existing.isEmpty())
        assertEquals(listOf(uri.toString()), access.releasedStrings)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun retain_take_failure_after_platform_effect_keeps_the_journal_for_startup_cleanup() {
        val uri = fakeUri()
        val journal = FakeContentUriGrantJournal()
        val access = FakePersistableUriPermissionAccess().apply {
            takeFailureAfterGrant = SecurityException("provider failed after persisting access")
        }
        val retainer = AndroidContentUriPermissionRetainer(access, journal)

        val failure = runCatching { retainer.retain(uri) }.exceptionOrNull()

        assertTrue(failure is ContentPermissionException)
        assertEquals(ShareErrorKind.PERMISSION_DENIED, (failure as ContentPermissionException).errorKind)
        assertEquals(setOf(uri.toString()), access.existing)
        assertEquals(setOf(uri.toString()), journal.entries)

        access.takeFailureAfterGrant = null
        AndroidContentUriPermissionRetainer(access, journal)

        assertTrue(access.existing.isEmpty())
        assertEquals(listOf(uri.toString()), access.releasedStrings)
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun failed_retain_does_not_install_an_in_memory_grant() {
        val uri = fakeUri()
        val access = FakePersistableUriPermissionAccess().apply {
            takeFailureAfterGrant = SecurityException("provider failed after persisting access")
        }
        val retainer = AndroidContentUriPermissionRetainer(access, FakeContentUriGrantJournal())

        val failure = runCatching { retainer.retain(uri) }.exceptionOrNull()

        assertTrue(failure is ContentPermissionException)
        var recoveryQueries = 0
        access.takeFailureAfterGrant = null
        access.onHas = { recoveryQueries++ }

        val recovered = retainer.retain(uri)

        assertEquals("retry must query the platform rather than reuse an in-memory grant", 1, recoveryQueries)
        assertEquals("the already-persisted grant must not be taken twice", 1, access.taken.size)
        recovered.lease.close()
        assertTrue(access.existing.isEmpty())
    }

    @Test
    fun startup_cleanup_is_idempotent_when_release_completed_before_journal_removal() {
        val uri = fakeUri()
        val journal = FakeContentUriGrantJournal(setOf(uri.toString()))
        val access = FakePersistableUriPermissionAccess()

        AndroidContentUriPermissionRetainer(access, journal)
        AndroidContentUriPermissionRetainer(access, journal)

        assertTrue(access.releasedStrings.isEmpty())
        assertTrue(journal.entries.isEmpty())
    }

    @Test
    fun a_new_lease_cannot_adopt_a_grant_while_the_previous_release_is_in_flight() {
        val uri = fakeUri()
        val releaseStarted = CountDownLatch(1)
        val replacementReachedPlatform = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val access = FakePersistableUriPermissionAccess().apply {
            onRelease = {
                releaseStarted.countDown()
                check(allowRelease.await(5, TimeUnit.SECONDS))
            }
        }
        val retainer = AndroidContentUriPermissionRetainer(access)
        val first = retainer.retain(uri)
        access.onHas = { replacementReachedPlatform.countDown() }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val closing = executor.submit { first.lease.close() }
            assertTrue(releaseStarted.await(5, TimeUnit.SECONDS))
            val retaining = executor.submit(java.util.concurrent.Callable { retainer.retain(uri) })
            val overlappedRelease = replacementReachedPlatform.await(1, TimeUnit.SECONDS)
            allowRelease.countDown()

            closing.get(5, TimeUnit.SECONDS)
            val second = retaining.get(5, TimeUnit.SECONDS)
            assertFalse("replacement acquisition must wait for the prior release", overlappedRelease)
            assertTrue("the replacement lease must still have a platform grant", uri.toString() in access.existing)
            second.lease.close()
        } finally {
            allowRelease.countDown()
            executor.shutdownNow()
        }
    }
}

private class FakePersistableUriPermissionAccess(
    private val events: MutableList<String>? = null,
) : PersistableUriPermissionAccess {
    val taken = mutableListOf<Uri>()
    val released = mutableListOf<Uri>()
    val releasedStrings = mutableListOf<String>()
    val existing = mutableSetOf<String>()
    var hasFailure: RuntimeException? = null
    var takeFailure: RuntimeException? = null
    var takeFailureAfterGrant: RuntimeException? = null
    var releaseFailure: RuntimeException? = null
    var onHas: (() -> Unit)? = null
    var onRelease: (() -> Unit)? = null

    override fun hasPersistedRead(uri: Uri): Boolean {
        events?.add("access:has")
        onHas?.invoke()
        hasFailure?.let { throw it }
        return uri.toString() in existing
    }

    override fun hasPersistedRead(rawUri: String): Boolean {
        events?.add("access:has")
        hasFailure?.let { throw it }
        return rawUri in existing
    }

    override fun takePersistableRead(uri: Uri) {
        events?.add("access:take")
        takeFailure?.let { throw it }
        taken += uri
        existing += uri.toString()
        takeFailureAfterGrant?.let { throw it }
    }

    override fun releasePersistableRead(uri: Uri) {
        events?.add("access:release")
        released += uri
        releasedStrings += uri.toString()
        releaseFailure?.let { throw it }
        onRelease?.invoke()
        existing -= uri.toString()
    }

    override fun releasePersistableRead(rawUri: String) {
        events?.add("access:release")
        releasedStrings += rawUri
        releaseFailure?.let { throw it }
        existing -= rawUri
    }
}

private class FakeContentUriGrantJournal(
    initial: Set<String> = emptySet(),
    private val events: MutableList<String>? = null,
) : ContentUriGrantJournal {
    val entries = initial.toMutableSet()

    override fun recordedUris(): Set<String> = entries.toSet()

    override fun record(uri: String) {
        events?.add("journal:record")
        entries += uri
    }

    override fun remove(uri: String) {
        events?.add("journal:remove")
        entries -= uri
    }
}
