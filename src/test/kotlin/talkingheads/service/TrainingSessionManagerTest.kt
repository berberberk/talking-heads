package talkingheads.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import talkingheads.config.AppConfig
import talkingheads.llm.LlmClient
import talkingheads.model.ServerEvent
import talkingheads.model.TrainingMessage
import talkingheads.model.TrainingSession
import talkingheads.repository.TrainingRepository
import talkingheads.scenario.ScenarioCatalog
import talkingheads.scenario.ScenarioPromptProvider
import talkingheads.scenario.ScenarioResolver
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
import talkingheads.scenario.ScenarioSelection

@OptIn(ExperimentalCoroutinesApi::class)
class TrainingSessionManagerTest {
    /** Повторный finish возвращает сохранённый отчёт и не повторяет вызов Gemini. */
    @Test
    fun `finish is idempotent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = InMemoryRepository()
        var evaluationCalls = 0
        val llm = object : LlmClient {
            override fun streamReply(history: List<TrainingMessage>, systemPrompt: String): Flow<String> = flow { emit("answer") }
            override suspend fun generateText(prompt: String, systemPrompt: String, jsonMode: Boolean): String {
                evaluationCalls++
                return validEvaluationJson()
            }
        }
        val manager = manager(repo, llm, testConfig(), dispatcher)
        val session = manager.createSession()
        manager.submitUserMessage(session.id, 1, "ответ")
        advanceUntilIdle()

        val first = manager.finish(session.id)
        val second = manager.finish(session.id)

        assertEquals(1, evaluationCalls)
        assertEquals(first.report, second.report)
        assertEquals(talkingheads.model.SessionStatus.FINISHED, second.status)
    }

    /** Ошибка evaluation сохраняет завершённую тренировку и разрешает безопасный retry. */
    @Test
    fun `failed evaluation keeps finished session and marks report failed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = InMemoryRepository()
        val llm = object : LlmClient {
            override fun streamReply(history: List<TrainingMessage>, systemPrompt: String): Flow<String> = flow { emit("answer") }
            override suspend fun generateText(prompt: String, systemPrompt: String, jsonMode: Boolean) = "not-json"
        }
        val manager = manager(repo, llm, testConfig(), dispatcher)
        val session = manager.createSession(ScenarioSelection(presetId = "sales-discovery"))
        manager.submitUserMessage(session.id, 1, "ответ")
        advanceUntilIdle()

