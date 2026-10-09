package com.unishare.app.data.content

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unishare.app.domain.TransferKey
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StagingRootSafetyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixture = File(context.cacheDir, "staging-root-${UUID.randomUUID()}").apply { mkdirs() }

    @After fun cleanup() {
        // Unlink the test symlink before traversing the fixture.
        File(fixture, "private/receive-staging").delete()
        fixture.deleteRecursively()
    }

    @Test fun actualPrivateRootDiagnosticsAndStagingCreation() {
        val root = context.filesDir
        println("Staging root diagnostics: absolute=${root.absolutePath}; canonical=${root.canonicalPath}")
        val key = TransferKey(UUID.randomUUID().toString())
        val store = StagingStore(context)
        try {
            val staging = store.create(key)
            assertEquals(root.canonicalFile, staging.file.canonicalFile.parentFile!!.parentFile)
        } finally { store.delete(key) }
    }

    @Test fun legalPrivateRootAliasCreatesCanonicalConfinedFile() {
        val privateRoot = File(fixture, "private").apply { mkdirs() }
        val alias = File(privateRoot, ".")
        assertNotEquals(alias.absoluteFile, alias.canonicalFile)
        val store = StagingStore(withFilesDir(alias))
        val key = TransferKey(UUID.randomUUID().toString())
        try {
            val staging = store.create(key)
            assertTrue(staging.file.isFile)
            assertEquals(privateRoot.canonicalFile, staging.file.canonicalFile.parentFile!!.parentFile)
            assertEquals(staging.file.absoluteFile, staging.file.canonicalFile)
        } finally { store.delete(key) }
    }

    @Test fun stagingDirectorySymlinkCannotWriteOutsidePrivateRoot() {
        val privateRoot = File(fixture, "private").apply { mkdirs() }
        val outside = File(fixture, "outside").apply { mkdirs() }
        val sentinel = File(outside, "unchanged").apply { writeText("keep") }
        Os.symlink(outside.absolutePath, File(privateRoot, "receive-staging").absolutePath)
        expectRejected(StagingStore(withFilesDir(privateRoot)))
        assertEquals(listOf("unchanged"), outside.list()!!.toList())
        assertEquals("keep", sentinel.readText())
    }

    @Test fun stagingDirectoryMustBeADirectory() {
        val privateRoot = File(fixture, "private").apply { mkdirs() }
        val blocker = File(privateRoot, "receive-staging").apply { writeText("keep") }
        expectRejected(StagingStore(withFilesDir(privateRoot)))
        assertEquals("keep", blocker.readText())
    }

    @Test fun recoveryCannotDeleteThroughStagingDirectorySymlink() {
        val privateRoot = File(fixture, "private").apply { mkdirs() }
        val outside = File(fixture, "outside").apply { mkdirs() }
        val sentinel = File(outside, "unchanged").apply { writeText("keep") }
        Os.symlink(outside.absolutePath, File(privateRoot, "receive-staging").absolutePath)
        val store = StagingStore(withFilesDir(privateRoot))
        try { store.cleanupAfterRestart() } catch (_: IOException) { }
        assertTrue("Recovery deleted outside the private root", sentinel.isFile)
        try { store.cleanupOrphans(Long.MAX_VALUE) } catch (_: IOException) { }
        assertEquals("keep", sentinel.readText())
    }

    private fun withFilesDir(root: File): Context = object : ContextWrapper(context) {
        override fun getFilesDir(): File = root
    }

    private fun expectRejected(store: StagingStore) {
        try { store.create(TransferKey(UUID.randomUUID().toString())); fail("Unsafe private destination accepted") }
        catch (_: IOException) { }
    }
}
