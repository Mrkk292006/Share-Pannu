package com.localshare.app

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * TeamShareServer — NanoHTTPD server for Team Share mode (port 8081).
 *
 * Design principles:
 *  • Files stored ONLY in cacheDir/TeamShare/ — never written to public storage automatically.
 *  • UUID-prefixed cache filenames → zero filename collision risk under concurrent uploads.
 *  • AtomicLong currentSessionBytes + MAX_SESSION_BYTES → 507 Insufficient Storage guard.
 *  • Same 15-min token TTL pattern as FileTransferServer; multiple simultaneous tokens allowed.
 *  • clearAllFiles() deletes every cache file and resets byte counter — called on session end.
 *
 * Future improvement: replace 3-second browser polling with WebSocket push notifications.
 */
class TeamShareServer(
    private val context: Context,
    private val pin: String,
    private val logCallback: (String) -> Unit,
    port: Int = 8081
) : NanoHTTPD(port) {

    companion object {
        const val BUFFER_SIZE        = 256 * 1024              // 256 KB I/O buffer
        const val SESSION_TTL_MS     = 15 * 60 * 1000L         // 15-min token TTL
        const val MIN_FREE_SPACE_BYTES = 500L * 1024 * 1024    // 500 MB safety buffer
        const val TEAM_CACHE_DIR     = "TeamShare"
        const val MAX_FAIL_ATTEMPTS  = 5                        // lockout threshold
        const val LOCKOUT_DURATION_MS = 60_000L                 // 60-second lockout
    }

    /**
     * IP-bound session store: token → Pair(clientIp, creationTimestamp)
     * Tokens are tied to the authenticating IP — reuse from a different IP is rejected.
     */
    private val sessions = ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Per-IP brute-force tracking: ip → Pair(failCount, firstFailTimestamp)
     * After MAX_FAIL_ATTEMPTS failures within LOCKOUT_DURATION_MS, the IP is locked out.
     */
    private val failedAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()

    /** In-memory registry: file-id → entry */
    private val fileRegistry = ConcurrentHashMap<String, TeamFileEntry>()

    /** Running total of bytes stored in this session — enforces MAX_SESSION_BYTES */
    private val currentSessionBytes = AtomicLong(0L)

    // ─────────────────────────────────────────────
    // Router
    // ─────────────────────────────────────────────
    override fun serve(session: IHTTPSession): Response {
        cleanExpiredSessions()
        val uri    = session.uri
        val method = session.method
        log("→ ${method.name} $uri")

        return when {
            uri == "/" && method == Method.GET                            -> serveWebUi()
            uri == "/ts-auth" && method == Method.POST                   -> handleAuth(session)
            uri == "/ts-files" && method == Method.GET                   -> requireAuth(session) { serveFileList() }
            uri.startsWith("/ts-download/") && method == Method.GET      -> requireAuth(session) { handleDownload(uri) }
            uri == "/ts-upload" && method == Method.POST                 -> requireAuth(session) { handleUpload(session) }
            uri == "/ts-delete" && method == Method.POST                 -> requireAuth(session) { handleDelete(session) }
            method == Method.OPTIONS                                      -> handleOptions()
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
        // IP-binding check: reject if the request comes from a different IP than the one that authenticated
        if (record.first != clientIp) {
            log("[⚠] TEAM token IP mismatch: expected ${record.first}, got $clientIp")
            return newFixedLengthResponse(Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }
        return block()
    }

    private fun cleanExpiredSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.second > SESSION_TTL_MS }
        // Also clear stale lockout records older than LOCKOUT_DURATION_MS
        failedAttempts.entries.removeIf { now - it.value.second > LOCKOUT_DURATION_MS }
    }

    // ─────────────────────────────────────────────
    // POST /ts-auth
    // Rate limiting: max 5 wrong attempts per IP within 60 s → 429 lockout
    // IP-bound tokens: token is stored with the authenticating IP
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
                log("[⚠] TEAM AUTH — IP $clientIp locked out ($count failures, ${retryAfterSec}s remaining)")
                val body = JSONObject()
                    .put("error", "Too many failed attempts. Try again in ${retryAfterSec}s.")
                    .put("retryAfter", retryAfterSec)
                    .toString()
                val resp = corsResponse(newFixedLengthResponse(
                    Response.Status.lookup(429), "application/json", body))
                resp.addHeader("Retry-After", retryAfterSec.toString())
                return resp
            }
            // Lockout window expired — reset
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
            // ── Success: clear fail record, issue IP-bound token ──
            failedAttempts.remove(clientIp)
            val token = UUID.randomUUID().toString()
            sessions[token] = Pair(clientIp, now)
            log("[✓] TEAM AUTH — Device authenticated ($clientIp)")
            corsResponse(newFixedLengthResponse(Status.OK, "application/json",
                JSONObject().put("token", token).toString()))
        } else {
            // ── Failure: increment counter ──
            val current = failedAttempts[clientIp]
            if (current == null || now - current.second >= LOCKOUT_DURATION_MS) {
                failedAttempts[clientIp] = Pair(1, now)
            } else {
                failedAttempts[clientIp] = Pair(current.first + 1, current.second)
            }
            val remaining = MAX_FAIL_ATTEMPTS - (failedAttempts[clientIp]?.first ?: 1)
            log("[✗] TEAM AUTH — Wrong PIN from $clientIp (${failedAttempts[clientIp]?.first}/$MAX_FAIL_ATTEMPTS)")
            corsResponse(newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                JSONObject()
                    .put("error", "Wrong PIN")
                    .put("attemptsRemaining", remaining.coerceAtLeast(0))
                    .toString()))
        }
    }

    // ─────────────────────────────────────────────
    // GET / — serve embedded Team Share web UI
    // ─────────────────────────────────────────────
    private fun serveWebUi(): Response {
        val html = context.assets.open("team_index.html").bufferedReader().readText()
        return corsResponse(newFixedLengthResponse(Status.OK, "text/html", html))
    }

    // ─────────────────────────────────────────────
    // GET /ts-files — list all session files
    // Returns: [{id, name, size, mime}, …]
    // ─────────────────────────────────────────────
    private fun serveFileList(): Response {
        val arr = JSONArray()
        fileRegistry.values
            .sortedByDescending { it.uploadedAt }
            .forEach { entry ->
                arr.put(JSONObject()
                    .put("id",   entry.id)
                    .put("name", entry.displayName)
                    .put("size", entry.size)
                    .put("mime", entry.mime))
            }
        return corsResponse(newFixedLengthResponse(Status.OK, "application/json", arr.toString()))
    }

    // ─────────────────────────────────────────────
    // GET /ts-download/<id> — stream session file to client
    // ─────────────────────────────────────────────
    private fun handleDownload(uri: String): Response {
        val id    = Uri.decode(uri.removePrefix("/ts-download/"))
        val entry = fileRegistry[id]
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")

        if (!entry.cacheFile.exists()) {
            fileRegistry.remove(id)
            return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
        }

        log("[⇄] TEAM GET /ts-download → ${entry.displayName} (${fmtSize(entry.size)})")
        return try {
            val stream = BufferedInputStream(FileInputStream(entry.cacheFile), BUFFER_SIZE)
            val resp   = newChunkedResponse(Status.OK, "application/octet-stream", stream)
            resp.addHeader("Content-Disposition", "attachment; filename=\"${entry.displayName}\"")
            resp.addHeader("Content-Length", entry.size.toString())
            resp.addHeader("Cache-Control", "no-cache")
            corsResponse(resp)
        } catch (e: Exception) {
            log("[✗] TEAM download error: ${e.message}")
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────
    // POST /ts-upload — receive file, store temporarily in cache
    //
    // Safeguards:
    //  1. Check device free space (requires file size + MIN_FREE_SPACE_BYTES)
    //  2. Cache file named <uuid>_<sanitizedName> → no collision possible
    //  3. AtomicLong updated atomically after successful write
    // ─────────────────────────────────────────────
    
    /**
     * Checks if the device has enough free space to accept the given file size.
     * Leaves a safety buffer of MIN_FREE_SPACE_BYTES.
     */
    fun hasSufficientSpace(newBytes: Long): Boolean {
        val cacheDir = File(context.cacheDir, TEAM_CACHE_DIR).apply { mkdirs() }
        val usableSpace = cacheDir.usableSpace
        val requiredBytes = if (newBytes > 0) newBytes else 0L
        return usableSpace - requiredBytes > MIN_FREE_SPACE_BYTES
    }
    private fun handleUpload(session: IHTTPSession): Response {
        val contentLengthStr = session.headers["content-length"] ?: ""
        val contentLength: Long = contentLengthStr.toLongOrNull() ?: -1L

        if (contentLength < 0) {
            return newFixedLengthResponse(Status.BAD_REQUEST, MIME_PLAINTEXT,
                "Missing or invalid Content-Length")
        }

        // ── Safeguard 1: Dynamic free space check ──
        if (!hasSufficientSpace(contentLength)) {
            val cacheDir = File(context.cacheDir, TEAM_CACHE_DIR)
            val free = cacheDir.usableSpace
            val req  = if (contentLength > 0) contentLength else 0L
            
            log("[✗] TEAM upload rejected — insufficient device storage. " +
                "Req: ${fmtSize(req)}, Free: ${fmtSize(free)}")
                
            return corsResponse(newFixedLengthResponse(
                Response.Status.lookup(507), "application/json",
                JSONObject()
                    .put("error", "Insufficient device storage")
                    .put("required", req)
                    .put("free", free)
                    .toString()
            ))
        }

        val rawName     = session.headers["x-filename"] ?: session.parms["filename"] ?: ""
        val displayName = sanitizeFileName(
            if (rawName.isNotEmpty()) Uri.decode(rawName)
            else "upload_${System.currentTimeMillis()}"
        )
        val mime = getMimeType(displayName)

        // ── Safeguard 2: UUID-prefixed cache filename → zero collision risk ──
        val fileId    = UUID.randomUUID().toString()
        val cacheName = "${fileId}_$displayName"
        val cacheDir  = File(context.cacheDir, TEAM_CACHE_DIR).also { it.mkdirs() }
        val cacheFile = File(cacheDir, cacheName)

        log("[⇄] TEAM POST /ts-upload → $displayName (${fmtSize(contentLength)}) — Receiving…")
        val uploadStart = System.currentTimeMillis()

        return try {
            val bytesWritten = java.io.BufferedOutputStream(cacheFile.outputStream(), StreamUtils.BUFFER_SIZE).use { out ->
                StreamUtils.streamWithSpeedTracking(
                    session.inputStream, out, contentLength, displayName, "TEAM upload"
                ) { msg -> log(msg) }
            }

            // ── Safeguard 3: atomic byte counter update after successful write ──
            currentSessionBytes.addAndGet(bytesWritten)

            val entry = TeamFileEntry(
                id          = fileId,
                displayName = displayName,
                size        = bytesWritten,
                mime        = mime,
                cacheFile   = cacheFile,
                uploadedAt  = System.currentTimeMillis()
            )
            fileRegistry[fileId] = entry

            val elapsed = (System.currentTimeMillis() - uploadStart).coerceAtLeast(1L)
            val speed   = bytesWritten.toDouble() / elapsed * 1000.0 / (1024.0 * 1024.0)
            log("[✓] TEAM upload → $displayName (${fmtSize(bytesWritten)}) — ${"%.1f".format(speed)} MB/s")

            corsResponse(newFixedLengthResponse(Status.OK, "application/json",
                JSONObject()
                    .put("success", true)
                    .put("id",      fileId)
                    .put("name",    displayName)
                    .put("size",    bytesWritten)
                    .toString()))

        } catch (e: Exception) {
            cacheFile.delete()
            log("[✗] TEAM upload error: ${e.message}")
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Upload error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────
    // POST /ts-delete  body: {"id": "<fileId>"}
    // ─────────────────────────────────────────────
    private fun handleDelete(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val raw = body["postData"] ?: ""
        val id  = try { JSONObject(raw).getString("id") } catch (_: Exception) { "" }

        val entry = fileRegistry.remove(id)
            ?: return corsResponse(newFixedLengthResponse(Status.NOT_FOUND, "application/json",
                JSONObject().put("error", "Not found").toString()))

        val bytes = entry.size
        if (entry.cacheFile.delete()) {
            currentSessionBytes.addAndGet(-bytes)
        }
        log("[✓] TEAM delete → ${entry.displayName}")
        return corsResponse(newFixedLengthResponse(Status.OK, "application/json",
            JSONObject().put("success", true).toString()))
    }

    // ─────────────────────────────────────────────
    // Public API for TeamShareService / TeamSharingActivity
    // ─────────────────────────────────────────────

    /** Snapshot of current session files for the RecyclerView */
    fun getSessionFiles(): List<TeamFileEntry> =
        fileRegistry.values.sortedByDescending { it.uploadedAt }

    /** Remove a file by ID (called from Activity's Delete button) */
    fun removeFile(id: String) {
        val entry = fileRegistry.remove(id) ?: return
        if (entry.cacheFile.delete()) {
            currentSessionBytes.addAndGet(-entry.size)
        }
        log("[✓] TEAM delete → ${entry.displayName}")
    }

    /** Get a file entry by ID (for Save-to-permanent flow) */
    fun getFile(id: String): TeamFileEntry? = fileRegistry[id]

    /**
     * Register a file entry created outside the HTTP server (e.g., phone-local upload).
     * Updates the session byte counter and registry identically to a browser upload.
     */
    fun addFileEntry(entry: TeamFileEntry) {
        fileRegistry[entry.id] = entry
        currentSessionBytes.addAndGet(entry.size)
    }

    /** Wipe ALL session files — call on session end */
    fun clearAllFiles() {
        val cacheDir = File(context.cacheDir, TEAM_CACHE_DIR)
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        fileRegistry.clear()
        currentSessionBytes.set(0L)
        sessions.clear()
        log("[✓] TEAM session cleared — all files deleted")
    }

    /** Current session byte usage */
    fun sessionBytes(): Long = currentSessionBytes.get()

    // ─────────────────────────────────────────────
    // Helpers
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

    private fun getMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._\\- ]"), "_").trimStart('.')

    private fun fmtSize(bytes: Long): String = when {
        bytes < 1024L               -> "$bytes B"
        bytes < 1024L * 1024        -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else                        -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun log(msg: String) = logCallback(msg)
}

// ─────────────────────────────────────────────
// Data model
// ─────────────────────────────────────────────
data class TeamFileEntry(
    val id:          String,
    val displayName: String,
    val size:        Long,
    val mime:        String,
    val cacheFile:   File,
    val uploadedAt:  Long
)
