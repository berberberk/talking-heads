package talkingheads.scenario

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Разбирает и строго валидирует JSON front matter сценарного Markdown. */
class ScenarioParser {
    private val json = Json { ignoreUnknownKeys = false }

    /** Возвращает проверенный сценарий либо отклоняет некорректный Markdown до создания сессии. */
    fun parse(markdown: String): ScenarioDefinition {
        if (markdown.length > MAX_MARKDOWN_LENGTH) throw ScenarioValidationException("Сценарий превышает допустимый размер")
        val match = FRONT_MATTER.matchEntire(markdown)
            ?: throw ScenarioValidationException("В сценарии отсутствует корректный scenario-meta блок")
        val metadata = try {
            json.decodeFromString<ScenarioMetadata>(match.groupValues[1])
        } catch (exception: Exception) {
            throw ScenarioValidationException("Не удалось разобрать scenario-meta: ${exception.message}")
        }
        return ScenarioDefinition(
            id = metadata.id.trim(), version = metadata.version, title = metadata.title.trim(),
            criteria = metadata.criteria.map(String::trim),
            stages = metadata.stages.map { ScenarioStage(it.id.trim(), it.goal.trim(), it.exitRule.trim()) },
            instructions = match.groupValues[2].trim(),
        ).also(::validate)
    }

    /** Проверяет все поля, которые могут попасть в системную инструкцию LLM. */
    private fun validate(definition: ScenarioDefinition) {
        requireId(definition.id, "Идентификатор сценария")
        if (definition.version < 1) throw ScenarioValidationException("Версия сценария должна быть положительной")
        requireText(definition.title, "Название сценария", 3, 120)
        requireList(definition.criteria, "Критерии", 1, 6)
        requireUnique(definition.criteria, "Критерии")
        definition.criteria.forEach { requireText(it, "Критерий", 3, 120) }
        requireList(definition.stages, "Этапы", 2, 5)
        requireUnique(definition.stages.map(ScenarioStage::id), "Идентификаторы этапов")
        definition.stages.forEach { stage ->
            requireId(stage.id, "Идентификатор этапа")
            requireText(stage.goal, "Цель этапа", 3, 300)
            if (!EXIT_RULE.matches(stage.exitRule)) throw ScenarioValidationException("Некорректное правило выхода этапа: ${stage.exitRule}")
        }
        requireText(definition.instructions, "Инструкция сценария", 20, 6_000)
    }

    private fun requireId(value: String, field: String) {
        if (!ID.matches(value)) throw ScenarioValidationException("$field имеет некорректный формат")
    }
    private fun requireText(value: String, field: String, min: Int, max: Int) {
        if (value.length !in min..max) throw ScenarioValidationException("$field должно содержать от $min до $max символов")
    }
    private fun <T> requireList(values: List<T>, field: String, min: Int, max: Int) {
        if (values.size !in min..max) throw ScenarioValidationException("$field должны содержать от $min до $max элементов")
    }
    private fun requireUnique(values: List<String>, field: String) {
        if (values.map { it.lowercase() }.toSet().size != values.size) throw ScenarioValidationException("$field не должны повторяться")
    }

    @Serializable private data class ScenarioMetadata(val id: String, val version: Int, val title: String, val criteria: List<String>, val stages: List<StageMetadata>)
    @Serializable private data class StageMetadata(val id: String, val goal: String, val exitRule: String)
    private companion object {
        const val MAX_MARKDOWN_LENGTH = 32_000
        val ID = Regex("[a-z][a-z0-9-]{1,62}")
        val EXIT_RULE = Regex("[a-z][a-z0-9_]{1,62}")
        val FRONT_MATTER = Regex("""\A\s*<!--\s*scenario-meta\s*\r?\n(.*?)\r?\n-->\s*(.*)\z""", setOf(RegexOption.DOT_MATCHES_ALL))
    }
}
