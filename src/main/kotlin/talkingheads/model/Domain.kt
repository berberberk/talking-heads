package talkingheads.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import talkingheads.scenario.ScenarioSnapshot

@Serializable
enum class SessionStatus {
    ACTIVE,
    FINISHED,
}

/** Отражает независимый жизненный цикл формирования итоговой оценки. */
@Serializable
enum class ReportStatus {
    NOT_STARTED,
    GENERATING,
    READY,
    FAILED,
}

@Serializable
enum class MessageRole {
    @SerialName("user") USER,
    @SerialName("model") MODEL,
}

@Serializable
data class TrainingMessage(
    val role: MessageRole,
    val text: String,
    val generationId: Long,
    val createdAt: String,
    val interrupted: Boolean = false,
)

@Serializable
data class CriterionScore(
    val name: String,
    val score: Int,
    val comment: String,
    val evidence: String,
    /** Связывает evidence только с фактической репликой сотрудника. */
    val evidenceGenerationId: Long? = null,
)

@Serializable
data class TrainingMetric(
    val name: String,
    val generationId: Long? = null,
    /** Correlation-id browser turn; отсутствует у server-only измерений. */
    val turnId: String? = null,
    val valueMs: Long,
    val recordedAt: String,
)

@Serializable
data class TrainingReport(
    val generatedAt: String,
    val summary: String,
    val overallScore: Int,
    val criteria: List<CriterionScore> = emptyList(),
    val recommendations: List<String> = emptyList(),
    val evaluationStatus: String,
)

@Serializable
data class TrainingSession(
    val id: String,
    val createdAt: String,
    val updatedAt: String,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val scenarioSnapshot: ScenarioSnapshot? = null,
    /** Временно сохраняет legacy criteria до переноса строгого отчёта. */
    val criteria: String? = null,
    val latestGenerationId: Long = -1,
    val messages: List<TrainingMessage> = emptyList(),
    val metrics: List<TrainingMetric> = emptyList(),
    val report: TrainingReport? = null,
    /** Время необратимого завершения тренировки; сохраняется при retry отчёта. */
    val finishedAt: String? = null,
    /** Состояние отдельного процесса evaluation без отката самой тренировки. */
    val reportStatus: ReportStatus = ReportStatus.NOT_STARTED,
    /** Безопасное пользовательское объяснение сбоя evaluation без деталей provider. */
    val reportError: String? = null,
)
