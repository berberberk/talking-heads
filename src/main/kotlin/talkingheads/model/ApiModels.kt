package talkingheads.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import talkingheads.scenario.ScenarioSelection

@Serializable
data class CreateSessionRequest(
    val scenario: ScenarioSelectionRequest? = null,
)

@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val websocketUrl: String,
)

@Serializable
data class FrontendConfig(
    @SerialName("avatar_provider") val avatarProvider: String,
    @SerialName("chat_api_url") val chatApiUrl: String,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("client_key") val clientKey: String? = null,
    @SerialName("stt_enabled") val sttEnabled: Boolean = false,
    @SerialName("scribe_model") val scribeModel: String? = null,
    @SerialName("scribe_language_code") val scribeLanguageCode: String? = null,
)

/** Возвращает одноразовый токен ElevenLabs Scribe без раскрытия server-side ключа. */
@Serializable
data class ScribeTokenResponse(val token: String)

@Serializable
data class InterruptRequest(
    val generationId: Long,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

/** Безопасное представление завершённой тренировки для report UI без скрытых prompt-инструкций. */
@Serializable
data class TrainingResultDto(
    val sessionId: String,
    val createdAt: String,
    val finishedAt: String? = null,
    val status: SessionStatus,
    val reportStatus: ReportStatus,
    val reportError: String? = null,
    val scenario: ScenarioSummaryDto? = null,
    /** Источник карточки нужен только для безопасного повтора preset-сценария. */
    val scenarioSource: String? = null,
    val messages: List<TrainingResultMessageDto> = emptyList(),
    val metrics: List<TrainingMetric> = emptyList(),
    val report: TrainingReport? = null,
)

/** Описывает одну безопасную строку стенограммы, пригодную для отчёта. */
@Serializable
data class TrainingResultMessageDto(
    val role: MessageRole,
    val text: String,
    val createdAt: String,
    val generationId: Long,
    val interrupted: Boolean = false,
)

@Serializable
data class ChatRequest(
    val sessionId: String? = null,
    val message: String,
    val scenario: ScenarioSelectionRequest? = null,
)

@Serializable
data class ChatResponse(
    val sessionId: String,
    val assistantMessage: String,
    val generationId: Long,
)

@Serializable
data class HistoryMessageDto(
    val role: String,
    val content: String,
    val createdAt: String,
    val generationId: Long,
    val interrupted: Boolean = false,
)

@Serializable
data class HistoryResponse(
    val sessionId: String,
    val messages: List<HistoryMessageDto>,
)

@Serializable
data class ChatReportDto(
    val sessionId: String,
    val generatedAt: String,
    val overallScore: Int,
    val summary: String,
    val recommendations: List<String> = emptyList(),
    val criteria: List<CriterionScore> = emptyList(),
    val evaluationStatus: String,
)

@Serializable
data class ChatStreamRequest(
    val type: String,
    val sessionId: String? = null,
    val message: String? = null,
    val turnId: String? = null,
    val generationId: Long? = null,
    val scenario: ScenarioSelectionRequest? = null,
)

/** Представляет взаимоисключающий выбор preset либо одноразового Markdown-сценария. */
@Serializable
data class ScenarioSelectionRequest(val presetId: String? = null, val markdown: String? = null) {
    /** Преобразует transport-форму в независимую domain-модель. */
    fun toDomain(): ScenarioSelection = ScenarioSelection(presetId, markdown)
}

/** Возвращает безопасную карточку сценария без закрытых LLM-инструкций. */
@Serializable
data class ScenarioSummaryDto(
    val id: String,
    val version: Int,
    val title: String,
    val criteria: List<String>,
    val stages: List<ScenarioStageDto>,
)

/** Описывает один отображаемый этап сценария. */
@Serializable
data class ScenarioStageDto(val id: String, val goal: String)

/** Принимает Markdown для проверки без создания или сохранения сессии. */
@Serializable
data class ScenarioValidationRequest(val markdown: String)

@Serializable
data class ChatStreamEvent(
    val type: String,
    val sessionId: String? = null,
    val generationId: Long? = null,
    val delta: String? = null,
    val error: String? = null,
    val turnId: String? = null,
    val outcome: String? = null,
    val metrics: Map<String, Long>? = null,
    val frameId: Long? = null,
    val cues: List<SubtitleCueEvent>? = null,
)

@Serializable
data class SubtitleCueEvent(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

@Serializable
data class BrowserLatencyMetricsRequest(
    val turnId: String,
    val sessionId: String? = null,
    val outcome: String,
    val metrics: Map<String, Long>,
    val slo: Map<String, Boolean>,
)

@Serializable
data class SimliSessionResponse(
    val token: String,
    val transport: String,
)

@Serializable
sealed interface ClientEvent {
    @Serializable
    @SerialName("user_message")
    data class UserMessage(
        val generationId: Long,
        val text: String,
        val clientSentAt: String? = null,
    ) : ClientEvent

    @Serializable
    @SerialName("interrupt")
    data class Interrupt(
        val generationId: Long,
    ) : ClientEvent

    @Serializable
    @SerialName("metric")
    data class Metric(
        val name: String,
        val generationId: Long? = null,
        val valueMs: Long,
    ) : ClientEvent

    @Serializable
    @SerialName("finish")
    data object Finish : ClientEvent
}

@Serializable
sealed interface ServerEvent {
    @Serializable
    @SerialName("connected")
    data class Connected(
        val sessionId: String,
        val latestGenerationId: Long,
    ) : ServerEvent

    @Serializable
    @SerialName("generation_started")
    data class GenerationStarted(
        val generationId: Long,
        val receivedAt: String,
    ) : ServerEvent

    @Serializable
    @SerialName("assistant_delta")
    data class AssistantDelta(
        val generationId: Long,
        val delta: String,
        val serverAt: String,
    ) : ServerEvent

    @Serializable
    @SerialName("assistant_segment")
    data class AssistantSegment(
        val generationId: Long,
        val segmentIndex: Int,
        val text: String,
        val serverAt: String,
    ) : ServerEvent

    @Serializable
    @SerialName("assistant_completed")
    data class AssistantCompleted(
        val generationId: Long,
        val text: String,
        val firstTokenLatencyMs: Long?,
        val totalLatencyMs: Long,
    ) : ServerEvent

    @Serializable
    @SerialName("generation_cancelled")
    data class GenerationCancelled(
        val generationId: Long,
        val partialText: String,
    ) : ServerEvent

    @Serializable
    @SerialName("stale_generation")
    data class StaleGeneration(
        val generationId: Long,
        val currentGenerationId: Long,
    ) : ServerEvent

    @Serializable
    @SerialName("report_ready")
    data class ReportReady(
        val report: TrainingReport,
    ) : ServerEvent

    /** Сообщает клиенту, что transcript сохранён, но evaluation можно повторить позже. */
    @Serializable
    @SerialName("report_failed")
    data class ReportFailed(
        val message: String,
    ) : ServerEvent

    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val message: String,
        val generationId: Long? = null,
    ) : ServerEvent
}
