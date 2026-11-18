package com.netter.paylist

import com.netter.paylist.delivery.TransportClient
import com.netter.paylist.delivery.TransportRequest
import com.netter.paylist.delivery.TransportResult
import com.netter.paylist.migration.LegacyQkey
import com.netter.paylist.security.SecretProvider
import com.netter.paylist.security.SecretResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared doubles for the instrumented (androidTest) end-to-end tests.
 * The transport is always fake — no billing server exists in CI — and
 * the secret provider is fake only where the test flips readability
 * mid-run; the real [com.netter.paylist.security.SecretVault] is used
 * wherever the Keystore path itself is under test.
 */
class FakeTransport(
    var responder: (TransportRequest) -> TransportResult = { matchedResponse() },
) : TransportClient {
    val requests = mutableListOf<TransportRequest>()

    override suspend fun post(
        request: TransportRequest,
        onSocketOpen: suspend () -> Boolean,
    ): TransportResult {
        requests.add(request)
        return responder(request)
    }

    companion object {
        /** A settled match: the chain stops and the item is done. */
        fun matchedResponse(): TransportResult =
            TransportResult.Http(
                200, emptyMap(),
                """{"match":{"status":"matched","apply":"ok"}}""",
            )

        /** A generic ack for non-payment payloads (test/heartbeat). */
        fun ackResponse(): TransportResult =
            TransportResult.Http(200, emptyMap(), """{"ok":true}""")
    }
}

/** A secret provider whose answer the test flips mid-run. */
class ToggleSecrets(var result: SecretResult) : SecretProvider {
    override fun secretFor(routeId: String): SecretResult = result
}

/** The canonical test payment payload for one tag. */
fun paymentPayload(tag: String, text: String = "Pembayaran Rp100.800 diterima"): JSONObject {
    val dedupeKey = "com.gojek.gopaymerchant|1795000000000|100800|$tag"
    return JSONObject().apply {
        put("type", "payment")
        put("device", "moto-g57")
        put("pkg", "com.gojek.gopaymerchant")
        put("amount", 100_800L)
        put("title", "GoPay")
        put("text", text)
        put("notif_time", "2026-11-18T03:00:00Z")
        put("dedupe_key", dedupeKey)
    }
}

/** The legacy `qkey` for a chain payment with the given tag and routes. */
fun chainQkey(tag: String, routeIds: List<String>): String {
    val dedupeKey = "com.gojek.gopaymerchant|1795000000000|100800|$tag"
    return LegacyQkey.build(dedupeKey, LegacyQkey.KIND_CHAIN, routeIds)
}

/** A chain queue item carrying [payload] over [routeIds]. */
fun chainQueueItem(payload: JSONObject, routeIds: List<String>): JSONObject {
    val qkey = LegacyQkey.build(
        payload.optString("dedupe_key"),
        LegacyQkey.KIND_CHAIN,
        routeIds,
    )
    return JSONObject().apply {
        put("qkey", qkey)
        put("kind", "chain")
        put("route_ids", JSONArray(routeIds))
        put("next", 0)
        put("attempts", 0)
        put("payload", payload)
    }
}
