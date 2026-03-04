package com.localshare.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class FileTransferServer(
    private val context: Context,
    private val pin: String,
    private val sharedFiles: MutableList<SharedFileEntry>,
    private val logCallback: (String) -> Unit,
    port: Int = 8080
) : NanoHTTPD(port) {

    companion object {
        const val BUFFER_SIZE         = 256 * 1024           // 256 KB I/O buffer
        const val SESSION_TTL_MS      = 15 * 60 * 1000L      // 15-min session
        const val APP_FOLDER          = "Share Pannu"         // public sub-folder name
        const val BASE_PATH           = "Download/$APP_FOLDER" // MediaStore RELATIVE_PATH base
        const val MAX_FAIL_ATTEMPTS   = 5                     // brute-force lockout threshold
        const val LOCKOUT_DURATION_MS = 60_000L               // 60-second IP lockout
    }

    /**
     * IP-bound session store: token → Pair(clientIp, creationTimestamp)
     * A token is only valid from the IP that authenticated it.
     */
    private val sessions = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Per-IP failed-auth tracking: ip → Pair(failCount, firstFailTimestamp)
     * After MAX_FAIL_ATTEMPTS the IP receives a 429 for LOCKOUT_DURATION_MS.
     */
    private val failedAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()

    /**
     * API 26-28 fallback: maps a stable numeric id → File so that
     * /phone-download/<id> can look up the file after servePhoneFiles populates the map.
     */
    private val legacyFileMap = ConcurrentHashMap<Long, File>()

    // ─────────────────────────────────────────────
    // Main router
    // ─────────────────────────────────────────────
    override fun serve(session: IHTTPSession): Response {
        cleanExpiredSessions()
        val uri    = session.uri
        val method = session.method
        log("→ ${method.name} $uri")

        return when {
            uri == "/" && method == Method.GET                         -> serveWebUi()
            uri == "/auth" && method == Method.POST                    -> handleAuth(session)
            uri == "/files" && method == Method.GET                    -> requireAuth(session) { serveFileList() }
            uri.startsWith("/download/") && method == Method.GET       -> requireAuth(session) { handleDownload(uri) }
            uri.startsWith("/hash/") && method == Method.GET           -> requireAuth(session) { handleHash(uri) }
            uri == "/upload" && method == Method.POST                  -> requireAuth(session) { handleUpload(session) }
            uri == "/phone-files" && method == Method.GET              -> requireAuth(session) { servePhoneFiles() }
            uri.startsWith("/phone-download/") && method == Method.GET -> requireAuth(session) { handlePhoneDownload(uri) }
            method == Method.OPTIONS                                   -> handleOptions()
            else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    // ─────────────────────────────────────────────
    // Auth middleware
    // ─────────────────────────────────────────────
    private fun requireAuth(session: IHTTPSession, block: () -> Response): Response {
        val headerToken = session.headers["x-auth-token"] ?: ""
        val queryToken  = session.parms["token"]          ?: ""
        val token = if (headerToken.isNotEmpty()) headerToken else queryToken
        val clientIp = session.headers["http-client-ip"]
            ?: session.headers["remote-addr"]
            ?: "unknown"

        val record = sessions[token]
        if (token.isEmpty() || record == null ||
            System.currentTimeMillis() - record.second > SESSION_TTL_MS) {
            sessions.remove(token)
            return newFixedLengthResponse(Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }
        // IP-binding: reject if request comes from a different IP than the authenticating one
        if (record.first != clientIp) {
            log("[⚠] AUTH token IP mismatch: expected ${record.first}, got $clientIp")
            return newFixedLengthResponse(Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }
        return block()
    }

    private fun cleanExpiredSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.second > SESSION_TTL_MS }
        failedAttempts.entries.removeIf { now - it.value.second > LOCKOUT_DURATION_MS }
    }

    // ─────────────────────────────────────────────
    // POST /auth
    // ─────────────────────────────────────────────
    private fun handleAuth(session: IHTTPSession): Response {
        val clientIp = session.headers["http-client-ip"]
            ?: session.headers["remote-addr"]
            ?: "unknown"
        val now = System.currentTimeMillis()

        // ── Rate limit check ──
        val failRecord = failedAttempts[clientIp]
        if (failRecord != null) {
            val (count, firstFail) = failRecord
            val elapsed = now - firstFail
            if (count >= MAX_FAIL_ATTEMPTS && elapsed < LOCKOUT_DURATION_MS) {
                val retryAfterSec = ((LOCKOUT_DURATION_MS - elapsed) / 1000).coerceAtLeast(1)
                log("[⚠] AUTH — IP $clientIp locked out ($count failures, ${retryAfterSec}s remaining)")
                val resp = corsResponse(newFixedLengthResponse(
                    Response.Status.lookup(429), "application/json",
                    JSONObject()
                        .put("error", "Too many failed attempts. Try again in ${retryAfterSec}s.")
                        .put("retryAfter", retryAfterSec)
                        .toString()))
                resp.addHeader("Retry-After", retryAfterSec.toString())
                return resp
            }
            if (elapsed >= LOCKOUT_DURATION_MS) failedAttempts.remove(clientIp)
        }

        // Read JSON body directly from inputStream — parseBody() is unreliable for
        // application/json and can silently return empty postData in NanoHTTPD
        val contentLength = session.headers["content-length"]?.toIntOrNull()?.coerceIn(0, 4096) ?: 0
        val raw = if (contentLength > 0) {
            session.inputStream.readNBytes(contentLength).toString(Charsets.UTF_8)
        } else ""
        val submitted = try { JSONObject(raw).getString("pin").trim() } catch (_: Exception) { "" }

        return if (submitted == pin) {
            failedAttempts.remove(clientIp)
            val token = UUID.randomUUID().toString()
            sessions[token] = Pair(clientIp, now)
            log("[✓] AUTH Success — Device authenticated ($clientIp)")
            corsResponse(newFixedLengthResponse(Status.OK, "application/json",
                JSONObject().put("token", token).toString()))
        } else {
            val current = failedAttempts[clientIp]
            if (current == null || now - current.second >= LOCKOUT_DURATION_MS) {
                failedAttempts[clientIp] = Pair(1, now)
            } else {
                failedAttempts[clientIp] = Pair(current.first + 1, current.second)
            }
            val remaining = MAX_FAIL_ATTEMPTS - (failedAttempts[clientIp]?.first ?: 1)
            log("[✗] AUTH Failed — Wrong PIN from $clientIp (${failedAttempts[clientIp]?.first}/$MAX_FAIL_ATTEMPTS)")
            corsResponse(newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                JSONObject()
                    .put("error", "Wrong PIN")
                    .put("attemptsRemaining", remaining.coerceAtLeast(0))
                    .toString()))
        }
    }

    // ─────────────────────────────────────────────
    // GET / — serve embedded web UI
    // ─────────────────────────────────────────────
    private fun serveWebUi(): Response {
        val html = context.assets.open("index.html").bufferedReader().readText()
        return corsResponse(newFixedLengthResponse(Status.OK, "text/html", html))
    }

    // ─────────────────────────────────────────────
    // GET /files — phone-shared files (phone→laptop)
    // ─────────────────────────────────────────────
    private fun serveFileList(): Response {
        val arr = JSONArray()
        synchronized(sharedFiles) {
            for (entry in sharedFiles) {
                arr.put(JSONObject()
                    .put("name", entry.name)
                    .put("size", entry.size)
                    .put("hash", entry.sha256 ?: ""))
            }
        }
        return corsResponse(newFixedLengthResponse(Status.OK, "application/json", arr.toString()))
    }

    // ─────────────────────────────────────────────
    // GET /phone-files — list received files from public MediaStore storage.
    // Returns JSON: [{id, name, size, mime, modified, subfolder}, …]
    // id is MediaStore _id (API 29+) or a stable hash (API 26-28).
    // ─────────────────────────────────────────────
    private fun servePhoneFiles(): Response {
        val arr = JSONArray()
        legacyFileMap.clear()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            servePhoneFilesMediaStore(arr)
        } else {
            servePhoneFilesLegacy(arr)
        }

        return corsResponse(newFixedLengthResponse(Status.OK, "application/json", arr.toString()))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun servePhoneFilesMediaStore(arr: JSONArray) {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.RELATIVE_PATH
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val selArgs   = arrayOf("$BASE_PATH/%")
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        context.contentResolver.query(
            collection, projection, selection, selArgs,
            "${MediaStore.Downloads.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            val modCol  = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val id        = cursor.getLong(idCol)
                val relPath   = cursor.getString(pathCol) ?: ""
                val subfolder = relPath.removePrefix("$BASE_PATH/").trimEnd('/')
                arr.put(JSONObject()
                    .put("id",       id)
                    .put("name",     cursor.getString(nameCol) ?: "")
                    .put("size",     cursor.getLong(sizeCol))
                    .put("mime",     cursor.getString(mimeCol) ?: "application/octet-stream")
                    .put("modified", cursor.getLong(modCol) * 1000L) // epoch_s → epoch_ms
                    .put("subfolder", subfolder))
            }
        }
    }

    private fun servePhoneFilesLegacy(arr: JSONArray) {
        val shareDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            APP_FOLDER
        )
        if (!shareDir.exists()) return

        shareDir.walkTopDown()
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .forEach { f ->
                // Stable positive id: use absolute path hashCode masked to long
                val id = (f.absolutePath.hashCode().toLong() and 0x7FFF_FFFF_FFFF_FFFFL)
                legacyFileMap[id] = f
                val subfolder = f.parentFile?.name ?: "Others"
                arr.put(JSONObject()
                    .put("id",       id)
                    .put("name",     f.name)
                    .put("size",     f.length())
                    .put("mime",     getMimeType(f.name))
                    .put("modified", f.lastModified())
                    .put("subfolder", subfolder))
            }
    }

    // ─────────────────────────────────────────────
    // GET /phone-download/<id> — stream received file back to laptop
    // ─────────────────────────────────────────────
    private fun handlePhoneDownload(uri: String): Response {
        val idStr = Uri.decode(uri.removePrefix("/phone-download/"))
        val id    = idStr.toLongOrNull()
            ?: return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid ID")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            handlePhoneDownloadMediaStore(id)
        } else {
            handlePhoneDownloadLegacy(id)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handlePhoneDownloadMediaStore(id: Long): Response {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val fileUri    = ContentUris.withAppendedId(collection, id)

        // Resolve display name and size for headers
        val (name, size) = context.contentResolver.query(
            fileUri,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) Pair(c.getString(0) ?: "file", c.getLong(1))
            else Pair("file", 0L)
        } ?: Pair("file", 0L)

        return try {
            val stream = context.contentResolver.openInputStream(fileUri)
                ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
            log("[⇄] GET /phone-download → $name (${fmtSize(size)})")
            val resp = newChunkedResponse(Status.OK, "application/octet-stream",
                BufferedInputStream(stream, BUFFER_SIZE))
            resp.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
            if (size > 0) resp.addHeader("Content-Length", size.toString())
            resp.addHeader("Cache-Control", "no-cache")
            corsResponse(resp)
        } catch (e: Exception) {
            log("[✗] Phone download error: ${e.message}")
            newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }
    }

    private fun handlePhoneDownloadLegacy(id: Long): Response {
        val file = legacyFileMap[id]
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT,
                "File not found — refresh the list and try again")
        if (!file.exists()) {
            legacyFileMap.remove(id)
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }
        return try {
            log("[⇄] GET /phone-download → ${file.name} (${fmtSize(file.length())})")
            val stream = BufferedInputStream(FileInputStream(file), BUFFER_SIZE)
            val resp   = newChunkedResponse(Status.OK, "application/octet-stream", stream)
            resp.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
            resp.addHeader("Content-Length", file.length().toString())
            resp.addHeader("Cache-Control", "no-cache")
            corsResponse(resp)
        } catch (e: Exception) {
            log("[✗] Phone download error: ${e.message}")
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────
    // GET /download/<name> — phone-shared file → laptop
    // ─────────────────────────────────────────────
    private fun handleDownload(uri: String): Response {
        val name  = Uri.decode(uri.removePrefix("/download/"))
        val entry = synchronized(sharedFiles) { sharedFiles.find { it.name == name } }
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        log("[⇄] GET /download → $name (${fmtSize(entry.size)})")
        return try {
            val stream = context.contentResolver.openInputStream(entry.uri)
                ?: return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Cannot open file")
            val resp = newChunkedResponse(Status.OK, "application/octet-stream",
                BufferedInputStream(stream, BUFFER_SIZE))
            resp.addHeader("Content-Disposition", "attachment; filename=\"$name\"")
            resp.addHeader("Content-Length", entry.size.toString())
            resp.addHeader("Cache-Control", "no-cache")
            corsResponse(resp)
        } catch (e: Exception) {
            log("[✗] Download error: ${e.message}")
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────
    // GET /hash/<name>
    // ─────────────────────────────────────────────
    private fun handleHash(uri: String): Response {
        val name  = Uri.decode(uri.removePrefix("/hash/"))
        val entry = synchronized(sharedFiles) { sharedFiles.find { it.name == name } }
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

        val hash = entry.sha256 ?: computeHash(entry.uri)
        synchronized(sharedFiles) { sharedFiles.find { it.name == name }?.sha256 = hash }
        return corsResponse(newFixedLengthResponse(Status.OK, "application/json",
            JSONObject().put("name", name).put("sha256", hash).toString()))
    }

    // ─────────────────────────────────────────────
    // POST /upload — receive laptop→phone file, save to public storage
    //
    // Saves to: Downloads/Share Pannu/<Type>/<filename>
    //   • API 29+  → MediaStore.Downloads with IS_PENDING=1 → write → IS_PENDING=0
    //   • API 26-28 → direct File write + MediaScannerConnection
    //
    // SHA-256 is computed inline (single-pass) during write — no extra seek needed.
    // On any failure the partial entry/file is cleaned up before returning error.
    // ─────────────────────────────────────────────
    private fun handleUpload(session: IHTTPSession): Response {
        val contentLengthStr = session.headers["content-length"] ?: ""
        val contentLength: Long = contentLengthStr.toLongOrNull() ?: -1L

        if (contentLength < 0) {
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT,
                "Missing or invalid Content-Length")
        }

        val rawName  = session.headers["x-filename"] ?: session.parms["filename"] ?: ""
        val baseName = sanitizeFileName(
            if (rawName.isNotEmpty()) Uri.decode(rawName)
            else "upload_${System.currentTimeMillis()}"
        )
        val mime      = getMimeType(baseName)
        val subfolder = subfolderFor(mime)

        log("[⇄] POST /upload → $baseName (${fmtSize(contentLength)}) — Receiving… [→ $APP_FOLDER/$subfolder]")

        val uploadStart = System.currentTimeMillis()
        return try {
            val result  = saveToPublicStorage(baseName, mime, session.inputStream, contentLength)
            val elapsed = (System.currentTimeMillis() - uploadStart).coerceAtLeast(1L)
            val speed   = result.size.toDouble() / elapsed * 1000.0 / (1024.0 * 1024.0)
            log("[✓] POST /upload → ${result.name} (${fmtSize(result.size)}) — Completed (${"%.1f".format(speed)} MB/s) → $subfolder")

            corsResponse(newFixedLengthResponse(Status.OK, "application/json",
                JSONObject()
                    .put("success",   true)
                    .put("name",      result.name)
                    .put("size",      result.size)
                    .put("sha256",    result.sha256)
                    .put("subfolder", subfolder)
                    .toString()))
        } catch (e: Exception) {
            log("[✗] Upload error: ${e.message}")
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT,
                "Upload error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────
    // Storage layer
    // ─────────────────────────────────────────────
    private data class SaveResult(val name: String, val uri: Uri,
                                   val size: Long, val sha256: String?)

    private fun saveToPublicStorage(
        baseName: String, mime: String, inputStream: InputStream, contentLength: Long
    ): SaveResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveViaMediaStore(baseName, mime, inputStream, contentLength)
    } else {
        saveViaDirectFile(baseName, mime, inputStream, contentLength)
    }

    // API 29+ — MediaStore.Downloads with IS_PENDING lock
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        baseName: String, mime: String, inputStream: InputStream, contentLength: Long
    ): SaveResult {
        val subfolder    = subfolderFor(mime)
        val relativePath = "$BASE_PATH/$subfolder"
        val finalName    = resolveMediaStoreName(baseName, relativePath)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, finalName)
            put(MediaStore.Downloads.MIME_TYPE,    mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING,   1)  // lock: invisible until write completes
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri    = context.contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore insert failed for $finalName")

        return try {
            // Raw stream → MediaStore output — no inline hashing (computed lazily on /hash)
            val bytesWritten = java.io.BufferedOutputStream(context.contentResolver.openOutputStream(itemUri)!!, StreamUtils.BUFFER_SIZE).use { out ->
                StreamUtils.streamWithSpeedTracking(
                    inputStream, out, contentLength, finalName, "Upload"
                ) { msg -> log(msg) }
            }

            context.contentResolver.update(itemUri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null, null)

            SaveResult(finalName, itemUri, bytesWritten, null) // hash computed lazily on /hash

        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(itemUri, null, null) }
            throw e
        }
    }

    /**
     * Resolves a conflict-free filename in the given relative path.
     * If "photo.jpg" exists → tries "photo (1).jpg", "photo (2).jpg" …
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolveMediaStoreName(baseName: String, relativePath: String): String {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
        val selection  = "${MediaStore.Downloads.RELATIVE_PATH} = ? AND " +
                         "${MediaStore.Downloads.DISPLAY_NAME} = ?"

        fun exists(name: String): Boolean =
            context.contentResolver.query(
                collection, projection, selection,
                arrayOf("$relativePath/", name), null
            )?.use { it.count > 0 } ?: false

        if (!exists(baseName)) return baseName
        val dot  = baseName.lastIndexOf('.')
        val base = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext  = if (dot > 0) baseName.substring(dot)    else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
    }

    // API 26-28 — write to public Downloads via File API, then scan
    private fun saveViaDirectFile(
        baseName: String, mime: String, inputStream: InputStream, contentLength: Long
    ): SaveResult {
        val subfolder  = subfolderFor(mime)
        val destDir    = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "$APP_FOLDER/$subfolder"
        ).also { it.mkdirs() }
        val destFile   = uniqueFile(destDir, baseName)  // conflict-safe

        val md = MessageDigest.getInstance("SHA-256")
        var bytesWritten = 0L

        try {
            // Wrap the output stream with BufferedOutputStream BEFORE DigestOutputStream for optimal batch hashing
            val bufferedOut = java.io.BufferedOutputStream(destFile.outputStream(), StreamUtils.BUFFER_SIZE)
            val digestOut = java.security.DigestOutputStream(bufferedOut, md)
            
            bytesWritten = StreamUtils.streamWithSpeedTracking(
                inputStream, digestOut, contentLength, destFile.name, "Upload"
            ) { msg -> log(msg) }
        } catch (e: Exception) {
            destFile.delete()   // rollback partial file
            throw e
        }

        // Make visible to the Files app and other apps
        MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mime), null)
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        return SaveResult(destFile.name, Uri.fromFile(destFile), bytesWritten, hash)
    }

    // ─────────────────────────────────────────────
    // MIME / subfolder helpers
    // ─────────────────────────────────────────────
    fun getMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    fun subfolderFor(mime: String): String = when {
        mime.startsWith("image/")  -> "Images"
        mime.startsWith("video/")  -> "Videos"
        mime.startsWith("audio/")  -> "Audio"
        mime == "application/pdf"
            || mime.startsWith("text/")
            || mime.contains("document",     ignoreCase = true)
            || mime.contains("spreadsheet",  ignoreCase = true)
            || mime.contains("presentation", ignoreCase = true)  -> "Documents"
        mime.contains("zip")   || mime.contains("rar")
            || mime.contains("7z") || mime.contains("tar")
            || mime.contains("gzip") || mime.contains("bzip")   -> "Archives"
        else -> "Others"
    }

    // ─────────────────────────────────────────────
    // SHA-256 for /hash endpoint (phone-shared files only)
    // ─────────────────────────────────────────────
    fun computeHash(uri: Uri): String {
        val md  = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER_SIZE)
        context.contentResolver.openInputStream(uri)?.use { input ->
            var read: Int
            while (input.read(buf).also { read = it } != -1) md.update(buf, 0, read)
        } ?: return "error"
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ─────────────────────────────────────────────
    // Common helpers
    // ─────────────────────────────────────────────
    private fun handleOptions(): Response {
        val resp = newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "")
        resp.addHeader("Access-Control-Allow-Origin",  "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        resp.addHeader("Access-Control-Allow-Headers", "X-Auth-Token, Content-Type, X-Filename")
        return resp
    }

    private fun corsResponse(resp: Response): Response {
        resp.addHeader("Access-Control-Allow-Origin",  "*")
        resp.addHeader("Access-Control-Allow-Headers", "X-Auth-Token, Content-Type, X-Filename")
        resp.addHeader("Connection", "keep-alive")
        return resp
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trimStart('.')

    private fun uniqueFile(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot  = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext  = if (dot > 0) name.substring(dot)    else ""
        var counter = 1
        while (true) {
            val next = File(dir, "$base ($counter)$ext")
            if (!next.exists()) return next
            counter++
        }
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes < 1024L               -> "$bytes B"
        bytes < 1024L * 1024        -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else                        -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun log(msg: String) = logCallback(msg)
}

data class SharedFileEntry(
    val name: String,
    val uri: Uri,
    val size: Long,
    var sha256: String? = null
)
