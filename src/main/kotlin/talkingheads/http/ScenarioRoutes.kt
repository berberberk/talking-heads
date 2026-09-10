package talkingheads.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject
import talkingheads.model.ErrorResponse
import talkingheads.model.ScenarioStageDto
import talkingheads.model.ScenarioSummaryDto
import talkingheads.model.ScenarioValidationRequest
import talkingheads.scenario.ScenarioCatalog
import talkingheads.scenario.ScenarioDefinition
import talkingheads.scenario.ScenarioResolver
import talkingheads.scenario.ScenarioSelection
import talkingheads.scenario.ScenarioSelectionException

/** Регистрирует read-only API каталога и проверку одноразового Markdown-сценария. */
fun Application.configureScenarioRoutes() {
    val catalog by inject<ScenarioCatalog>()
    val resolver by inject<ScenarioResolver>()
    routing {
        get("/api/scenarios") { call.respond(catalog.all().map(ScenarioDefinition::toSummary)) }
        post("/api/scenarios/validate") {
            try {
                val markdown = call.receive<ScenarioValidationRequest>().markdown
                val snapshot = requireNotNull(resolver.resolve(ScenarioSelection(markdown = markdown)))
                call.respond(snapshot.definition.toSummary())
            } catch (exception: ScenarioSelectionException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(exception.message ?: "invalid scenario"))
            }
        }
    }
}

/** Убирает внутренние инструкции методиста из публичного ответа каталога. */
private fun ScenarioDefinition.toSummary() = ScenarioSummaryDto(
    id = id,
    version = version,
    title = title,
    criteria = criteria,
    stages = stages.map { ScenarioStageDto(it.id, it.goal) },
)
