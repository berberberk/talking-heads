package talkingheads.service

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import talkingheads.config.AppConfig

class SimliSessionTokenClientTest {
    /** Не допускает попадание ошибочно возвращённого Simli session_token в backend log. */
    @Test
    fun `redacts session token from provider detail`() {
        val client = SimliSessionTokenClient(HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }), config())

        val safe = client.redactProviderDetail(
            "{\"detail\":\"INVALID_FACE_ID\",\"session_token\":\"provider-token\",\"api_key\":\"server-key\"}",
            "server-key",
        )

        assertContains(safe, "INVALID_FACE_ID")
        assertFalse(safe.contains("provider-token"))
        assertFalse(safe.contains("server-key"))
    }

    /** Создаёт изолированную конфигурацию без реальных provider credentials. */
    private fun config(): AppConfig = AppConfig.fromEnvironment(
        mapOf(
            "GEMINI_API_KEY" to "test",
            "AVATAR_PROVIDER" to "simli",
            "SIMLI_API_KEY" to "server-key",
            "SIMLI_FACE_ID" to "face-id",
            "ELEVENLABS_API_KEY" to "eleven-key",
            "ELEVENLABS_VOICE_ID" to "voice-id",
        ),
    )
}
