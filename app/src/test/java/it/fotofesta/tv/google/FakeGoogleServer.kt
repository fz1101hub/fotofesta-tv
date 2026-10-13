package it.fotofesta.tv.google

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.Collections
import java.util.concurrent.TimeUnit

/** Archivio token in memoria per i test. */
class MemoryTokenStore(
    override var accessToken: String? = null,
    override var refreshToken: String? = null,
    override var expiresAtEpochSeconds: Long = 0L,
) : TokenStore {
    var cleared = false
    override fun clear() {
        accessToken = null
        refreshToken = null
        expiresAtEpochSeconds = 0L
        cleared = true
    }
}

/**
 * Server finto che imita gli endpoint OAuth, Picker e di download di Google. Ogni test registra
 * le risposte per percorso; le richieste ricevute restano consultabili in [requests].
 */
class FakeGoogleServer : AutoCloseable {
    private val server = MockWebServer()
    private val routes = mutableMapOf<String, ArrayDeque<MockResponse>>()
    private val fallback = mutableMapOf<String, MockResponse>()
    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val key = "${request.method} ${request.requestUrl!!.encodedPath}"
                synchronized(routes) {
                    routes[key]?.removeFirstOrNull()?.let { return it }
                    fallback[key]?.let { return it }
                }
                return MockResponse().setResponseCode(404).setBody("""{"error":{"message":"no route $key"}}""")
            }
        }
        server.start()
    }

    val oauthBase: HttpUrl get() = server.url("/oauth/")
    val apiBase: HttpUrl get() = server.url("/v1")
    fun url(path: String): String = server.url(path).toString()

    /** Accoda risposte usate una sola volta, nell'ordine. */
    fun enqueue(method: String, path: String, vararg responses: MockResponse) = synchronized(routes) {
        routes.getOrPut("$method $path") { ArrayDeque() }.addAll(responses)
    }

    /** Risposta usata ogni volta che la coda del percorso è vuota. */
    fun always(method: String, path: String, response: MockResponse) = synchronized(routes) {
        fallback["$method $path"] = response
    }

    fun requestsTo(method: String, path: String) =
        requests.filter { it.method == method && it.requestUrl!!.encodedPath == path }

    val client: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    override fun close() {
        client.dispatcher.executorService.shutdown()
        server.shutdown()
    }
}

fun json(body: String, code: Int = 200): MockResponse =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

fun oauthError(error: String, code: Int = 400) = json("""{"error":"$error","error_description":"$error"}""", code)
