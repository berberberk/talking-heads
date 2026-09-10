package talkingheads.scenario

/** Загружает встроенные сценарии из classpath один раз при старте приложения. */
class ScenarioCatalog(private val parser: ScenarioParser = ScenarioParser()) {
    private val scenarios = PRESETS.map { path -> parser.parse(load(path)) }
        .also { definitions -> if (definitions.map(ScenarioDefinition::id).toSet().size != definitions.size) throw ScenarioValidationException("Встроенные сценарии не должны иметь одинаковые идентификаторы") }
        .associateBy(ScenarioDefinition::id)

    /** Возвращает preset-сценарии в стабильном порядке для UI. */
    fun all(): List<ScenarioDefinition> = scenarios.values.sortedBy(ScenarioDefinition::title)

    /** Находит preset по его публичному идентификатору. */
    fun find(id: String): ScenarioDefinition? = scenarios[id]

    private fun load(path: String): String = ScenarioCatalog::class.java.classLoader.getResourceAsStream(path)
        ?.bufferedReader()?.use { it.readText() }?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Ресурс сценария отсутствует или пуст: $path")

    private companion object {
        val PRESETS = listOf("scenarios/sales-discovery.md", "scenarios/sales-objection.md", "scenarios/structured-interview.md", "scenarios/policy-knowledge.md", "scenarios/manager-feedback.md")
    }
}

/** Преобразует выбор клиента в неизменяемый snapshot для одной сессии. */
class ScenarioResolver(private val catalog: ScenarioCatalog, private val parser: ScenarioParser = ScenarioParser()) {
    /** Возвращает null для свободного диалога или валидированный snapshot выбранного сценария. */
    fun resolve(selection: ScenarioSelection?): ScenarioSnapshot? {
        if (selection == null) return null
        val presetId = selection.presetId?.trim()?.takeIf { it.isNotEmpty() }
        val markdown = selection.markdown?.takeIf { it.isNotBlank() }
        if ((presetId == null) == (markdown == null)) throw ScenarioSelectionException("Выберите ровно один preset-сценарий или Markdown-файл")
        return if (presetId != null) ScenarioSnapshot(ScenarioSource.PRESET, catalog.find(presetId) ?: throw ScenarioSelectionException("Указанный preset-сценарий недоступен"))
        else ScenarioSnapshot(ScenarioSource.UPLOADED, try { parser.parse(requireNotNull(markdown)) } catch (exception: ScenarioValidationException) { throw ScenarioSelectionException(exception.message ?: "Markdown-сценарий не прошёл проверку") })
    }
}
