package com.unishare.sdk

import android.content.ClipData
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Test-fixture recovery policy shared by the live clipboard test and controlled regressions. */
internal object ClipboardFixtureRetry {
    private fun hostText(clip: ClipData?): String? {
        if (clip == null || clip.description.label?.toString() != "host clipboard" ||
            clip.itemCount != 1 || clip.description.mimeTypeCount != 1 ||
            clip.description.getMimeType(0) != "text/plain") return null
        val item = clip.getItemAt(0)
        if (item.uri != null || item.intent != null) return null
        return item.text?.toString()
    }

    private fun snapshotText(read: OperationResult, current: ClipData?, deadline: Long): String {
        if (!read.isSuccess) throw AssertionError("clipboard read failed: ${read.error?.category}")
        val text = (read.content as? ShareContent.Text)?.text
            ?: throw AssertionError("only successful host text can explain a clipboard replacement")
        if (System.nanoTime() >= deadline) throw AssertionError("clipboard fixture deadline exceeded")
        if (hostText(current) != text) throw AssertionError("SDK text does not match the current host clipboard snapshot")
        return text
    }

    fun requireReplacement(
        read: OperationResult,
        current: ClipData?,
        observed: List<ClipData>,
        deadline: Long,
    ) {
        val text = snapshotText(read, current, deadline)
        if (observed.none { hostText(it) == text }) {
            throw AssertionError("SDK text does not match the current and observed host clipboard replacement")
        }
    }

    fun awaitReplacement(read: OperationResult, current: ClipData?, events: BlockingQueue<ClipData>, deadline: Long) {
        // Validate the SDK result first: errors/wrong contents never wait or rebuild.
        snapshotText(read, current, deadline)
        val observed = mutableListOf<ClipData>()
        events.drainTo(observed)
        if (observed.isEmpty()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw AssertionError("clipboard fixture deadline exceeded")
            events.poll(remaining, TimeUnit.NANOSECONDS)?.let(observed::add)
        }
        requireReplacement(read, current, observed, deadline)
    }

    @JvmStatic
    fun assertContracts() {
        val host = ClipData.newPlainText("host clipboard", "external 世界")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        fun rejected(read: OperationResult, current: ClipData? = host,
                     observed: List<ClipData> = listOf(host), until: Long = deadline) {
            var failed = false
            try { requireReplacement(read, current, observed, until) }
            catch (_: AssertionError) { failed = true }
            catch (_: IllegalStateException) { failed = true }
            check(failed) { "fixture must fail immediately instead of rebuilding: ${read.error?.category}, ${read.content?.javaClass?.simpleName}" }
            val events = LinkedBlockingQueue<ClipData>().apply { addAll(observed) }
            // Invalid/missing/stale event cases are checked by the non-waiting policy;
            // error/type/content/deadline failures must also be immediate on the live event path.
            if (observed.isNotEmpty() || !read.isSuccess || read.content !is ShareContent.Text || until < System.nanoTime()) {
                failed = false
                try { awaitReplacement(read, current, events, until) }
                catch (_: AssertionError) { failed = true }
                check(failed) { "live event barrier must not swallow an invalid SDK result" }
            }
        }
        // Every SDK error must survive even if a genuine host event occurred concurrently.
        for (category in ErrorCategory.values()) {
            rejected(OperationResult(1, error = ShareError(category)))
        }
        rejected(OperationResult(1, content = ShareContent.Image("image/png", byteArrayOf(1, 2))))
        rejected(OperationResult(1))
        rejected(OperationResult(1, content = ShareContent.Text("corrupt or unrelated")))
        val external = OperationResult(1, content = ShareContent.Text("external 世界"))
        rejected(external, current = ClipData.newPlainText("another writer", "external 世界"))
        rejected(external, observed = emptyList())
        rejected(external, observed = listOf(ClipData.newPlainText("host clipboard", "stale event")))
        rejected(external, current = ClipData.newIntent("host clipboard", android.content.Intent("fixture")))
        rejected(external, until = System.nanoTime() - 1)
        // A precisely matching, observed host text replacement is admitted within the deadline.
        requireReplacement(external, host, listOf(host), deadline)
        awaitReplacement(external, host, LinkedBlockingQueue<ClipData>().apply { add(host) }, deadline)
    }
}
