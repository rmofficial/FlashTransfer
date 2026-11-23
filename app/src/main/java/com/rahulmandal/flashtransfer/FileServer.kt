package com.rahulmandal.flashtransfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * High-speed HTTP file server (own implementation, no external HTTP library).
 *
 *  - NIO SocketChannel + FileChannel.transferTo  -> zero-copy sendfile(), minimal CPU
 *  - 1 MB socket send/receive buffers, TCP_NODELAY
 *  - HTTP keep-alive, Range / resume, HEAD, multi-connection (download managers)
 *  - Optional Basic auth (user "admin")
 *
 * A root can be either a SAF tree Uri (a picked folder) or a plain file:// path
 * (the whole memory card, using MANAGE_EXTERNAL_STORAGE / READ_EXTERNAL_STORAGE).
 */
class FileServer(
    context: Context,
    private val port: Int,
    rootUri: Uri,
    private val password: String,
    private val pathToken: String = ""
) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val treeUri: Uri = rootUri
    private val fileMode: Boolean = rootUri.scheme == "file"
    private val rootFile: File? = if (fileMode) File(rootUri.path ?: "/") else null
    private val rootDocId: String =
        if (fileMode) rootFile!!.absolutePath else DocumentsContract.getTreeDocumentId(rootUri)
    private val rootDoc = Doc(
        rootDocId,
        if (fileMode) "Storage" else "Shared folder",
        true, 0L, 0L
    )

    private var ssc: ServerSocketChannel? = null

    @Volatile
    private var running = false
    private val open: MutableSet<SocketChannel> =
        Collections.newSetFromMap(ConcurrentHashMap<SocketChannel, Boolean>())
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "flash-conn").apply { isDaemon = true }
    })

    private data class Doc(
        val docId: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val lastModified: Long
    )

    private class Req(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val keepAlive: Boolean
    )

    // ------------------------------------------------------------------ SAF helpers

    private fun childrenUri(parentId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)

    private fun documentUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun queryChildren(parentId: String): List<Doc> {
        val out = ArrayList<Doc>()
        if (fileMode) {
            val dir = File(parentId)
            val listed = try {
                dir.listFiles()
            } catch (_: Exception) {
                null
            } ?: return out
            for (f in listed) {
                try {
                    if (!f.isDirectory && !f.isFile) continue
                    out.add(
                        Doc(
                            f.absolutePath,
                            f.name,
                            f.isDirectory,
                            if (f.isFile) f.length() else -1L,
                            f.lastModified()
                        )
                    )
                } catch (_: Exception) {
                }
            }
            return out
        }
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        try {
            resolver.query(childrenUri(parentId), projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)
                    out.add(
                        Doc(
                            id, name,
                            mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            size, modified
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun resolve(segments: List<String>): Doc? {
        if (fileMode) {
            val root = rootFile ?: return null
            var f = root
            for (seg in segments) f = File(f, seg)
            return try {
                val rootPath = root.canonicalPath
                val path = f.canonicalPath
                if (path != rootPath && !path.startsWith(rootPath + File.separator)) return null
                if (!f.exists()) return null
                Doc(
                    f.absolutePath,
                    f.name.ifEmpty { "Storage" },
                    f.isDirectory,
                    if (f.isFile) f.length() else -1L,
                    f.lastModified()
                )
            } catch (_: Exception) {
                null
            }
        }
        var currentId = rootDocId
        var current: Doc = rootDoc
        for (seg in segments) {
            if (!current.isDir) return null
            val child = queryChildren(currentId).firstOrNull { it.name == seg } ?: return null
            current = child
            currentId = child.docId
        }
        return current
    }

    // ------------------------------------------------------------------ lifecycle

    @Throws(IOException::class)
    fun start() {
        val ch = ServerSocketChannel.open()
        try {
            ch.socket().reuseAddress = true
            try {
                ch.socket().receiveBufferSize = 1 shl 20
            } catch (_: Exception) {
            }
            ch.socket().bind(InetSocketAddress(port), 256)
        } catch (e: IOException) {
            ch.close()
            throw e
        }
        ssc = ch
        running = true
        Thread({ acceptLoop(ch) }, "flash-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            ssc?.close()
        } catch (_: Exception) {
        }
        for (c in open.toList()) {
            try {
                c.close()
            } catch (_: Exception) {
            }
        }
        open.clear()
        pool.shutdownNow()
    }

    private fun acceptLoop(ch: ServerSocketChannel) {
        while (running) {
            try {
                val sc = ch.accept()
                open.add(sc)
                pool.execute { serveClient(sc) }
            } catch (e: Exception) {
                if (!running) break
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ connection

    private fun serveClient(sc: SocketChannel) {
        try {
            try {
                sc.setOption(StandardSocketOptions.TCP_NODELAY, true)
            } catch (_: Exception) {
            }
            try {
                sc.setOption(StandardSocketOptions.SO_SNDBUF, 1 shl 20)
            } catch (_: Exception) {
            }
            try {
                sc.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
            } catch (_: Exception) {
            }
            val ins = BufferedInputStream(Channels.newInputStream(sc), 16384)
            while (running) {
                val req = readRequest(ins) ?: break
                if (!handle(req, sc, ins)) break
            }
        } catch (_: Exception) {
            // client went away / bad request - just close
        } finally {
            open.remove(sc)
            try {
                sc.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readLine(ins: InputStream): String? {
        val bos = ByteArrayOutputStream()
        var n = 0
        while (true) {
            val c = ins.read()
            if (c < 0) return if (n == 0) null else String(bos.toByteArray(), Charsets.ISO_8859_1)
            if (c == '\n'.code) break
            if (c != '\r'.code) {
                bos.write(c)
                if (++n > 8192) throw IOException("line too long")
            }
        }
        return String(bos.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readRequest(ins: InputStream): Req? {
        var line = readLine(ins) ?: return null
        while (line.isEmpty()) line = readLine(ins) ?: return null
        val parts = line.split(' ')
        if (parts.size < 3) throw IOException("bad request line")
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]
        val http11 = parts[2].equals("HTTP/1.1", ignoreCase = true)

        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val h = readLine(ins) ?: return null
            if (h.isEmpty()) break
            if (++count > 100) throw IOException("too many headers")
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase(Locale.ROOT)] = h.substring(i + 1).trim()
        }
        val q = target.indexOf('?')
        val rawPath = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) target.substring(q + 1) else ""
        val conn = headers["connection"]?.lowercase(Locale.ROOT)
        val keep = if (http11) conn != "close" else conn == "keep-alive"
        return Req(method, Uri.decode(rawPath), query, headers, keep)
    }

    // ------------------------------------------------------------------ writing

    private fun writeAll(sc: SocketChannel, data: ByteArray) {
        writeAll(sc, data, 0, data.size)
    }

    private fun writeAll(sc: SocketChannel, data: ByteArray, offset: Int, length: Int) {
        val bb = ByteBuffer.wrap(data, offset, length)
        while (bb.hasRemaining()) sc.write(bb)
    }

    private fun head(
        code: Int, reason: String, extra: List<Pair<String, String>>, keep: Boolean
    ): ByteArray {
        val h = StringBuilder()
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
        h.append("Server: FlashTransfer\r\n")
        for ((k, v) in extra) h.append(k).append(": ").append(v).append("\r\n")
        h.append("Connection: ").append(if (keep) "keep-alive" else "close").append("\r\n\r\n")
        return h.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun sendBytes(
        sc: SocketChannel, code: Int, reason: String, mime: String, body: ByteArray,
        extra: List<Pair<String, String>>, keep: Boolean, headOnly: Boolean
    ) {
        val hdr = ArrayList<Pair<String, String>>()
        hdr.add("Content-Type" to mime)
        hdr.add("Content-Length" to body.size.toString())
        hdr.addAll(extra)
        val hb = head(code, reason, hdr, keep)
        writeAll(sc, if (headOnly) hb else hb + body)
    }

    private fun sendText(sc: SocketChannel, code: Int, reason: String, msg: String, keep: Boolean) {
        sendBytes(
            sc, code, reason, "text/plain; charset=utf-8", msg.toByteArray(Charsets.UTF_8),
            emptyList(), keep, false
        )
    }

    // ------------------------------------------------------------------ request handling

    private fun authorized(req: Req): Boolean {
        if (password.isEmpty()) return true
        val h = req.headers["authorization"] ?: return false
        if (!h.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.decode(h.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            return false
        }
        val user = decoded.substringBefore(':')
        val pass = decoded.substringAfter(':', "")
        return user == "admin" &&
            MessageDigest.isEqual(pass.toByteArray(Charsets.UTF_8), password.toByteArray(Charsets.UTF_8))
    }

    /** @return true if the connection can be kept alive for another request */
    private fun handle(req: Req, sc: SocketChannel, ins: InputStream): Boolean {
        val keep = req.keepAlive
        val headOnly = req.method == "HEAD"

        var uri = req.path
        if (pathToken.isNotEmpty()) {
            if (uri == "/$pathToken") {
                sendBytes(
                    sc, 301, "Moved Permanently", "text/plain; charset=utf-8", ByteArray(0),
                    listOf("Location" to "/$pathToken/"), keep, headOnly
                )
                return keep
            }
            if (!uri.startsWith("/$pathToken/")) {
                sendText(sc, 404, "Not Found", "Not found", keep)
                return keep
            }
            uri = uri.substring(pathToken.length + 1)
            if (uri.isEmpty()) uri = "/"
        }

        val bodyLength = req.headers["content-length"]?.trim()?.toLongOrNull() ?: 0L
        val hasBody = bodyLength > 0L || req.method == "POST"

        if (!authorized(req)) {
            val k = if (hasBody) false else keep
            sendBytes(
                sc, 401, "Unauthorized", "text/plain; charset=utf-8", "Login required".toByteArray(),
                listOf("WWW-Authenticate" to "Basic realm=\"Flash Transfer\""), k, headOnly
            )
            return k
        }
        if (req.method == "POST") {
            return handleUpload(req, sc, ins, uri)
        }
        if (req.method != "GET" && !headOnly) {
            sendText(sc, 405, "Method Not Allowed", "Method not allowed", keep)
            return keep
        }

        val segments = uri.trim('/').split('/').filter { it.isNotEmpty() }
        val doc = try {
            resolve(segments)
        } catch (e: Exception) {
            null
        }
        if (doc == null) {
            sendText(sc, 404, "Not Found", "Not found", keep)
            return keep
        }
        if (doc.isDir) {
            if (!uri.endsWith("/")) {
                sendBytes(
                    sc, 301, "Moved Permanently", "text/plain; charset=utf-8", ByteArray(0),
                    listOf("Location" to Uri.encode("${req.path}/", "/")), keep, headOnly
                )
                return keep
            }
            val html = listing(doc, uri).toByteArray(Charsets.UTF_8)
            sendBytes(
                sc, 200, "OK", "text/html; charset=utf-8", html,
                listOf("Cache-Control" to "no-store"), keep, headOnly
            )
            return keep
        }
        return serveFile(req, sc, doc)
    }

    // ------------------------------------------------------------------ file upload

    private fun handleUpload(
        req: Req, sc: SocketChannel, ins: InputStream, uri: String
    ): Boolean {
        val length = req.headers["content-length"]?.trim()?.toLongOrNull()
        if (length == null) {
            sendText(sc, 411, "Length Required", "Content-Length header is required", false)
            return false
        }
        if (length <= 0L) {
            sendText(sc, 400, "Bad Request", "Empty upload", false)
            return false
        }
        val rawName = req.headers["x-file-name"] ?: req.headers["x-filename"]
        val name = rawName?.let { safeName(Uri.decode(it)) }
        if (name == null) {
            sendText(
                sc, 400, "Bad Request",
                "Missing or invalid X-File-Name header", false
            )
            return false
        }

        val segments = uri.trim('/').split('/').filter { it.isNotEmpty() }
        val target = try {
            resolve(segments)
        } catch (_: Exception) {
            null
        }
        if (target == null) {
            sendText(sc, 404, "Not Found", "Upload folder not found", false)
            return false
        }
        if (!target.isDir) {
            sendText(sc, 400, "Bad Request", "Upload target is not a folder", false)
            return false
        }

        val mime = guessMime(req.headers["content-type"], name)
        var createdFile: File? = null
        var createdUri: Uri? = null
        var out: OutputStream? = null
        var savedName = name
        try {
            if (fileMode) {
                val root = rootFile?.canonicalFile ?: throw IOException("no root")
                val dir = File(target.docId).canonicalFile
                if (dir != root && !dir.path.startsWith(root.path + File.separator)) {
                    sendText(sc, 403, "Forbidden", "Target outside shared folder", false)
                    return false
                }
                if (!dir.isDirectory) throw IOException("folder unavailable")
                val existing = try {
                    dir.list()?.toHashSet() ?: HashSet()
                } catch (_: Exception) {
                    HashSet()
                }
                savedName = uniqueName(existing, name)
                val f = File(dir, savedName)
                createdFile = f
                out = FileOutputStream(f)
            } else {
                val existing = queryChildren(target.docId).mapTo(HashSet()) { it.name }
                savedName = uniqueName(existing, name)
                val newUri = DocumentsContract.createDocument(
                    resolver, childrenUri(target.docId), mime, savedName
                ) ?: throw IOException("folder is not writable")
                createdUri = newUri
                out = resolver.openOutputStream(newUri, "w")
                    ?: throw IOException("folder is not writable")
            }

            ServerState.active.incrementAndGet()
            try {
                out!!.let { o ->
                    streamUpload(ins, o, length)
                    o.flush()
                }
            } finally {
                ServerState.active.decrementAndGet()
                try {
                    out?.close()
                } catch (_: Exception) {
                }
            }

            val json = "{\"name\":\"${jsonEscape(savedName)}\",\"size\":$length}"
                .toByteArray(Charsets.UTF_8)
            sendBytes(
                sc, 201, "Created", "application/json; charset=utf-8", json,
                listOf("X-Filename" to Uri.encode(savedName)), false, false
            )
            return false
        } catch (e: SecurityException) {
            cleanupUpload(createdFile, createdUri)
            sendText(
                sc, 403, "Forbidden",
                "Folder is read-only. Re-select the folder in the app to allow uploads.", false
            )
            return false
        } catch (e: Exception) {
            cleanupUpload(createdFile, createdUri)
            sendText(sc, 500, "Internal Server Error", "Upload failed: ${e.message ?: "unknown error"}", false)
            return false
        }
    }

    private fun cleanupUpload(file: File?, uri: Uri?) {
        if (uri != null) {
            try {
                DocumentsContract.deleteDocument(resolver, uri)
            } catch (_: Exception) {
            }
        }
        if (file != null) {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun streamUpload(ins: InputStream, out: OutputStream, length: Long) {
        val buf = ByteArray(256 * 1024)
        var remaining = length
        while (remaining > 0L) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val r = ins.read(buf, 0, want)
            if (r < 0) throw IOException("client closed early")
            out.write(buf, 0, r)
            remaining -= r
            ServerState.bytesReceived.addAndGet(r.toLong())
        }
    }

    private fun safeName(raw: String): String? {
        var n = raw.substringAfterLast('/').substringAfterLast('\\')
        n = n.replace(Regex("[\\u0000-\\u001F\\u007F]"), "").trim()
        if (n.isEmpty() || n == "." || n == "..") return null
        if (n.length > 200) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0) n.substring(dot) else ""
            val base = if (dot > 0) n.substring(0, dot) else n
            val keep = maxOf(1, 200 - ext.length)
            n = base.take(keep).ifEmpty { "file" } + ext.take(if (dot > 0) 20 else 0)
        }
        return n
    }

    private fun uniqueName(existing: Set<String>, name: String): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (candidate !in existing) return candidate
            i++
        }
    }

    private fun guessMime(contentType: String?, name: String): String {
        val supplied = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        val fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(name))
        if (supplied.isNullOrEmpty() || supplied == "application/octet-stream") {
            return fromExt ?: "application/octet-stream"
        }
        return supplied
    }

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    // ------------------------------------------------------------------ file download

    private data class RangeSel(val start: Long, val end: Long, val partial: Boolean)

    private fun selectRange(range: String?, len: Long): RangeSel {
        var start = 0L
        var end = len - 1
        var partial = false
        if (range != null && range.startsWith("bytes=") && len > 0) {
            val spec = range.substring(6).substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash)
                val b = spec.substring(dash + 1)
                try {
                    if (a.isEmpty()) {
                        start = maxOf(0L, len - b.toLong())
                    } else {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = minOf(b.toLong(), len - 1)
                    }
                    partial = true
                } catch (e: NumberFormatException) {
                    start = 0L
                    end = len - 1
                    partial = false
                }
            }
        }
        return RangeSel(start, end, partial)
    }

    private fun serveFile(req: Req, sc: SocketChannel, doc: Doc): Boolean {
        val keep = req.keepAlive
        val headOnly = req.method == "HEAD"
        val dl = req.query.split('&').any { it == "dl" || it.startsWith("dl=") }

        val source = try {
            if (fileMode) {
                val f = File(doc.docId)
                if (f.isFile && f.canRead()) {
                    val mime = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(extensionOf(f.name))
                        ?: "application/octet-stream"
                    Source(f.length(), mime) { c, start, count -> streamFile(f, c, start, count) }
                } else null
            } else {
                val fileUri = documentUri(doc.docId)
                val pfd = try {
                    resolver.openFileDescriptor(fileUri, "r")
                } catch (_: Exception) {
                    null
                }
                if (pfd == null) null else {
                    val len = when {
                        pfd.statSize >= 0 -> pfd.statSize
                        doc.size >= 0 -> doc.size
                        else -> -1L
                    }
                    if (len < 0) {
                        try {
                            pfd.close()
                        } catch (_: Exception) {
                        }
                        null
                    } else {
                        val mime = resolver.getType(fileUri)
                            ?: MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(extensionOf(doc.name))
                            ?: "application/octet-stream"
                        Source(len, mime) { c, start, count ->
                            stream(pfd, fileUri, c, start, count)
                        }.also {
                            it.onClose = {
                                try {
                                    pfd.close()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
        if (source == null) {
            sendText(sc, 404, "Not Found", "File not readable", keep)
            return keep
        }

        try {
            val len = source.length
            val mime = if (dl) "application/octet-stream" else source.mime
            val sel = selectRange(req.headers["range"], len)
            if (sel.partial && (sel.start >= len || sel.start > sel.end)) {
                sendBytes(
                    sc, 416, "Range Not Satisfiable", "text/plain; charset=utf-8", ByteArray(0),
                    listOf("Content-Range" to "bytes */$len"), keep, headOnly
                )
                return keep
            }

            val count = if (len == 0L) 0L else sel.end - sel.start + 1
            val hdr = ArrayList<Pair<String, String>>()
            hdr.add("Content-Type" to mime)
            hdr.add("Content-Length" to count.toString())
            hdr.add("Accept-Ranges" to "bytes")
            if (sel.partial) hdr.add("Content-Range" to "bytes ${sel.start}-${sel.end}/$len")
            if (dl) {
                val ascii = doc.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                hdr.add(
                    "Content-Disposition" to
                        "attachment; filename=\"$ascii\"; filename*=UTF-8''" + Uri.encode(doc.name)
                )
            }
            writeAll(sc, head(if (sel.partial) 206 else 200, if (sel.partial) "Partial Content" else "OK", hdr, keep))
            if (headOnly || count == 0L) return keep

            ServerState.active.incrementAndGet()
            try {
                source.transferTo(sc, sel.start, count)
            } finally {
                ServerState.active.decrementAndGet()
            }
            return keep
        } finally {
            source.close()
        }
    }

    private class Source(
        val length: Long,
        val mime: String,
        val transferTo: (SocketChannel, Long, Long) -> Unit
    ) {
        var onClose: (() -> Unit)? = null
        fun close() = try {
            onClose?.invoke()
        } catch (_: Exception) {
        }
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun account(n: Long) {
        ServerState.bytesSent.addAndGet(n)
        ServerState.bytesRemaining.addAndGet(-n)
    }

    private fun stream(
        pfd: ParcelFileDescriptor,
        fileUri: Uri,
        sc: SocketChannel,
        start: Long,
        count: Long
    ) {
        var sent = 0L
        var fallback = false
        ServerState.bytesRemaining.addAndGet(count)
        try {
            try {
                val ch = FileInputStream(pfd.fileDescriptor).channel
                var pos = start
                while (sent < count) {
                    val want = minOf(count - sent, CHUNK)
                    val n = ch.transferTo(pos, want, sc)
                    if (n <= 0L) {
                        if (sent == 0L) {
                            fallback = true
                            break
                        }
                        throw IOException("file stalled")
                    }
                    pos += n
                    sent += n
                    account(n)
                }
            } catch (e: Exception) {
                if (sent > 0L) throw e
                fallback = true
            }

            if (!fallback) return

            resolver.openFileDescriptor(fileUri, "r")?.use { p2 ->
                FileInputStream(p2.fileDescriptor).use { input ->
                    var skipped = 0L
                    while (skipped < start) {
                        val s = input.skip(start - skipped)
                        if (s <= 0L) {
                            if (input.read() < 0) throw IOException("file ended early")
                            skipped++
                        } else {
                            skipped += s
                        }
                    }
                    val buf = ByteArray(256 * 1024)
                    var remaining = count
                    while (remaining > 0) {
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                        if (r < 0) throw IOException("file ended early")
                        writeAll(sc, buf, 0, r)
                        remaining -= r
                        sent += r
                        account(r.toLong())
                    }
                }
            }
        } finally {
            val left = count - sent
            if (left > 0) ServerState.bytesRemaining.addAndGet(-left)
        }
    }

    private fun streamFile(f: File, sc: SocketChannel, start: Long, count: Long) {
        var sent = 0L
        var fallback = false
        ServerState.bytesRemaining.addAndGet(count)
        try {
            try {
                FileInputStream(f).use { input ->
                    val ch = input.channel
                    var pos = start
                    while (sent < count) {
                        val want = minOf(count - sent, CHUNK)
                        val n = ch.transferTo(pos, want, sc)
                        if (n <= 0L) {
                            if (sent == 0L) {
                                fallback = true
                                break
                            }
                            throw IOException("file stalled")
                        }
                        pos += n
                        sent += n
                        account(n)
                    }
                }
            } catch (e: Exception) {
                if (sent > 0L) throw e
                fallback = true
            }

            if (!fallback) return

            FileInputStream(f).use { input ->
                var skipped = 0L
                while (skipped < start) {
                    val s = input.skip(start - skipped)
                    if (s <= 0L) {
                        if (input.read() < 0) throw IOException("file ended early")
                        skipped++
                    } else {
                        skipped += s
                    }
                }
                val buf = ByteArray(256 * 1024)
                var remaining = count
                while (remaining > 0) {
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (r < 0) throw IOException("file ended early")
                    writeAll(sc, buf, 0, r)
                    remaining -= r
                    sent += r
                    account(r.toLong())
                }
            }
        } finally {
            val left = count - sent
            if (left > 0) ServerState.bytesRemaining.addAndGet(-left)
        }
    }

    // ------------------------------------------------------------------ folder listing

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun iconFor(name: String, isDir: Boolean): String {
        if (isDir) return "📁"
        return when (extensionOf(name)) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> "🖼️"
            "mp4", "mkv", "avi", "mov", "webm", "3gp" -> "🎬"
            "mp3", "m4a", "wav", "flac", "ogg", "aac" -> "🎵"
            "zip", "rar", "7z", "tar", "gz" -> "📦"
            "apk", "xapk", "apks" -> "🤖"
            "pdf" -> "📕"
            else -> "📄"
        }
    }

    private fun listing(dir: Doc, uri: String): String {
        val items = queryChildren(dir.docId)
            .sortedWith(compareBy({ !it.isDir }, { it.name.lowercase(Locale.ROOT) }))
        val df = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
        var folders = 0
        var files = 0
        var total = 0L
        val rows = StringBuilder()

        if (uri != "/") {
            rows.append("<div class='row' data-n='..'><span class='ic'>⬆️</span>")
                .append("<a class='nm' href='../'>.. (parent folder)</a>")
                .append("<span class='sz'></span><span class='dt'></span><span class='dl'></span></div>")
        }
        for (f in items) {
            val enc = Uri.encode(f.name)
            val name = esc(f.name)
            val nl = esc(f.name.lowercase(Locale.ROOT))
            val date = if (f.lastModified > 0) df.format(Date(f.lastModified)) else ""
            if (f.isDir) {
                folders++
                rows.append("<div class='row' data-n='$nl'><span class='ic'>${iconFor(f.name, true)}</span>")
                    .append("<a class='nm' href='$enc/'>$name</a><span class='sz'>—</span>")
                    .append("<span class='dt'>$date</span><span class='dl'></span></div>")
            } else {
                files++
                val size = if (f.size >= 0) f.size else 0L
                total += size
                rows.append("<div class='row' data-n='$nl'><span class='ic'>${iconFor(f.name, false)}</span>")
                    .append("<a class='nm' href='$enc'>$name</a><span class='sz'>${Fmt.size(size)}</span>")
                    .append("<span class='dt'>$date</span>")
                    .append("<a class='dl' href='$enc?dl=1'>Download</a></div>")
            }
        }

        val base = if (pathToken.isEmpty()) "/" else "/$pathToken/"
        val crumbs = StringBuilder("<a href='$base'>Home</a>")
        var acc = base
        for (seg in uri.split('/').filter { it.isNotEmpty() }) {
            acc += Uri.encode(seg) + "/"
            crumbs.append(" / <a href='$acc'>${esc(seg)}</a>")
        }

        return """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Flash Transfer</title>
<style>
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:radial-gradient(1200px 600px at 20% -10%,#14213d 0,#070b14 60%) fixed;color:#eaf0fa;min-height:100vh}
.wrap{max-width:980px;margin:0 auto;padding:24px 16px 48px}
.top{display:flex;align-items:center;gap:12px;margin-bottom:14px}
.logo{width:44px;height:44px;border-radius:13px;background:linear-gradient(135deg,#0b1226,#1b2e5c);border:1px solid #2a3a5e;display:grid;place-items:center;font-size:22px}
h1{font-size:20px;margin:0}.sub{color:#8ea0bf;font-size:12px}
.crumbs{margin:6px 0 14px;font-size:14px;color:#8ea0bf;word-break:break-all}
.crumbs a{color:#ffb300;text-decoration:none}
.search{width:100%;padding:12px 14px;border-radius:12px;border:1px solid #1d2a45;background:#0f1726;color:#eaf0fa;font-size:15px;margin-bottom:12px;outline:none}
.search:focus{border-color:#ffb300}
.up{display:flex;align-items:center;gap:12px;margin:0 0 12px;padding:14px;border-radius:16px;border:1px dashed #2a3a5e;background:linear-gradient(135deg,#111d33,#0b1424);transition:border-color .15s,background .15s,box-shadow .15s}
.up.drag{border-color:#ffb300;background:#16233d;box-shadow:0 0 0 3px #ffb30022}
.upic{width:42px;height:42px;border-radius:12px;background:linear-gradient(135deg,#ffb300,#ff6d00);display:grid;place-items:center;font-size:20px;flex:0 0 auto}
.uptxt{flex:1;min-width:0}
.uptitle{font-weight:700;font-size:14px}
.upsub{color:#8ea0bf;font-size:12px;margin-top:2px}
.upacts{display:flex;gap:8px;flex:0 0 auto}
.upbtn{border:0;border-radius:10px;padding:10px 14px;font-weight:700;font-size:13px;color:#1a1000;background:linear-gradient(135deg,#ffb300,#ff6d00);cursor:pointer;white-space:nowrap}
.upbtn.ghost{background:#18233b;color:#eaf0fa;border:1px solid #2a3a5e}
.upbtn:disabled{opacity:.45;cursor:default}
.picked{display:none;margin:0 0 12px;border-radius:14px;background:#0f1726;border:1px solid #1d2a45;overflow:hidden}
.picked.show{display:block}
.phead{display:flex;align-items:center;justify-content:space-between;padding:10px 12px;border-bottom:1px solid #18233b;font-size:12px;color:#8ea0bf}
.pclear{border:0;background:transparent;color:#ffb300;font-weight:700;font-size:12px;cursor:pointer;padding:2px 4px}
.pitem{display:flex;align-items:center;gap:10px;padding:10px 12px;border-bottom:1px solid #18233b;font-size:13px}
.pitem:last-child{border-bottom:0}
.pic{width:28px;height:28px;border-radius:8px;background:#18233b;display:grid;place-items:center;font-size:14px;flex:0 0 auto}
.pname{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.psize{color:#8ea0bf;font-size:12px;white-space:nowrap}
.pdel{border:0;background:#18233b;color:#8ea0bf;width:26px;height:26px;border-radius:8px;cursor:pointer;font-size:12px;flex:0 0 auto}
.pdel:hover{color:#f87171}
.upwrap{display:none;margin:0 0 14px;padding:12px 14px;border-radius:14px;background:#0f1726;border:1px solid #1d2a45}
.upwrap.show{display:block}
.upname{font-size:12px;color:#eaf0fa;margin-bottom:8px;word-break:break-all}
.upbar{height:8px;border-radius:99px;background:#18233b;overflow:hidden}
.upfill{height:100%;width:0;border-radius:99px;background:linear-gradient(90deg,#ffb300,#ff6d00);transition:width .15s}
.upstatus{font-size:12px;margin-top:8px;color:#8ea0bf}
.upstatus.err{color:#f87171}
.card{background:#111a2c;border:1px solid #1d2a45;border-radius:16px;overflow:hidden}
.row{display:flex;align-items:center;gap:12px;padding:12px 14px;border-bottom:1px solid #18233b}
.row:last-child{border-bottom:0}.row:hover{background:#15213a}
.ic{font-size:20px;width:26px;text-align:center}
.nm{flex:1;color:#eaf0fa;text-decoration:none;word-break:break-word;font-weight:500}
.sz,.dt{color:#8ea0bf;font-size:12px;white-space:nowrap}
.dl{display:inline-block;padding:7px 12px;border-radius:10px;background:linear-gradient(135deg,#ffb300,#ff6d00);color:#1a1000;font-weight:700;text-decoration:none;font-size:13px}
.dl:empty{visibility:hidden}
.stats{margin:12px 2px 0;color:#8ea0bf;font-size:12px}
.foot{margin-top:22px;text-align:center;color:#5d6f8f;font-size:12px}
@media(max-width:600px){.dt{display:none}.up{flex-wrap:wrap}.upacts{width:100%}.upacts .upbtn{flex:1}}
</style></head><body><div class="wrap">
<div class="top"><div class="logo">⚡</div><div><h1>Flash Transfer</h1><div class="sub">Lightning-fast Wi-Fi file sharing</div></div></div>
<div class="crumbs">$crumbs</div>
<div class="up" id="drop"><div class="upic">⬆️</div><div class="uptxt"><div class="uptitle">Upload files</div><div class="upsub" id="upsub">Select one or more files, then press Upload</div></div><div class="upacts"><button class="upbtn ghost" id="upbtn" type="button">Choose files</button><button class="upbtn" id="upgo" type="button" disabled>Upload</button></div></div>
<input id="filepicker" type="file" multiple hidden>
<div class="picked" id="picked"></div>
<div class="upwrap" id="upwrap"><div class="upname" id="upname"></div><div class="upbar"><div class="upfill" id="upfill"></div></div><div class="upstatus" id="upstatus"></div></div>
<input class="search" placeholder="Search in this folder..." oninput="f(this.value)">
<div class="card">$rows</div>
<div class="stats">$folders folders · $files files · ${Fmt.size(total)}</div>
<div class="foot">© 2026 Rahul Mandal • All rights reserved</div>
</div>
<script>
function f(q){q=q.toLowerCase();var r=document.querySelectorAll('.row');for(var i=0;i<r.length;i++){r[i].style.display=r[i].getAttribute('data-n').indexOf(q)>-1?'':'none';}}
(function(){
var drop=document.getElementById('drop'),pick=document.getElementById('filepicker'),btn=document.getElementById('upbtn'),go=document.getElementById('upgo');
var picked=document.getElementById('picked'),wrap=document.getElementById('upwrap'),fill=document.getElementById('upfill'),nm=document.getElementById('upname'),st=document.getElementById('upstatus'),sub=document.getElementById('upsub');
if(!drop||!pick||!btn||!go)return;
var selected=[];
function setStatus(t,err){st.textContent=t||'';st.className='upstatus'+(err?' err':'')}
function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;')}
function human(n){if(n<1024)return n+' B';var u=['KB','MB','GB','TB'],v=n,i=-1;while(v>=1024&&i<u.length-1){v/=1024;i++}return (v>=100?v.toFixed(0):v.toFixed(1))+' '+u[i]}
function icon(name){var e=(name.split('.').pop()||'').toLowerCase();if(/^(jpg|jpeg|png|gif|webp|heic|bmp)$/.test(e))return '🖼️';if(/^(mp4|mkv|avi|mov|webm|3gp)$/.test(e))return '🎬';if(/^(mp3|m4a|wav|flac|ogg|aac)$/.test(e))return '🎵';if(/^(zip|rar|7z|tar|gz)$/.test(e))return '📦';if(/^(apk|xapk|apks)$/.test(e))return '🤖';if(e==='pdf')return '📕';return '📄'}
function totalSize(){var t=0;for(var i=0;i<selected.length;i++)t+=selected[i].size;return t}
function render(){
if(!selected.length){picked.className='picked';picked.innerHTML='';go.disabled=true;go.textContent='Upload';sub.textContent='Select one or more files, then press Upload';return}
var html='<div class="phead"><span>'+selected.length+' file'+(selected.length>1?'s':'')+' · '+human(totalSize())+'</span><button class="pclear" id="clearall" type="button">Clear</button></div>';
for(var i=0;i<selected.length;i++){var f=selected[i];html+='<div class="pitem"><div class="pic">'+icon(f.name)+'</div><div class="pname">'+esc(f.name)+'</div><div class="psize">'+human(f.size)+'</div><button class="pdel" data-i="'+i+'" type="button">✕</button></div>'}
picked.innerHTML=html;picked.className='picked show';go.disabled=false;
go.textContent='Upload '+selected.length+(selected.length>1?' files':' file');
sub.textContent=selected.length+' file'+(selected.length>1?'s':'')+' selected — press Upload';
}
function add(files){
var list=Array.prototype.slice.call(files||[]);
for(var i=0;i<list.length;i++){
var f=list[i],dup=false;
for(var j=0;j<selected.length;j++){var s=selected[j];if(s.name===f.name&&s.size===f.size&&s.lastModified===f.lastModified){dup=true;break}}
if(!dup)selected.push(f);
}
render();setStatus('');fill.style.width='0%';wrap.className='upwrap';
}
picked.addEventListener('click',function(ev){
var t=ev.target;if(!t)return;
if(t.id==='clearall'){selected=[];render();setStatus('');fill.style.width='0%';wrap.className='upwrap';return}
var idx=t.getAttribute('data-i');
if(idx!==null&&idx!==undefined){selected.splice(parseInt(idx,10),1);render();setStatus('');fill.style.width='0%';wrap.className='upwrap'}
});
btn.onclick=function(){pick.click()};
pick.onchange=function(){add(pick.files);pick.value=''};
go.onclick=function(){upload()};
['dragenter','dragover'].forEach(function(e){drop.addEventListener(e,function(ev){ev.preventDefault();drop.classList.add('drag')})});
['dragleave','drop'].forEach(function(e){drop.addEventListener(e,function(ev){ev.preventDefault();drop.classList.remove('drag')})});
drop.addEventListener('drop',function(ev){if(ev.dataTransfer&&ev.dataTransfer.files)add(ev.dataTransfer.files)});
function put(f){
return new Promise(function(resolve,reject){
var x=new XMLHttpRequest();
x.open('POST',location.pathname);
x.setRequestHeader('X-File-Name',encodeURIComponent(f.name));
x.setRequestHeader('Content-Type',f.type||'application/octet-stream');
x.upload.onprogress=function(e){if(e.lengthComputable)fill.style.width=Math.min(100,Math.round(e.loaded/e.total*1000)/10)+'%'};
x.onload=function(){if(x.status>=200&&x.status<300){resolve()}else{reject(new Error((x.responseText||('HTTP '+x.status)).trim()))}};
x.onerror=function(){reject(new Error('Network error'))};
x.onabort=function(){reject(new Error('Cancelled'))};
x.send(f);
});
}
async function upload(){
if(!selected.length)return;
btn.disabled=true;go.disabled=true;wrap.className='upwrap show';fill.style.width='0%';
for(var i=0;i<selected.length;i++){
var f=selected[i];
nm.textContent=(i+1)+' / '+selected.length+'   ·   '+f.name;
fill.style.width='0%';setStatus('Uploading...');
try{await put(f);fill.style.width='100%';setStatus('Uploaded '+f.name)}
catch(err){setStatus('Failed: '+err.message,true);btn.disabled=false;go.disabled=false;return}
}
setStatus('Done. Refreshing...');
setTimeout(function(){location.reload()},600);
}
render();
})();
</script></body></html>"""
    }

    companion object {
        /** sendfile chunk: big enough for speed, small enough for a smooth live-speed readout */
        private const val CHUNK = 1L shl 20
    }
}
