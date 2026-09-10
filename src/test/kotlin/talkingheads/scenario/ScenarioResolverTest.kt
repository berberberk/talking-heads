package talkingheads.scenario

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Проверяет преобразование выбора сценария в закреплённый snapshot до создания сессии. */
class ScenarioResolverTest {
    private val resolver = ScenarioResolver(ScenarioCatalog())

    /** Оставляет свободный диалог без сценарного snapshot. */
    @Test
    fun `resolve accepts absent selection`() {
        assertNull(resolver.resolve(null))
    }

    /** Находит только предзагруженный сценарий и сохраняет его происхождение. */
    @Test
    fun `resolve creates preset snapshot`() {
        val snapshot = requireNotNull(resolver.resolve(ScenarioSelection(presetId = "sales-discovery")))

        assertEquals(ScenarioSource.PRESET, snapshot.source)
        assertEquals("sales-discovery", snapshot.definition.id)
    }

    /** Валидирует custom Markdown, не добавляя его в общий каталог. */
    @Test
    fun `resolve creates uploaded snapshot`() {
        val snapshot = requireNotNull(resolver.resolve(ScenarioSelection(markdown = customMarkdown())))

        assertEquals(ScenarioSource.UPLOADED, snapshot.source)
        assertEquals("custom-training", snapshot.definition.id)
        assertNull(ScenarioCatalog().find("custom-training"))
    }

    /** Отклоняет неизвестный preset до создания тренировочной сессии. */
    @Test
    fun `resolve rejects unknown preset`() {
        assertFailsWith<ScenarioSelectionException> { resolver.resolve(ScenarioSelection(presetId = "unknown-preset")) }
    }

    /** Не принимает одновременно preset и пользовательский Markdown. */
    @Test
    fun `resolve rejects ambiguous selection`() {
        assertFailsWith<ScenarioSelectionException> { resolver.resolve(ScenarioSelection("sales-discovery", customMarkdown())) }
    }

    private fun customMarkdown() = """
        <!-- scenario-meta
        {"id":"custom-training","version":1,"title":"Пользовательская тренировка","criteria":["Понятность ответа"],"stages":[{"id":"start","goal":"Начать разговор","exitRule":"continue"},{"id":"finish","goal":"Подвести итог","exitRule":"complete"}]}
        -->
        Проведи короткую тренировку и помоги сотруднику сформулировать уверенный ответ.
    """.trimIndent()
}
