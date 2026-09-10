package talkingheads.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import talkingheads.config.AppConfig
import talkingheads.llm.LlmClient
import talkingheads.model.MessageRole
import talkingheads.model.ReportStatus
import talkingheads.model.ServerEvent
import talkingheads.model.SessionStatus
import talkingheads.model.TrainingMessage
import talkingheads.model.TrainingMetric
import talkingheads.model.TrainingSession
import talkingheads.repository.TrainingRepository
import talkingheads.scenario.ScenarioPromptProvider
import talkingheads.scenario.ScenarioResolver
import talkingheads.scenario.ScenarioSelection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

class TrainingSessionManager(
    private val repository: TrainingRepository,
    private val llmClient: LlmClient,
    private val reportService: ReportService,
    private val appConfig: AppConfig,
    private val scenarioResolver: ScenarioResolver,
    private val scenarioPromptProvider: ScenarioPromptProvider,
    coroutineContext: CoroutineContext,
) {
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    private val runtimes = ConcurrentHashMap<String, SessionRuntime>()

    /** Создаёт тренировку и необратимо закрепляет выбранный сценарий до первого turn. */
    suspend fun createSession(selection: ScenarioSelection? = null): TrainingSession {
        val now = Instant.now().toString()
        val session = TrainingSession(
            id = UUID.randomUUID().toString(),
            createdAt = now,
            updatedAt = now,
            scenarioSnapshot = scenarioResolver.resolve(selection),
        )
        repository.create(session)
        runtimeFor(session)
        return session
    }

    suspend fun getSession(sessionId: String): TrainingSession? = repository.get(sessionId)

    suspend fun events(sessionId: String): Flow<ServerEvent> {
        val session = repository.get(sessionId) ?: throw SessionNotFoundException(sessionId)
        return runtimeFor(session).events.asSharedFlow()
    }

    suspend fun latestGenerationId(sessionId: String): Long {
        val session = repository.get(sessionId) ?: throw SessionNotFoundException(sessionId)
        return runtimeFor(session).currentGenerationId.get()
    }

    suspend fun submitUserMessage(
        sessionId: String,
        generationId: Long,
        text: String,
    ) {
        require(text.isNotBlank()) { "Message text must not be blank" }
        val persisted = repository.get(sessionId) ?: throw SessionNotFoundException(sessionId)
        val runtime = runtimeFor(persisted)
        runtime.commandMutex.withLock {
            startGenerationLocked(sessionId, runtime, generationId, text)
        }
    }

    /** Starts the next monotonic generation and returns its id. */
    suspend fun submitNextUserMessage(
        sessionId: String,
        text: String,
        onGenerationReserved: (Long) -> Unit = {},
    ): Long {
        require(text.isNotBlank()) { "Message text must not be blank" }
        val persisted = repository.get(sessionId) ?: throw SessionNotFoundException(sessionId)
        val runtime = runtimeFor(persisted)
        return runtime.commandMutex.withLock {
            val generationId = runtime.currentGenerationId.get() + 1
            onGenerationReserved(generationId)
            startGenerationLocked(sessionId, runtime, generationId, text)
            generationId
        }
    }

    private suspend fun startGenerationLocked(
        sessionId: String,
        runtime: SessionRuntime,
        generationId: Long,
        text: String,
    ) {
        val persisted = repository.get(sessionId) ?: throw SessionNotFoundException(sessionId)
        check(persisted.status == SessionStatus.ACTIVE) { "Session is already finished" }

        val current = runtime.currentGenerationId.get()
        if (generationId <= current) {
            runtime.emit(ServerEvent.StaleGeneration(generationId, current))
            return
        }

        // Invalidate the old generation before waiting for cancellation, so late chunks are discarded.
        runtime.currentGenerationId.set(generationId)
        runtime.pipelineJob?.cancelAndJoin()

        val now = Instant.now().toString()
        repository.update(sessionId) { session ->
            session.copy(
                latestGenerationId = generationId,
                updatedAt = now,
                messages = session.messages + TrainingMessage(
                    role = MessageRole.USER,
                    text = text.trim(),
                    generationId = generationId,
                    createdAt = now,
                ),
            )
        } ?: throw SessionNotFoundException(sessionId)

        runtime.pipelineJob = scope.launch {
            runGeneration(sessionId, generationId, runtime)
        }
    }

    suspend fun interrupt(sessionId: String, generationId: Long) {
        val persisted = repository.get(sessionId) ?: throw SessionNotFoundException(sessionId)
        val runtime = runtimeFor(persisted)
        runtime.commandMutex.withLock {
            // Race-safe: an interrupt for generation N must never cancel a newer generation N+1.
            if (runtime.currentGenerationId.get() != generationId) return@withLock
            val startedNanos = System.nanoTime()
            runtime.pipelineJob?.cancelAndJoin()
            runtime.pipelineJob = null
            recordMetric(
                sessionId = sessionId,
                name = "backend_interrupt_cancel",
                generationId = generationId,
                valueMs = nanosToMs(System.nanoTime() - startedNanos),
            )
        }
    }

    /** Сохраняет одно измерение существующей telemetry; browser turnId сохраняет correlation без новой схемы. */
    suspend fun recordMetric(
        sessionId: String,
        name: String,
        generationId: Long?,
        valueMs: Long,
        turnId: String? = null,
    ) {
        require(name.isNotBlank()) { "Metric name must not be blank" }
        require(valueMs >= 0) { "Metric valueMs must be non-negative" }
        repository.update(sessionId) { session ->
            session.copy(
                updatedAt = Instant.now().toString(),
                metrics = session.metrics + TrainingMetric(
                    name = name.trim(),
                    generationId = generationId,
                    turnId = turnId,
                    valueMs = valueMs,
                    recordedAt = Instant.now().toString(),
                ),
            )
        } ?: throw SessionNotFoundException(sessionId)
    }

    suspend fun finish(sessionId: String): TrainingSession {
        val persisted = repository.get(sessionId) ?: throw SessionNotFoundException(sessionId)
        val runtime = runtimeFor(persisted)

        return runtime.commandMutex.withLock {
            runtime.currentGenerationId.incrementAndGet()
            runtime.pipelineJob?.cancelAndJoin()
            runtime.pipelineJob = null

            val session = repository.get(sessionId) ?: throw SessionNotFoundException(sessionId)
            if (session.status == SessionStatus.FINISHED && (session.reportStatus == ReportStatus.READY || session.report != null)) {
                return@withLock session
            }
            if (session.status == SessionStatus.FINISHED && session.reportStatus == ReportStatus.GENERATING) {
                return@withLock session
            }

            // Сначала необратимо сохраняем сам факт завершения. Сбой Gemini не имеет
            // права вернуть участника в ACTIVE или потерять сохранённую стенограмму.
            val now = Instant.now().toString()
            val finished = repository.update(sessionId) { latest ->
                latest.copy(
                    status = SessionStatus.FINISHED,
                    finishedAt = latest.finishedAt ?: now,
                    updatedAt = now,
                    latestGenerationId = runtime.currentGenerationId.get(),
                    reportStatus = ReportStatus.GENERATING,
                    reportError = null,
                )
            } ?: throw SessionNotFoundException(sessionId)
            generateReportLocked(sessionId, runtime, finished)
        }
    }

    /** Повторно запускает evaluation только для уже завершённой тренировки без готового отчёта. */
    suspend fun retryReport(sessionId: String): TrainingSession {
        val persisted = repository.get(sessionId) ?: throw SessionNotFoundException(sessionId)
        val runtime = runtimeFor(persisted)
        return runtime.commandMutex.withLock {
            val session = repository.get(sessionId) ?: throw SessionNotFoundException(sessionId)
            check(session.status == SessionStatus.FINISHED) { "Training is still active" }
            if (session.reportStatus == ReportStatus.READY || session.report != null || session.reportStatus == ReportStatus.GENERATING) {
                return@withLock session
            }
            val generating = repository.update(sessionId) { latest ->
                latest.copy(updatedAt = Instant.now().toString(), reportStatus = ReportStatus.GENERATING, reportError = null)
            } ?: throw SessionNotFoundException(sessionId)
            generateReportLocked(sessionId, runtime, generating)
        }
    }

    /** Вызывает evaluator после persist FINISHED и сохраняет только безопасный terminal result. */
    private suspend fun generateReportLocked(sessionId: String, runtime: SessionRuntime, session: TrainingSession): TrainingSession = try {
        val report = reportService.build(
            messages = session.messages,
            scenario = session.scenarioSnapshot,
            fallbackCriteria = DEFAULT_EVALUATION_CRITERIA,
        )
        val ready = repository.update(sessionId) { latest ->
            latest.copy(updatedAt = Instant.now().toString(), report = report, reportStatus = ReportStatus.READY, reportError = null)
        } ?: throw SessionNotFoundException(sessionId)
        runtime.emit(ServerEvent.ReportReady(report))
        ready
    } catch (exception: Exception) {
        val failed = repository.update(sessionId) { latest ->
            latest.copy(updatedAt = Instant.now().toString(), report = null, reportStatus = ReportStatus.FAILED, reportError = REPORT_FAILURE_MESSAGE)
        } ?: throw SessionNotFoundException(sessionId)
        runtime.emit(ServerEvent.ReportFailed(REPORT_FAILURE_MESSAGE))
        failed
    }

    private suspend fun runGeneration(
        sessionId: String,
        generationId: Long,
        runtime: SessionRuntime,
    ) {
        val startedNanos = System.nanoTime()
        var firstTokenNanos: Long? = null
        val buffer = StringBuilder()
        val segmenter = StreamingTextSegmenter()
        var segmentIndex = 0

        runtime.emit(
            ServerEvent.GenerationStarted(
                generationId = generationId,
                receivedAt = Instant.now().toString(),
            ),
        )

        try {
            val session = repository.get(sessionId) ?: throw SessionNotFoundException(sessionId)
            val systemPrompt = scenarioPromptProvider.compose(appConfig.trainingSystemPrompt, session.scenarioSnapshot)
            val context = session.messages.takeLast(appConfig.maxContextMessages)
            llmClient.streamReply(context, systemPrompt).collect { delta ->
                if (runtime.currentGenerationId.get() != generationId) return@collect
                if (firstTokenNanos == null) firstTokenNanos = System.nanoTime()
                buffer.append(delta)
                runtime.emit(
                    ServerEvent.AssistantDelta(
                        generationId = generationId,
                        delta = delta,
                        serverAt = Instant.now().toString(),
                    ),
                )
                segmenter.append(delta).forEach { segment ->
                    runtime.emit(
                        ServerEvent.AssistantSegment(
                            generationId = generationId,
                            segmentIndex = segmentIndex++,
                            text = segment,
                            serverAt = Instant.now().toString(),
                        ),
                    )
                }
            }

            if (runtime.currentGenerationId.get() != generationId) return
            segmenter.flush()?.let { tail ->
                runtime.emit(
                    ServerEvent.AssistantSegment(
                        generationId = generationId,
                        segmentIndex = segmentIndex,
                        text = tail,
                        serverAt = Instant.now().toString(),
                    ),
                )
            }
            val text = buffer.toString().trim()
            if (text.isEmpty()) {
                runtime.emit(
                    ServerEvent.Error(
                        code = "GENERATION_EMPTY",
                        message = "LLM returned an empty response",
                        generationId = generationId,
                    ),
                )
                return
            }
            appendAssistantMessage(sessionId, generationId, text, interrupted = false)

            val totalMs = nanosToMs(System.nanoTime() - startedNanos)
            val firstTokenMs = firstTokenNanos?.let { nanosToMs(it - startedNanos) }
            firstTokenMs?.let { recordMetric(sessionId, "backend_first_token", generationId, it) }
            recordMetric(sessionId, "backend_generation_total", generationId, totalMs)
            runtime.emit(
                ServerEvent.AssistantCompleted(
                    generationId = generationId,
                    text = text,
                    firstTokenLatencyMs = firstTokenMs,
                    totalLatencyMs = totalMs,
                ),
            )
        } catch (e: CancellationException) {
            val partial = buffer.toString().trim()
            if (partial.isNotEmpty()) {
                appendAssistantMessage(sessionId, generationId, partial, interrupted = true)
            }
            runtime.emit(
                ServerEvent.GenerationCancelled(
                    generationId = generationId,
                    partialText = partial,
                ),
            )
            throw e
        } catch (e: Exception) {
            val partial = buffer.toString().trim()
            if (partial.isNotEmpty()) {
                appendAssistantMessage(sessionId, generationId, partial, interrupted = true)
            }
            runtime.emit(
                ServerEvent.Error(
                    code = "GENERATION_FAILED",
                    message = e.message ?: "LLM generation failed",
                    generationId = generationId,
                ),
            )
        }
    }

    private suspend fun appendAssistantMessage(
        sessionId: String,
        generationId: Long,
        text: String,
        interrupted: Boolean,
    ) {
        val now = Instant.now().toString()
        repository.update(sessionId) { session ->
            session.copy(
                updatedAt = now,
                messages = session.messages + TrainingMessage(
                    role = MessageRole.MODEL,
                    text = text,
                    generationId = generationId,
                    createdAt = now,
                    interrupted = interrupted,
                ),
            )
        }
    }

    private companion object {
        val DEFAULT_EVALUATION_CRITERIA = listOf("Полнота ответа", "Следование сценарию", "Качество коммуникации")
        const val REPORT_FAILURE_MESSAGE = "Не удалось сформировать оценку. Попробуйте ещё раз."
    }

    private fun runtimeFor(session: TrainingSession): SessionRuntime =
        runtimes.computeIfAbsent(session.id) {
            SessionRuntime(initialGenerationId = session.latestGenerationId)
        }

    private fun nanosToMs(nanos: Long): Long = nanos / 1_000_000

    private class SessionRuntime(initialGenerationId: Long) {
        val currentGenerationId = AtomicLong(initialGenerationId)
        val commandMutex = Mutex()
        val events = MutableSharedFlow<ServerEvent>(
            replay = 0,
            extraBufferCapacity = 256,
        )
        var pipelineJob: Job? = null

        suspend fun emit(event: ServerEvent) {
            events.emit(event)
        }
    }
}

class SessionNotFoundException(sessionId: String) :
    NoSuchElementException("Training session $sessionId not found")
