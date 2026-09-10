package talkingheads.scenario

import kotlinx.serialization.Serializable

/** Описывает неизменяемое содержимое корпоративного тренировочного сценария. */
@Serializable
data class ScenarioDefinition(
    val id: String,
    val version: Int,
    val title: String,
    val criteria: List<String>,
    val stages: List<ScenarioStage>,
    val instructions: String,
)

/** Описывает один этап сценарной тренировки. */
@Serializable
data class ScenarioStage(val id: String, val goal: String, val exitRule: String)

/** Сообщает о нарушении безопасного формата сценарного Markdown. */
class ScenarioValidationException(message: String) : IllegalArgumentException(message)

/** Указывает источник сценария, закреплённого за тренировкой. */
@Serializable
enum class ScenarioSource { PRESET, UPLOADED }

/** Хранит нормализованный неизменяемый сценарий в aggregate тренировочной сессии. */
@Serializable
data class ScenarioSnapshot(val source: ScenarioSource, val definition: ScenarioDefinition)

/** Представляет необработанный выбор preset либо одноразового Markdown-сценария. */
data class ScenarioSelection(val presetId: String? = null, val markdown: String? = null)

/** Сообщает, что сценарий нельзя применить к новой тренировке. */
class ScenarioSelectionException(message: String) : IllegalArgumentException(message)
