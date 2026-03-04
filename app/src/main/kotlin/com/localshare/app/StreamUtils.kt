package com.localshare.app

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object StreamUtils {

    const val BUFFER_SIZE = 524288 // 512 KB

    /**
     * Streams data from [inputStream] to [outputStream] using a 512 KB buffer.
     * Guaranteed to emit real-time speed logs every 1000ms via [logCallback].
     * Avoids division by zero and handles formatting cleanly.
     * Throws [IOException] if bytesWritten != contentLength (truncated transfer).
     */
    fun streamWithSpeedTracking(
        inputStream: InputStream,
        outputStream: OutputStream,
        contentLength: Long,
        filename: String,
        contextName: String, // e.g. "Upload" or "TEAM upload" or "PHONE"
        logCallback: (String) -> Unit
    ): Long {
        var bytesWritten = 0L
        var lastLogMs    = System.currentTimeMillis()
        var lastLogBytes = 0L
        val startTime    = lastLogMs

        val buf = ByteArray(BUFFER_SIZE)
        var remaining = contentLength
        val isUnbounded = contentLength <= 0

        while (isUnbounded || remaining > 0) {
            val toRead = if (isUnbounded) buf.size else java.lang.Math.min(buf.size.toLong(), remaining).toInt()
            val read   = inputStream.read(buf, 0, toRead)
            if (read == -1) break
            outputStream.write(buf, 0, read)
            bytesWritten += read
            if (!isUnbounded) remaining -= read

            val nowMs = System.currentTimeMillis()
            val dtMs  = nowMs - lastLogMs

            if (dtMs >= 1000) {
                val windowBytes = bytesWritten - lastLogBytes
                val speedMbs    = windowBytes.toDouble() / dtMs * 1000.0 / (1024.0 * 1024.0)
                val speedStr    = if (speedMbs >= 1.0) "${"%.1f".format(speedMbs)} MB/s"
                                  else "${"%.0f".format(speedMbs * 1024)} KB/s"
                
                if (isUnbounded) {
                    logCallback("[⇄] $contextName $filename — $speedStr (${fmtSize(bytesWritten)} / Unknown Size)")
                } else {
                    val pct = (bytesWritten * 100 / contentLength)
                    logCallback("[⇄] $contextName $filename — $pct% · $speedStr (${fmtSize(bytesWritten)} / ${fmtSize(contentLength)})")
                }
                
                lastLogMs    = nowMs
                lastLogBytes = bytesWritten
            }
        }

        if (bytesWritten != contentLength && contentLength > 0) {
            throw IOException("Incomplete $contextName: received $bytesWritten / $contentLength bytes")
        }

        // Final summary log
        val totalMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
        val avgSpeedMbs = bytesWritten.toDouble() / totalMs * 1000.0 / (1024.0 * 1024.0)
        val avgSpeedStr = if (avgSpeedMbs >= 1.0) "${"%.1f".format(avgSpeedMbs)} MB/s"
                          else "${"%.0f".format(avgSpeedMbs * 1024)} KB/s"
                          
        logCallback("[✓] $contextName complete: $filename (${fmtSize(bytesWritten)}) at avg $avgSpeedStr")

        return bytesWritten
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes < 1024L               -> "$bytes B"
        bytes < 1024L * 1024        -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else                        -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
