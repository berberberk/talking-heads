package talkingheads.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import talkingheads.config.AppConfig
import talkingheads.module

/** Проверяет публичные HTTP-контракты выбора сценария и безопасного результата сессии. */
class ScenarioApiContractTest {
    /** Каталог отдаёт preset summary без скрытых инструкций методиста. */
    @Test
    fun `catalog exposes preset summaries only`() = testApplication {
        application { module(testConfig()) }

        val response = client.get("/api/scenarios")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(body.contains("sales-discovery"))
        assertTrue(body.contains("Выявление потребности B2B-клиента"))
        assertFalse(body.contains("Конфигурация тренировки от методиста"))
    }

    /** Невалидный Markdown не может стать пользовательским сценарием. */
    @Test
    fun `validation rejects malformed custom markdown`() = testApplication {
        application { module(testConfig()) }

        val response = client.post("/api/scenarios/validate") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"markdown":"это не сценарий"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("scenario-meta"))
    }

    /** Неизвестный preset отклоняется до создания session aggregate. */
    @Test
    fun `session creation rejects unknown preset`() = testApplication {
        application { module(testConfig()) }

        val response = client.post("/api/sessions") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"scenario":{"presetId":"missing-preset"}}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("недоступен"))
    }

    /** Выбранный preset закрепляется в сессии, а result не раскрывает его private instructions. */
    @Test
    fun `session result keeps scenario summary without private instructions`() = testApplication {
        application { module(testConfig()) }

        val created = client.post("/api/sessions") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody("""{"scenario":{"presetId":"sales-discovery"}}""")
        }
        val sessionId = Json.parseToJsonElement(created.bodyAsText()).jsonObject["sessionId"]!!.jsonPrimitive.content
        val result = client.get("/api/sessions/$sessionId/result")
        val body = result.bodyAsText()

        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(HttpStatusCode.OK, result.status)
        assertTrue(body.contains("Выявление потребности B2B-клиента"))
        assertFalse(body.contains("Конфигурация тренировки от методиста"))
    }

    /** Создаёт file-backed конфигурацию тестового приложения без vendor credentials. */
    private fun testConfig() = AppConfig(
        host = "127.0.0.1",
        port = 8080,
        geminiApiKey = "test-key",
        geminiModel = "test-model",
        didAgentId = "public-agent",
        didClientKey = "public-key",
        dataDir = Path.of("build/test-data/scenario-api"),
        allowedOrigins = emptySet(),
        trainingSystemPrompt = "Тестовый system prompt",
        trainingCriteria = null,
    )
}
