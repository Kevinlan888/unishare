package com.unishare.app.data.content

import android.content.Context
import android.net.Uri
import com.unishare.app.domain.TransferKey
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

data class PreparedStaging(
    val transferId: TransferKey,
    val uri: Uri,
    val file: File,
    val createdAtMillis: Long,
)

/** Opaque, process-owned destinations. Transfer identifiers are never used as paths. */
class StagingStore(context: Context) {
    // Android's legitimate /data/user/0 filesDir alias can canonicalize to
    // /data/data. Resolve that trusted root once; never relax child confinement.
    private val privateRoot = context.filesDir.canonicalFile
    private val directory = File(privateRoot, "receive-staging")
    private val authority = "${context.packageName}.staging"
    private val owner = directory.absolutePath

    fun create(transferId: TransferKey): PreparedStaging = synchronized(lock) {
        ensureDirectory()
        val key = owner to transferId
        active[key]?.let { existing -> if (existing.file.isFile) return@synchronized existing }
        val token = UUID.randomUUID().toString()
        val file = payload(token)
        if (!file.createNewFile()) throw IOException("Could not create private destination")
        val createdAt = System.currentTimeMillis()
        try {
            FileOutputStream(metadata(token)).use { output ->
                val data = DataOutputStream(output)
                data.writeLong(createdAt)
                data.flush()
                output.fd.sync()
            }
        } catch (failure: Exception) {
            file.delete()
            metadata(token).delete()
            throw IOException("Could not prepare private destination", failure)
        }
        PreparedStaging(transferId, Uri.Builder().scheme("content").authority(authority).appendPath(token).build(), file, createdAt)
            .also { active[key] = it }
    }

    fun delete(transferId: TransferKey) = synchronized(lock) {
        active.remove(owner to transferId)?.let { deleteUri(it.uri) }
        Unit
    }

    /** The exact ten-minute boundary expires; payload writes cannot extend it. */
    fun cleanupOrphans(nowMillis: Long) = synchronized(lock) {
        ensureDirectory()
        directory.listFiles().orEmpty().forEach { file ->
            val token = file.name.removeSuffix(".data").removeSuffix(".meta")
            val createdAt = readCreatedAt(token)
            if (!tokenPattern.matches(token) || createdAt == null || !payload(token).isFile ||
                (nowMillis >= createdAt && nowMillis - createdAt >= EXPIRY_MILLIS)) {
                file.delete()
                if (tokenPattern.matches(token)) {
                    payload(token).delete()
                    metadata(token).delete()
                }
            }
        }
        active.entries.removeAll { it.key.first == owner && !it.value.file.isFile }
        Unit
    }

    /** Called once by the provider when a new process starts; old transfers cannot resume. */
    fun cleanupAfterRestart() = synchronized(lock) {
        ensureDirectory()
        directory.listFiles().orEmpty().forEach { it.delete() }
        active.entries.removeAll { it.key.first == owner }
        Unit
    }

    internal fun resolve(uri: Uri): File = synchronized(lock) {
        val token = token(uri) ?: throw FileNotFoundException("Invalid private destination")
        val file = payload(token)
        if (!file.isFile || file.canonicalFile != file.absoluteFile || readCreatedAt(token) == null) {
            throw FileNotFoundException("Private destination unavailable")
        }
        file
    }

    internal fun deleteUri(uri: Uri): Int = synchronized(lock) {
        val token = token(uri) ?: return@synchronized 0
        val file = try { resolve(uri) } catch (_: FileNotFoundException) { return@synchronized 0 }
        if (!file.delete()) return@synchronized 0
        metadata(token).delete()
        active.entries.removeAll { it.key.first == owner && it.value.uri == uri }
        1
    }

    internal fun validate(staging: PreparedStaging) {
        val file = resolve(staging.uri)
        if (file.canonicalFile != staging.file.canonicalFile) throw IOException("Invalid private source")
    }

    private fun ensureDirectory() {
        if (!privateRoot.isDirectory || directory.parentFile != privateRoot ||
            (!directory.isDirectory && !directory.mkdirs()) ||
            directory.canonicalFile != directory.absoluteFile || directory.canonicalFile.parentFile != privateRoot) {
            throw IOException("Private destination unavailable")
        }
    }

    private fun token(uri: Uri): String? {
        if (uri.scheme != "content" || uri.authority != authority || uri.query != null || uri.fragment != null) return null
        val token = uri.encodedPath?.removePrefix("/") ?: return null
        return token.takeIf { tokenPattern.matches(it) && uri.encodedPath == "/$it" }
    }

    private fun payload(token: String) = File(directory, "$token.data")
    private fun metadata(token: String) = File(directory, "$token.meta")
    private fun readCreatedAt(token: String): Long? {
        if (!tokenPattern.matches(token)) return null
        val file = metadata(token)
        return try {
            if (!file.isFile || file.canonicalFile != file.absoluteFile || file.length() != 8L) null
            else DataInputStream(file.inputStream()).use { it.readLong().takeIf { time -> time >= 0 } }
        } catch (_: IOException) { null }
    }

    private companion object {
        const val EXPIRY_MILLIS = 600_000L
        val tokenPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val lock = Any()
        val active = mutableMapOf<Pair<String, TransferKey>, PreparedStaging>()
    }
}
