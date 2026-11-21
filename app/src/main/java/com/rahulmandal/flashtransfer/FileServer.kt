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
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
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
 * Files are read through the Storage Access Framework (no "all files" permission),
 * so the app never needs MANAGE_EXTERNAL_STORAGE.
 */
class FileServer(
    context: Context,
    private val port: Int,
    rootUri: Uri,
    private val password: String
) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val treeUri: Uri = rootUri
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(rootUri)
    private val rootDoc = Doc(rootDocId, "Shared folder", true, 0L, 0L)

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
                if (!handle(req, sc)) break
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
    private fun handle(req: Req, sc: SocketChannel): Boolean {
        val keep = req.keepAlive
        val headOnly = req.method == "HEAD"

        if (!authorized(req)) {
            sendBytes(
                sc, 401, "Unauthorized", "text/plain; charset=utf-8", "Login required".toByteArray(),
                listOf("WWW-Authenticate" to "Basic realm=\"Flash Transfer\""), keep, headOnly
            )
            return keep
        }
        if (req.method != "GET" && !headOnly) {
            sendText(sc, 405, "Method Not Allowed", "Method not allowed", keep)
            return keep
        }

        val uri = req.path
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
                    listOf("Location" to Uri.encode("$uri/", "/")), keep, headOnly
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

    // ------------------------------------------------------------------ file download

    private fun serveFile(req: Req, sc: SocketChannel, doc: Doc): Boolean {
        val keep = req.keepAlive
        val headOnly = req.method == "HEAD"
        val dl = req.query.split('&').any { it == "dl" || it.startsWith("dl=") }

        val fileUri = documentUri(doc.docId)
        val pfd: ParcelFileDescriptor = try {
            resolver.openFileDescriptor(fileUri, "r")
        } catch (e: Exception) {
            null
        } ?: run {
            sendText(sc, 404, "Not Found", "File not readable", keep)
            return keep
        }

        try {
            val len = when {
                pfd.statSize >= 0 -> pfd.statSize
                doc.size >= 0 -> doc.size
                else -> -1L
            }
            if (len < 0) {
                sendText(sc, 500, "Internal Server Error", "Unknown file size", keep)
                return keep
            }

            var mime = resolver.getType(fileUri)
                ?: MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(extensionOf(doc.name)) ?: "application/octet-stream"
            if (dl) mime = "application/octet-stream"

            var start = 0L
            var end = len - 1
            var partial = false
            val range = req.headers["range"]
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
            if (partial && (start >= len || start > end)) {
                sendBytes(
                    sc, 416, "Range Not Satisfiable", "text/plain; charset=utf-8", ByteArray(0),
                    listOf("Content-Range" to "bytes */$len"), keep, headOnly
                )
                return keep
            }

            val count = if (len == 0L) 0L else end - start + 1
            val hdr = ArrayList<Pair<String, String>>()
            hdr.add("Content-Type" to mime)
            hdr.add("Content-Length" to count.toString())
            hdr.add("Accept-Ranges" to "bytes")
            if (partial) hdr.add("Content-Range" to "bytes $start-$end/$len")
            if (dl) {
                val ascii = doc.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                hdr.add(
                    "Content-Disposition" to
                        "attachment; filename=\"$ascii\"; filename*=UTF-8''" + Uri.encode(doc.name)
                )
            }
            writeAll(sc, head(if (partial) 206 else 200, if (partial) "Partial Content" else "OK", hdr, keep))
            if (headOnly || count == 0L) return keep

            ServerState.active.incrementAndGet()
            try {
                stream(pfd, fileUri, sc, start, count)
            } finally {
                ServerState.active.decrementAndGet()
            }
            return keep
        } finally {
            try {
                pfd.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun stream(
        pfd: ParcelFileDescriptor,
        fileUri: Uri,
        sc: SocketChannel,
        start: Long,
        count: Long
    ) {
        var sent = 0L
        var fallback = false
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
                ServerState.bytesSent.addAndGet(n)
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
                    ServerState.bytesSent.addAndGet(r.toLong())
                }
            }
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

        val crumbs = StringBuilder("<a href='/'>Home</a>")
        var acc = "/"
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
@media(max-width:600px){.dt{display:none}}
</style></head><body><div class="wrap">
<div class="top"><div class="logo">⚡</div><div><h1>Flash Transfer</h1><div class="sub">Lightning-fast Wi-Fi file sharing</div></div></div>
<div class="crumbs">$crumbs</div>
<input class="search" placeholder="Search in this folder..." oninput="f(this.value)">
<div class="card">$rows</div>
<div class="stats">$folders folders · $files files · ${Fmt.size(total)}</div>
<div class="foot">Flash Transfer · Developed by Rahul Mandal</div>
</div>
<script>
function f(q){q=q.toLowerCase();var r=document.querySelectorAll('.row');for(var i=0;i<r.length;i++){r[i].style.display=r[i].getAttribute('data-n').indexOf(q)>-1?'':'none';}}
</script></body></html>"""
    }

    companion object {
        /** sendfile chunk: big enough for speed, small enough for a smooth live-speed readout */
        private const val CHUNK = 1L shl 20
    }
}
