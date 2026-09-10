package talkingheads.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    @Test
    fun `accepts llm-service compatibility environment names`() {
        val config = AppConfig.fromEnvironment(
            mapOf(
                "GEMINI_API_KEY" to "key",
                "CHAT_API_URL" to "http://backend:8080/",
                "FRONTEND_HOST" to "frontend:8000",
                "DATABASE_URL" to "jdbc:postgresql://db:5432/speaking_character",
            ),
        )

        assertEquals("http://backend:8080", config.publicApiUrl)
        assertEquals(StorageBackend.POSTGRES, config.storageBackend)
        assertEquals(setOf("http://frontend:8000", "https://frontend:8000"), config.allowedOrigins)
    }

    @Test
    fun `simli mode requires server-side vendor credentials`() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.fromEnvironment(
                mapOf(
                    "GEMINI_API_KEY" to "key",
                    "AVATAR_PROVIDER" to "simli",
                ),
            )
        }
    }

    @Test
    fun `uses Russian Scribe by default`() {
        val config = AppConfig.fromEnvironment(mapOf("GEMINI_API_KEY" to "key"))

        assertEquals("scribe_v2_realtime", config.scribeModel)
        assertEquals("ru", config.scribeLanguageCode)
    }

    @Test
    fun `allows automatic Scribe language detection when language is blank`() {
        val config = AppConfig.fromEnvironment(
            mapOf("GEMINI_API_KEY" to "key", "SCRIBE_LANGUAGE_CODE" to "   "),
        )

        assertEquals(null, config.scribeLanguageCode)
    }
}
