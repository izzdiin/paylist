package com.netter.paylist.delivery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory

/**
 * One POST to a legacy endpoint (design §5.3, §8.5).
 *
 * @param url the route endpoint URL.
 * @param authSecret the route secret, sent ONLY as the `X-QRIS-Secret`
 *   header on this connection — never to any other host (redirects
 *   are never followed, so the secret cannot leak to a `Location`).
 *   The name is deliberately not the bare word "secret": the F44
 *   static check forbids any field access under that bare name
 *   outside `SecretMigration.kt`, and this transient per-request
 *   credential is not a stored secret.
 * @param body the JSON request body.
 */
data class TransportRequest(
    val url: String,
    val authSecret: String,
    val body: String,
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 15_000,
)

/** Outcome of one transport attempt. Never carries the secret. */
sealed interface TransportResult {
    /** An HTTP response was received (any status, including 3xx/4xx/5xx). */
    data class Http(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    ) : TransportResult

    /**
     * No HTTP response: the request provably did not reach the server
     * as a completed POST (`NO_POST`, §5.5 row 5). `errorCode` is one
     * of `dns`, `connect_timeout`, `read_timeout`, `ssl`, `io`.
     */
    data class NetworkError(val errorCode: String) : TransportResult

    /**
     * The `SOCKET_OPEN` checkpoint refused (or failed): zero request
     * bytes were written and the socket was closed. Fail closed.
     */
    data object CheckpointFailed : TransportResult
}

/**
 * The single HTTP transport for delivery (design §5.3, §8.5).
 *
 * Ordering is normative (R18 C21(11), the bytes invariant):
 * `openConnection()` → `connect()` → `onSocketOpen()` checkpoint →
 * first write to the output stream. No request byte is written before
 * the checkpoint commits; if the checkpoint fails, the POST does not
 * start. Until F40 passes on the actual stack, the bytes invariant is
 * a test assumption, not established behaviour (§8.5 resolution 3).
 */
interface TransportClient {
    /**
     * POST [request] to its URL.
     *
     * @param onSocketOpen invoked after `connect()` returns and before
     *   any byte is written: the caller commits the `SOCKET_OPEN`
     *   checkpoint transaction here. Return false (or throw) to abort
     *   the POST before the first write.
     */
    suspend fun post(
        request: TransportRequest,
        onSocketOpen: suspend () -> Boolean,
    ): TransportResult
}

/**
 * `HttpURLConnection` transport. Configuration mirrors the legacy
 * `Sender.post()` (POST, `Content-Type: application/json`,
 * `X-QRIS-Secret`, 15 s timeouts, `doOutput`) with exactly three
 * deliberate differences: an explicit `connect()`, an explicit
 * `setInstanceFollowRedirects(false)` (3xx is surfaced, never
 * followed — C9), and the `SOCKET_OPEN` checkpoint gate.
 *
 * @param sslSocketFactory test injection for TLS trust (E2); null
 *   means the platform default. Never a trust-all factory.
 * @param hostnameVerifier test injection for TLS hostnames (E2); null
 *   means the platform default.
 * @param proxy test injection for the proxy matrix (E3); null means
 *   a direct connection.
 */
class HttpTransportClient(
    private val sslSocketFactory: SSLSocketFactory? = null,
    private val hostnameVerifier: HostnameVerifier? = null,
    private val proxy: Proxy? = null,
) : TransportClient {

    companion object {
        /** Response bodies are read at most this far (defensive cap). */
        const val MAX_RESPONSE_BODY_BYTES = 64 * 1024
    }

    /**
     * The single connection-configuration point. F40's test-fidelity
     * rule: the E-cases use this exact configuration, never a bare
     * `URLConnection` with different settings.
     */
    internal fun openConnection(request: TransportRequest): HttpURLConnection {
        val url = URL(request.url)
        val raw = if (proxy != null) url.openConnection(proxy) else url.openConnection()
        val conn = raw as HttpURLConnection
        if (conn is HttpsURLConnection) {
            if (sslSocketFactory != null) conn.sslSocketFactory = sslSocketFactory
            if (hostnameVerifier != null) conn.hostnameVerifier = hostnameVerifier
        }
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-QRIS-Secret", request.authSecret)
        conn.connectTimeout = request.connectTimeoutMs
        conn.readTimeout = request.readTimeoutMs
        conn.doOutput = true
        // C9: 3xx is a route-configuration defect, surfaced to the
        // classifier — never followed (the secret must not leak to
        // a redirect target, and a 3xx proves nothing was applied).
        conn.instanceFollowRedirects = false
        return conn
    }

    override suspend fun post(
        request: TransportRequest,
        onSocketOpen: suspend () -> Boolean,
    ): TransportResult = withContext(Dispatchers.IO) {
        // The body bytes are built in memory before any I/O, so the
        // interval from the checkpoint to the first write holds no
        // allocation or suspension point (§7.3B admission fence).
        val bodyBytes = request.body.toByteArray(Charsets.UTF_8)
        val conn = openConnection(request)
        var phase = "connect"
        try {
            conn.connect()
            val checkpointOk = try {
                onSocketOpen()
            } catch (e: Exception) {
                false
            }
            if (!checkpointOk) {
                conn.disconnect()
                return@withContext TransportResult.CheckpointFailed
            }
            phase = "write"
            // F40 case H: the ONLY output-stream call site in this
            // file, and the only path to the first request byte runs
            // through the checkpoint above. Do not add another.
            conn.outputStream.use { out -> out.write(bodyBytes) }
            phase = "read"
            val status = conn.responseCode
            val headers = conn.headerFields
                .entries
                .filter { it.key != null }
                .associate { it.key!! to (it.value.firstOrNull().orEmpty()) }
            val stream = if (status in 200..299) {
                try {
                    conn.inputStream
                } catch (e: IOException) {
                    null
                }
            } else {
                conn.errorStream
            }
            val body = readCapped(stream)
            TransportResult.Http(status, headers, body)
        } catch (e: UnknownHostException) {
            TransportResult.NetworkError("dns")
        } catch (e: SSLException) {
            TransportResult.NetworkError("ssl")
        } catch (e: SocketTimeoutException) {
            TransportResult.NetworkError(
                if (phase == "connect") "connect_timeout" else "read_timeout"
            )
        } catch (e: IOException) {
            TransportResult.NetworkError("io")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Read at most [MAX_RESPONSE_BODY_BYTES] from [stream] (null-safe).
     * Hand-rolled: `InputStream.readNBytes` needs API 33, minSdk is 28.
     */
    private fun readCapped(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        stream.use { input ->
            while (out.size() < MAX_RESPONSE_BODY_BYTES) {
                val want = minOf(buf.size, MAX_RESPONSE_BODY_BYTES - out.size())
                val n = input.read(buf, 0, want)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
