package io.bhodl.desktop.integration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * Tiny JSON-RPC client for the regtest bitcoind container.
 *
 * Hand-rolled rather than pulling in BitcoinJ / RxBitcoin — we only
 * need the four verbs the smoke tests drive (`createwallet`,
 * `getnewaddress`, `generatetoaddress`, `sendtoaddress`), and the
 * dependencies we'd add to test code do not need to leak into the
 * main app classpath.
 *
 * Errors come back as [`BitcoinRpcException`] with the original code +
 * message from Core so callers can match on `(-1, "RPC misformatted")`
 * style failures explicitly.
 */
public class BitcoinRpcClient(
    private val baseUrl: String,
    private val user: String = BitcoindRegtestContainer.RPC_USER,
    private val password: String = BitcoindRegtestContainer.RPC_PASSWORD,
) {
    private val http: HttpClient = HttpClient.newHttpClient()
    private val json: Json = Json { ignoreUnknownKeys = true }

    /**
     * Generic call. Pass the JSON-RPC method name and a list of params
     * already encoded as `JsonElement`. Returns the `result` payload.
     */
    public fun call(method: String, params: List<JsonElement> = emptyList()): JsonElement {
        val body = buildJsonObject {
            put("jsonrpc", "1.0")
            put("id", "bhodl-it")
            put("method", method)
            put("params", buildJsonArray { params.forEach { add(it) } })
        }.toString()
        val authHeader = "Basic " +
            Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        val req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", authHeader)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        val parsed = json.parseToJsonElement(resp.body()).jsonObject
        val error = parsed["error"]
        if (error != null && error.toString() != "null") {
            val errorObj = error.jsonObject
            val code = errorObj["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val message = errorObj["message"]?.jsonPrimitive?.content ?: error.toString()
            throw BitcoinRpcException(code, message)
        }
        return parsed["result"]
            ?: throw IllegalStateException("RPC response missing 'result': ${resp.body()}")
    }

    /** Create a wallet (idempotent — a "wallet already exists" error is swallowed). */
    public fun createWalletIfMissing(name: String) {
        try {
            call("createwallet", listOf(JsonPrimitive(name)))
        } catch (e: BitcoinRpcException) {
            // -4 = "Wallet already loaded" or already exists.
            if (e.code != -4) throw e
        }
    }

    /** `getnewaddress` returning a fresh address from the on-node wallet. */
    public fun getNewAddress(): String =
        call("getnewaddress").jsonPrimitive.content

    /** Mine `count` blocks paying out to `address`. Returns the block hashes. */
    public fun generateToAddress(count: Int, address: String): List<String> {
        val result = call(
            "generatetoaddress",
            listOf(JsonPrimitive(count), JsonPrimitive(address)),
        )
        return (result as kotlinx.serialization.json.JsonArray).map {
            it.jsonPrimitive.content
        }
    }

    /** Send `amountBtc` BTC from the on-node wallet to `address`. Returns txid. */
    public fun sendToAddress(address: String, amountBtc: String): String =
        call(
            "sendtoaddress",
            listOf(JsonPrimitive(address), JsonPrimitive(amountBtc.toBigDecimal())),
        ).jsonPrimitive.content

    /** Best-block height. */
    public fun blockCount(): Int =
        call("getblockcount").jsonPrimitive.content.toInt()
}

public class BitcoinRpcException(
    public val code: Int,
    message: String,
) : RuntimeException("RPC error $code: $message")
