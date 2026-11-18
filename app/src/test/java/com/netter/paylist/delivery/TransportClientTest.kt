package com.netter.paylist.delivery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * F40 pre-socket liveness + transport behaviour (design §10.2, §8.5).
 *
 * The E-cases prove the bytes invariant on the actual stack: no HTTP
 * request byte reaches the origin and no handler runs before the
 * `SOCKET_OPEN` checkpoint. They use the production connection
 * configuration ([HttpTransportClient.openConnection]) — never a bare
 * `URLConnection` with different settings (test-fidelity rule).
 *
 * The failure rule is normative: if E1 fails (headers sent on
 * `connect()`), the checkpoint moves before `openConnection()` or the
 * transport changes, and the failure is recorded as a finding — the
 * assertion is never reworded to pass.
 *
 * The recording servers here are hand-rolled over raw sockets:
 * `com.sun.net.httpserver` does not compile under AGP unit tests
 * (android.jar bootclasspath), and a raw server is also the honest
 * instrument for E1 — it sees exactly the bytes the client sends.
 * A request counts as received only when a full HTTP request
 * (request line + headers + `Content-Length` body) is parsed; a bare
 * TCP/TLS connection that delivers no HTTP request is not a request.
 *
 * Cases A–D and J (admission vs barrier, lease expiry, process kill)
 * belong to the attempt protocol, not the transport: they live in
 * `CutoverTest` (F31/F40 A–D/J). This file owns E1/E2/E3/F/G/H/I.
 */
class TransportClientTest {

    // --- Recording servers ---

    /**
     * A minimal HTTP/1.1 server over a raw socket (optionally TLS).
     * Parses one request at a time; each test makes one connection at
     * a time, so a sequential accept loop is enough.
     */
    private class MiniHttpServer(
        private val status: Int = 200,
        private val responseBody: String = """{"ok":true,"match":{"status":"logged"}}""",
        private val responseHeaders: Map<String, String> = emptyMap(),
        private val delayMs: Long = 0,
        private val serverSslContext: SSLContext? = null,
    ) {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort
        @Volatile var invocations = 0
        @Volatile var connectionsAccepted = 0
        @Volatile var lastMethod: String? = null
        @Volatile var lastPath: String? = null
        @Volatile var lastHeaders: Map<String, String> = emptyMap()
        @Volatile var lastBody: ByteArray = ByteArray(0)

        private val worker = thread(start = true, isDaemon = true) {
            while (!serverSocket.isClosed) {
                try {
                    handle(serverSocket.accept())
                } catch (e: IOException) {
                    break // server socket closed
                }
            }
        }

        private fun handle(raw: Socket) {
            connectionsAccepted++
            try {
                val socket: Socket = if (serverSslContext != null) {
                    // Wrap the accepted socket for TLS: the wrapping
                    // `createSocket` lives on the *client* factory even
                    // for server use — `useClientMode = false` makes
                    // this end the TLS server (STARTTLS-style upgrade).
                    (serverSslContext.socketFactory.createSocket(raw, null, raw.port, true) as SSLSocket)
                        .apply {
                            useClientMode = false
                            startHandshake()
                        }
                } else {
                    raw
                }
                socket.use { s ->
                    s.soTimeout = 3000
                    val input = s.getInputStream()
                    val headerBytes = readUntilDoubleCrlf(input) ?: return
                    val headerText = headerBytes.toString(Charsets.UTF_8)
                    val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
                    if (lines.isEmpty()) return
                    val requestLine = lines[0].split(" ")
                    if (requestLine.size < 2) return
                    val headers = mutableMapOf<String, String>()
                    for (line in lines.drop(1)) {
                        val idx = line.indexOf(':')
                        if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                    }
                    val contentLength = headers.entries
                        .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
                        ?.value?.toIntOrNull() ?: 0
                    val body = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(body, read, contentLength - read)
                        if (n <= 0) return // truncated body: not a request
                        read += n
                    }
                    // A full HTTP request is parsed: now it counts.
                    invocations++
                    lastMethod = requestLine[0]
                    lastPath = requestLine[1]
                    lastHeaders = headers
                    lastBody = body
                    if (delayMs > 0) Thread.sleep(delayMs)
                    val reason = when (status) {
                        200 -> "OK"
                        301 -> "Moved Permanently"
                        302 -> "Found"
                        307 -> "Temporary Redirect"
                        308 -> "Permanent Redirect"
                        404 -> "Not Found"
                        else -> "Status"
                    }
                    val bytes = responseBody.toByteArray(Charsets.UTF_8)
                    val head = buildString {
                        append("HTTP/1.1 $status $reason\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Connection: close\r\n")
                        for ((k, v) in responseHeaders) append("$k: $v\r\n")
                        append("\r\n")
                    }
                    val out = s.getOutputStream()
                    out.write(head.toByteArray(Charsets.UTF_8))
                    out.write(bytes)
                    out.flush()
                }
            } catch (e: Exception) {
                // Timeout / disconnect / TLS failure: the connection
                // produced no parsed request; nothing to record.
            }
        }

