package talkingheads.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import talkingheads.model.MessageRole
import talkingheads.model.TrainingMessage

/** Проверяет SSE-парсинг Gemini и безопасное переключение модели до первого слова. */
class GeminiLlmClientTest {
    /** Переключает модель после provider error, пока пользователю ещё не отправлен текст. */
    @Test
    fun `falls back before first delta and streams fallback text`() = kotlinx.coroutines.test.runTest {
        val requestedModels = mutableListOf<String>()
        val client = GeminiLlmClient(
            httpClient = HttpClient(MockEngine { request ->
                val url = request.url.toString()
                requestedModels += url.substringAfter("/models/").substringBefore(":")
                if (url.contains("primary-model")) {
                    respond("quota exhausted", HttpStatusCode.TooManyRequests)
                } else {
                    respond(
                        content = """
                            data: {"candidates":[{"content":{"parts":[{"text":"Привет"}]}}]}

                            data: {"candidates":[{"content":{"parts":[{"text":", коллега!"}]}}]}

                            data: [DONE]
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/event-stream"),
                    )
                }
            }),
            json = Json { ignoreUnknownKeys = true },
            apiKey = "test-key",
            model = "primary-model",
            fallbackModels = listOf("fallback-model"),
        )

        val text = client.streamReply(listOf(userMessage()), "system").toList().joinToString("")

        assertEquals("Привет, коллега!", text)
        assertEquals(listOf("primary-model", "fallback-model"), requestedModels)
    }

    /** Не передаёт внутренние thought-parts в ответ, который затем будет озвучен. */
    @Test
    fun `filters thought parts from SSE response`() = kotlinx.coroutines.test.runTest {
        val client = GeminiLlmClient(
            httpClient = HttpClient(MockEngine {
                respond(
                    content = """data: {"candidates":[{"content":{"parts":[{"text":"скрытая цепочка","thought":true},{"text":"Готовый ответ"}]}}]}""",
                    status = HttpStatusCode.OK,
                )
            }),
            json = Json { ignoreUnknownKeys = true },
            apiKey = "test-key",
            model = "model",
        )

        val chunks = client.streamReply(listOf(userMessage()), "system").toList()

        assertEquals(listOf("Готовый ответ"), chunks)
        assertTrue(chunks.none { it.contains("скрытая") })
    }

    /** Создаёт минимальную пользовательскую реплику для Gemini request body. */
    private fun userMessage() = TrainingMessage(
        role = MessageRole.USER,
        text = "Здравствуйте",
        generationId = 1,
        createdAt = "2026-09-07T00:00:00Z",
    )
}