        manager.finish(session.id)
        val saved = requireNotNull(repo.get(session.id))
        assertEquals(talkingheads.model.SessionStatus.FINISHED, saved.status)
        assertEquals(talkingheads.model.ReportStatus.FAILED, saved.reportStatus)
        assertTrue(saved.finishedAt != null)
        assertEquals(null, saved.report)
    }

    /** Повторный запуск отчёта после FAILED сохраняет исходный finishedAt и не меняет transcript. */
    @Test
    fun `retry report turns failed report ready without changing finished timestamp`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = InMemoryRepository()
        var calls = 0
        val llm = object : LlmClient {
            override fun streamReply(history: List<TrainingMessage>, systemPrompt: String): Flow<String> = flow { emit("answer") }
            override suspend fun generateText(prompt: String, systemPrompt: String, jsonMode: Boolean): String {
                calls++
                return if (calls <= 2) "not-json" else validEvaluationJson()
            }
        }
        val manager = manager(repo, llm, testConfig(), dispatcher)
        val session = manager.createSession()
        manager.submitUserMessage(session.id, 1, "ответ")
        advanceUntilIdle()

        val failed = manager.finish(session.id)
        val retried = manager.retryReport(session.id)

        assertEquals(talkingheads.model.ReportStatus.FAILED, failed.reportStatus)
        assertEquals(talkingheads.model.ReportStatus.READY, retried.reportStatus)
        assertEquals(failed.finishedAt, retried.finishedAt)
        assertTrue(retried.messages.isNotEmpty())
        assertEquals(3, calls)
    }

    /** Закрепляет выбранный сценарий в сессии и передаёт его только в system prompt. */
    @Test
    fun `session keeps scenario snapshot for subsequent generation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = InMemoryRepository()
        val config = testConfig()
        var capturedSystemPrompt = ""
        val llm = object : LlmClient {
            override fun streamReply(history: List<TrainingMessage>, systemPrompt: String): Flow<String> = flow {
                capturedSystemPrompt = systemPrompt
                emit("answer")
            }

            override suspend fun generateText(prompt: String, systemPrompt: String, jsonMode: Boolean) = "{}"
        }
        val manager = manager(repo, llm, config, dispatcher)
        val session = manager.createSession(ScenarioSelection(presetId = "sales-discovery"))

        manager.submitUserMessage(session.id, 1, "Начнём")
        advanceUntilIdle()

        val saved = requireNotNull(repo.get(session.id))
        assertEquals("sales-discovery", saved.scenarioSnapshot?.definition?.id)
        assertContains(capturedSystemPrompt, "Выявление потребности B2B-клиента")
        assertContains(capturedSystemPrompt, "Конфигурация тренировки от методиста")
    }

    @Test
    fun `new generation cancels old response and keeps latest`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = InMemoryRepository()
        val config = testConfig()
        val llm = object : LlmClient {
            override fun streamReply(history: List<TrainingMessage>, systemPrompt: String): Flow<String> = flow {
                val last = history.last().text
                if (last == "first") {
                    emit("old-")
                    delay(500)
                    emit("should-not-arrive")
                } else {
                    emit("new-answer")
                }
            }

            override suspend fun generateText(prompt: String, systemPrompt: String, jsonMode: Boolean): String =
                "{\"summary\":\"ok\",\"criteria\":[],\"mistakes\":[],\"recommendations\":[]}"
        }
        val manager = manager(repo, llm, config, dispatcher)
        val session = manager.createSession()
        val events = mutableListOf<ServerEvent>()
        val collector = launch(dispatcher) {
            manager.events(session.id).toList(events)
        }

        manager.submitUserMessage(session.id, 1, "first")
        testScheduler.advanceTimeBy(1)
        manager.submitUserMessage(session.id, 2, "second")
        advanceUntilIdle()

        val saved = repo.get(session.id)!!
        assertEquals(2, saved.latestGenerationId)
        assertTrue(saved.messages.any { it.text == "new-answer" })
        assertTrue(saved.messages.none { it.text.contains("should-not-arrive") })
        assertTrue(events.any { it is ServerEvent.GenerationCancelled && it.generationId == 1L })
        assertTrue(events.any { it is ServerEvent.AssistantCompleted && it.generationId == 2L })
        collector.cancel()
    }

    /** Сохраняет browser correlation-id, чтобы acceptance CSV не смешивал разные голосовые turn. */
    @Test
    fun `browser metric keeps turn correlation id`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = InMemoryRepository()
        val manager = manager(repo, object : LlmClient {
            override fun streamReply(history: List<TrainingMessage>, systemPrompt: String): Flow<String> = flow { }
            override suspend fun generateText(prompt: String, systemPrompt: String, jsonMode: Boolean) = "{}"
        }, testConfig(), dispatcher)
        val session = manager.createSession()

        manager.recordMetric(session.id, "browser_voice_end_to_first_audio_ms", null, 123, "turn-1")

        assertEquals("turn-1", repo.get(session.id)?.metrics?.single()?.turnId)
    }

    /** Создаёт менеджер с реальными сценарными зависимостями и тестовыми adapters. */
    private fun manager(repo: InMemoryRepository, llm: LlmClient, config: AppConfig, dispatcher: TestDispatcher): TrainingSessionManager {
        val reportService = ReportService(llm, Json { ignoreUnknownKeys = true })
        return TrainingSessionManager(
            repository = repo,
            llmClient = llm,
            reportService = reportService,
            appConfig = config,
            scenarioResolver = ScenarioResolver(ScenarioCatalog()),
            scenarioPromptProvider = ScenarioPromptProvider(),
            coroutineContext = dispatcher,
        )
    }

    private fun testConfig() = AppConfig(
        host = "localhost",
        port = 8080,
        geminiApiKey = "test",
        geminiModel = "gemini-2.5-flash-lite",
        didAgentId = null,
        didClientKey = null,
        dataDir = Path.of("build/test-data"),
        allowedOrigins = emptySet(),
        trainingSystemPrompt = "test",
        trainingCriteria = null,
    )

    private fun validEvaluationJson() = """
        {"overallScore":4,"summary":"Итог","recommendations":["Практикуйте уточняющие вопросы"],"criteria":[
        {"name":"Полнота ответа","score":4,"comment":"Комментарий","evidence":"Факт"},
        {"name":"Следование сценарию","score":4,"comment":"Комментарий","evidence":"Факт"},
        {"name":"Качество коммуникации","score":4,"comment":"Комментарий","evidence":"Факт"}]}
    """.trimIndent()

    private class InMemoryRepository : TrainingRepository {
        private val data = linkedMapOf<String, TrainingSession>()
        override suspend fun create(session: TrainingSession): TrainingSession = session.also { data[it.id] = it }
        override suspend fun get(sessionId: String): TrainingSession? = data[sessionId]
        override suspend fun save(session: TrainingSession): TrainingSession = session.also { data[it.id] = it }
        override suspend fun update(
            sessionId: String,
            transform: (TrainingSession) -> TrainingSession,
        ): TrainingSession? {
            val current = data[sessionId] ?: return null
            return transform(current).also { data[sessionId] = it }
        }
    }
}