        /** Reads until `\r\n\r\n`; null on EOF/timeout before a full header block. */
        private fun readUntilDoubleCrlf(input: java.io.InputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            val window = ArrayDeque<Byte>()
            while (out.size() < 64 * 1024) {
                val b = try {
                    input.read()
                } catch (e: SocketTimeoutException) {
                    return null
                }
                if (b < 0) return null
                out.write(b)
                window.addLast(b.toByte())
                if (window.size > 4) window.removeFirst()
                if (window.size == 4 &&
                    window[0] == '\r'.code.toByte() && window[1] == '\n'.code.toByte() &&
                    window[2] == '\r'.code.toByte() && window[3] == '\n'.code.toByte()
                ) {
                    return out.toByteArray()
                }
            }
            return null
        }

        fun stop() {
            try {
                serverSocket.close()
            } catch (e: IOException) {
            }
            worker.join(3000)
        }
    }

    /** A raw TCP server that records every byte the client sends. */
    private class RawServer {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val received = ByteArrayOutputStream()
        @Volatile var accepted = false
        private val worker = thread(start = true, isDaemon = true) {
            try {
                val client = socket.accept()
                accepted = true
                client.soTimeout = 600
                val input = client.getInputStream()
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        received.write(buf, 0, n)
                    }
                } catch (e: SocketTimeoutException) {
                    // No more bytes within the window: done.
                }
                client.close()
            } catch (e: IOException) {
                // Socket closed while waiting: fine.
            }
        }

        fun stop() {
            try {
                socket.close()
            } catch (e: IOException) {
            }
            worker.join(3000)
        }
    }

    /** TLS fixture: the committed test keystore (CN=localhost). */
    private fun testSslContexts(): Pair<SSLContext, SSLContext> {
        val loader = javaClass.classLoader
        val ks = KeyStore.getInstance("JKS").apply {
            loader.getResourceAsStream("tls/test-keystore.jks").use {
                load(it, "testtest".toCharArray())
            }
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(ks, "testtest".toCharArray()) }
        val serverCtx = SSLContext.getInstance("TLS")
            .apply { init(kmf.keyManagers, null, null) }
        val ts = KeyStore.getInstance("JKS").apply {
            loader.getResourceAsStream("tls/test-truststore.jks").use {
                load(it, "testtest".toCharArray())
            }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(ts) }
        val clientCtx = SSLContext.getInstance("TLS")
            .apply { init(null, tmf.trustManagers, null) }
        return serverCtx to clientCtx
    }

    private fun request(
        url: String,
        connectTimeoutMs: Int = 5000,
        readTimeoutMs: Int = 5000,
    ) = TransportRequest(
        url = url,
        authSecret = "test-secret",
        body = """{"type":"payment","dedupe_key":"k1"}""",
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
    )

    private fun findSourceRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (true) {
            val candidate = File(dir, "src/main/java")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
                ?: throw AssertionError("source root not found under ${System.getProperty("user.dir")}")
        }
    }

    // --- F40 E1: plain HTTP, connect() only ---

    @Test
    fun e1_plainHttpConnect_sendsZeroBytesAndInvokesNoHandler() {
        // Raw-byte proof: zero bytes on the application stream.
        val raw = RawServer()
        try {
            val conn = HttpTransportClient().openConnection(
                request("http://127.0.0.1:${raw.port}/notify.php")
            )
            conn.connect()
            Thread.sleep(900) // longer than the server's read window
            conn.disconnect()
        } finally {
            raw.stop()
        }
        assertEquals(
            "E1: connect() must send zero application bytes; got: ${receivedText(raw)}",
            0,
            raw.received.size(),
        )

        // Handler-execution proof (case I): no handler invocation.
        val http = MiniHttpServer()
        try {
            val conn = HttpTransportClient().openConnection(
                request("http://127.0.0.1:${http.port}/notify.php")
            )
            conn.connect()
            Thread.sleep(700)
            conn.disconnect()
            Thread.sleep(300) // let the handler thread observe the EOF
        } finally {
            http.stop()
        }
        assertEquals(0, http.invocations)
    }

    private fun receivedText(raw: RawServer): String =
        raw.received.toString(Charsets.UTF_8.name()).take(200)

    // --- F40 E2: HTTPS, connect() only ---

    @Test
    fun e2_httpsConnect_invokesNoHandler() {
        val (serverCtx, clientCtx) = testSslContexts()
        val https = MiniHttpServer(serverSslContext = serverCtx)
        try {
            val client = HttpTransportClient(
                sslSocketFactory = clientCtx.socketFactory,
                hostnameVerifier = { hostname, _ -> hostname == "localhost" },
            )
            val conn = client.openConnection(
                request("https://localhost:${https.port}/notify.php")
            )
            conn.connect() // TLS handshake happens here; no HTTP request
            Thread.sleep(700)
            conn.disconnect()
            Thread.sleep(300)
        } finally {
            https.stop()
        }
        assertTrue(
            "E2: the TLS handshake must have completed (connection accepted)",
            https.connectionsAccepted >= 1,
        )
        assertEquals(
            "E2: zero handler invocations before the checkpoint (TLS handshake bytes are transport, not application effect)",
            0,
            https.invocations,
        )
    }

    // --- F40 E3: HTTP proxy ---

    @Test
    fun e3_proxyHttpConnect_forwardsNothing() {
        val proxy = RawServer()
        try {
            val client = HttpTransportClient(
                proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port))
            )
            val conn = client.openConnection(
                request("http://example.invalid/notify.php")
            )
            conn.connect()
            Thread.sleep(900)
            conn.disconnect()
        } finally {
            proxy.stop()
        }
        assertEquals(
            "E3: no application bytes may reach the proxy before the checkpoint; got: ${receivedText(proxy)}",
            0,
            proxy.received.size(),
        )
    }

    @Test
    fun e3_proxyHttpsConnect_isTransportNotApplication() {
        val proxy = RawServer()
        try {
            val client = HttpTransportClient(
                proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port))
            )
            val conn = client.openConnection(
                request("https://example.invalid/notify.php", readTimeoutMs = 2000)
            )
            try {
                // The raw proxy never answers CONNECT, so connect()
                // times out waiting for the tunnel response. That is
                // expected: the assertion is about what was SENT.
                conn.connect()
            } catch (e: IOException) {
                // Expected (timeout waiting for the CONNECT response).
            } finally {
                conn.disconnect()
            }
        } finally {
            proxy.stop()
        }
        val sent = receivedText(proxy)
        // A proxy CONNECT, if the stack sends one on connect(), is
        // transport — recorded, not an application effect. A POST
        // would be an application effect and must never appear.
        assertTrue("E3: no POST may be forwarded before the checkpoint; got: $sent", !sent.contains("POST "))
        println("F40-E3 evidence: proxy received ${proxy.received.size()} bytes: $sent")
    }

    // --- F40 F: redirects are surfaced, never followed ---

    @Test
    fun f_redirectsNeverFollowed_allCodes() {
        val second = MiniHttpServer()
        try {
            for (code in listOf(301, 302, 307, 308)) {
                val first = MiniHttpServer(
                    status = code,
                    responseBody = "",
                    responseHeaders = mapOf("Location" to "http://127.0.0.1:${second.port}/other"),
                )
                try {
                    val result = runBlocking {
                        HttpTransportClient().post(
                            request("http://127.0.0.1:${first.port}/notify.php")
                        ) { true }
                    }
                    val http = result as TransportResult.Http
                    assertEquals("F: $code must be surfaced, not followed", code, http.status)
                    assertEquals(
                        "F: the 3xx Location must be surfaced",
                        "http://127.0.0.1:${second.port}/other",
                        http.headers.entries.firstOrNull {
                            it.key.equals("Location", ignoreCase = true)
                        }?.value,
                    )
                } finally {
                    first.stop()
                }
            }
        } finally {
            second.stop()
        }
        assertEquals("F: zero bytes at the second server in every case", 0, second.invocations)
    }

    // --- F40 G: control — the platform default, recorded as evidence ---

    /**
     * Mirrors the legacy `Sender.post()` configuration exactly: no
     * `setInstanceFollowRedirects` call, no explicit `connect()`.
     */
    private fun openBaselineConnection(url: String, secret: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-QRIS-Secret", secret)
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.doOutput = true
        return conn
    }

    @Test
    fun g_control_platformDefault_redirectBehaviourRecorded() {
        val second = MiniHttpServer()
        try {
            for (code in listOf(302, 307)) {
                val first = MiniHttpServer(
                    status = code,
                    responseBody = "",
                    responseHeaders = mapOf("Location" to "http://127.0.0.1:${second.port}/other"),
                )
                try {
                    val before = second.invocations
                    val conn = openBaselineConnection(
                        "http://127.0.0.1:${first.port}/notify.php",
                        "test-secret",
                    )
                    conn.outputStream.use {
                        it.write("""{"type":"payment"}""".toByteArray(Charsets.UTF_8))
                    }
                    val status = conn.responseCode
                    conn.disconnect()
                    Thread.sleep(300) // let the second server record a follow
                    val followed = second.invocations > before
                    // No assertion on the outcome: G is a control. The
                    // observation is the evidence; if the default
                    // follows a redirect for POST, the v1.1 explicit
                    // `false` is load-bearing.
                    println("F40-G control: HTTP $code with platform default -> status $status, second server hit: $followed")
                    assertTrue("G: a response must arrive", status in 100..599)
                } finally {
                    first.stop()
                }
            }
        } finally {
            second.stop()
        }
    }

    // --- F40 H: a single output-stream call site, after the checkpoint ---

    @Test
    fun h_singleOutputStreamCallSiteAfterCheckpoint() {
        val src = File(
            findSourceRoot(),
            "com/netter/paylist/delivery/TransportClient.kt"
        ).readText()
        val outputSites = Regex("""\.outputStream\b""").findAll(src).count()
        assertEquals(
            "F40-H: exactly one output-stream call site in TransportClient.kt",
            1,
            outputSites,
        )
        val checkpointCall = src.indexOf("onSocketOpen()")
        val writeSite = src.indexOf(".outputStream")
        assertTrue("F40-H: the checkpoint call must precede the first write", checkpointCall in 0 until writeSite)
    }

    // --- Transport behaviour ---

    @Test
    fun post_success_roundTrip() {
        val server = MiniHttpServer()
        try {
            val result = runBlocking {
                HttpTransportClient().post(
                    request("http://127.0.0.1:${server.port}/notify.php")
                ) { true }
            }
            val http = result as TransportResult.Http
            assertEquals(200, http.status)
            assertEquals("""{"ok":true,"match":{"status":"logged"}}""", http.body)
            assertEquals(1, server.invocations)
            assertEquals("POST", server.lastMethod)
            assertEquals("/notify.php", server.lastPath)
            assertEquals(
                "application/json",
                server.lastHeaders.entries.firstOrNull {
                    it.key.equals("Content-Type", ignoreCase = true)
                }?.value,
            )
            assertEquals(
                "test-secret",
                server.lastHeaders.entries.firstOrNull {
                    it.key.equals("X-QRIS-Secret", ignoreCase = true)
                }?.value,
            )
            assertEquals(
                """{"type":"payment","dedupe_key":"k1"}""",
                server.lastBody.toString(Charsets.UTF_8),
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun post_checkpointFalse_writesZeroBytes() {
        val server = MiniHttpServer()
        try {
            val result = runBlocking {
                HttpTransportClient().post(
                    request("http://127.0.0.1:${server.port}/notify.php")
                ) { false }
            }
            assertEquals(TransportResult.CheckpointFailed, result)
            Thread.sleep(300)
            assertEquals("no request may reach the server when the checkpoint refuses", 0, server.invocations)
        } finally {
            server.stop()
        }
    }

    @Test
    fun post_checkpointThrows_writesZeroBytes() {
        val server = MiniHttpServer()
        try {
            val result = runBlocking {
                HttpTransportClient().post(
                    request("http://127.0.0.1:${server.port}/notify.php")
                ) { throw IllegalStateException("checkpoint storage failed") }
            }
            assertEquals(TransportResult.CheckpointFailed, result)
            Thread.sleep(300)
            assertEquals(0, server.invocations)
        } finally {
            server.stop()
        }
    }

    @Test
    fun post_errorBody_readFromErrorStream() {
        val server = MiniHttpServer(status = 404, responseBody = """{"ok":false,"error":"nope"}""")
        try {
            val result = runBlocking {
                HttpTransportClient().post(
                    request("http://127.0.0.1:${server.port}/notify.php")
                ) { true }
            }
            val http = result as TransportResult.Http
            assertEquals(404, http.status)
            assertEquals("""{"ok":false,"error":"nope"}""", http.body)
        } finally {
            server.stop()
        }
    }

    @Test
    fun post_responseHeaders_exposed() {
        val server = MiniHttpServer(
            responseHeaders = mapOf("X-Custom" to "v1", "Retry-After" to "120")
        )
        try {
            val result = runBlocking {
                HttpTransportClient().post(
                    request("http://127.0.0.1:${server.port}/notify.php")
                ) { true }
            }
            val http = result as TransportResult.Http
            assertEquals("v1", http.headers.entries.firstOrNull {
                it.key.equals("X-Custom", ignoreCase = true)
            }?.value)
            assertEquals("120", http.headers.entries.firstOrNull {
                it.key.equals("Retry-After", ignoreCase = true)
            }?.value)
        } finally {
            server.stop()
        }
    }

    @Test
    fun post_connectionRefused_isNetworkError() {
        // A port nothing listens on (found by binding, then closing).
        val closedPort = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            .also { it.close() }.localPort
        val result = runBlocking {
            HttpTransportClient().post(
                request("http://127.0.0.1:$closedPort/notify.php")
            ) { true }
        }
        assertEquals(TransportResult.NetworkError("io"), result)
    }

    @Test
    fun post_unknownHost_isDnsError() {
        val result = runBlocking {
            HttpTransportClient().post(
                request("http://nonexistent.invalid/notify.php")
            ) { true }
        }
        assertEquals(TransportResult.NetworkError("dns"), result)
    }

    @Test
    fun post_readTimeout_isReadTimeoutError() {
        val server = MiniHttpServer(delayMs = 3000)
        try {
            val result = runBlocking {
                HttpTransportClient().post(
                    request(
                        "http://127.0.0.1:${server.port}/notify.php",
                        readTimeoutMs = 1000,
                    )
                ) { true }
            }
            assertEquals(TransportResult.NetworkError("read_timeout"), result)
        } finally {
            server.stop()
        }
    }

    @Test
    fun post_responseBody_cappedAt64k() {
        val big = "x".repeat(200 * 1024)
        val server = MiniHttpServer(responseBody = big)
        try {
            val result = runBlocking {
                HttpTransportClient().post(
                    request("http://127.0.0.1:${server.port}/notify.php")
                ) { true }
            }
            val http = result as TransportResult.Http
            assertEquals(HttpTransportClient.MAX_RESPONSE_BODY_BYTES, http.body.length)
        } finally {
            server.stop()
        }
    }
}
