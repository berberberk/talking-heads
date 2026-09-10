package talkingheads.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import talkingheads.config.AppConfig

class ElevenLabsScribeTokenClientTest {
    @Test
    fun `requests realtime Scribe token with server-side credential`() = kotlinx.coroutines.test.runTest {
        var requestUrl = ""
        var apiKey = ""
        var requestMethod = HttpMethod.Get
        val client = HttpClient(MockEngine { request ->
            requestUrl = request.url.toString()
            apiKey = request.headers["xi-api-key"].orEmpty()
            requestMethod = request.method
            respond("{\"token\":\"sutkn_test\"}".toByteArray(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })

        assertEquals("sutkn_test", ElevenLabsScribeTokenClient(client, config()).createToken())
        assertEquals("https://api.elevenlabs.io/v1/single-use-token/realtime_scribe", requestUrl)
        assertEquals(HttpMethod.Post, requestMethod)
        assertEquals("server-only-key", apiKey)
    }

    @Test
    fun `rejects blank token and upstream failures without exposing response body`() = kotlinx.coroutines.test.runTest {
        val blank = HttpClient(MockEngine {
            respond("{\"token\":\" \"}".toByteArray(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val failure = HttpClient(MockEngine {
            respond("secret upstream details".toByteArray(), HttpStatusCode.Forbidden)
        })

        assertFailsWith<ScribeTokenException> { ElevenLabsScribeTokenClient(blank, config()).createToken() }
        val error = assertFailsWith<ScribeTokenException> { ElevenLabsScribeTokenClient(failure, config()).createToken() }
        assertNotEquals("secret upstream details", error.message)
    }

    private fun config(): AppConfig = AppConfig.fromEnvironment(
        mapOf(
            "GEMINI_API_KEY" to "key",
            "ELEVENLABS_API_KEY" to "server-only-key",
        ),
    )
}
