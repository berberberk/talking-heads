package talkingheads.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.ktor.ext.inject
import talkingheads.model.ChatReportDto
import talkingheads.model.ChatRequest
import talkingheads.model.ChatResponse
import talkingheads.model.ErrorResponse
import talkingheads.model.HistoryMessageDto
import talkingheads.model.HistoryResponse
import talkingheads.model.MessageRole
import talkingheads.model.ServerEvent
import talkingheads.model.TrainingReport
import talkingheads.service.TrainingSessionManager

/** Compatibility API from the llm-service branch, backed by the integrated session manager. */
fun Application.configureChatCompatibilityRoutes() {
    val manager by inject<TrainingSessionManager>()

    routing {
        route("/api/chat") {
            post {
                val request = call.receive<ChatRequest>()
                val message = request.message.trim()
                if (message.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("message must not be empty"))
                    return@post
                }

                val session = if (request.sessionId == null) {
                    manager.createSession(request.scenario?.toDomain())
                } else {
                    if (request.scenario != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("scenario can only be selected for a new session"))
                        return@post
                    }
                    if (!isUuid(request.sessionId)) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse("sessionId must be a UUID"))
                        return@post
                    }
                    val existing = manager.getSession(request.sessionId)
                    if (existing == null) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("chat session not found"))
                        return@post
                    }
                    existing
                }

                val generationId = AtomicLong(Long.MIN_VALUE)
                val terminal = CompletableDeferred<ServerEvent>()
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    manager.events(session.id).collect { event ->
                        val active = generationId.get()
                        if (active == Long.MIN_VALUE) return@collect
                        when (event) {
                            is ServerEvent.AssistantCompleted -> if (event.generationId == active) terminal.complete(event)
                            is ServerEvent.GenerationCancelled -> if (event.generationId == active) terminal.complete(event)
                            is ServerEvent.Error -> if (event.generationId == active) terminal.complete(event)
                            else -> Unit
                        }
                    }
                }

                var reserved: Long? = null
                try {
                    reserved = manager.submitNextUserMessage(session.id, message) { generationId.set(it) }
                    val event = withTimeout(120_000) { terminal.await() }
                    when (event) {
                        is ServerEvent.AssistantCompleted -> call.respond(
                            ChatResponse(
                                sessionId = session.id,
                                assistantMessage = event.text,
                                generationId = requireNotNull(reserved),
                            ),
                        )
                        is ServerEvent.GenerationCancelled -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse("generation was interrupted"),
                        )
                        is ServerEvent.Error -> call.respond(
                            HttpStatusCode.BadGateway,
                            ErrorResponse(event.message),
                        )
                        else -> call.respond(HttpStatusCode.InternalServerError, ErrorResponse("unexpected generation state"))
                    }
                } catch (_: TimeoutCancellationException) {
                    reserved?.let { manager.interrupt(session.id, it) }
                    call.respond(HttpStatusCode.GatewayTimeout, ErrorResponse("generation timed out"))
                } catch (e: CancellationException) {
                    reserved?.let { id -> try { manager.interrupt(session.id, id) } catch (_: Exception) { } }
                    throw e
                } finally {
                    collector.cancelAndJoin()
                }
            }

            get("/{sessionId}/history") {
                val sessionId = call.parameters["sessionId"].orEmpty()
                if (!isUuid(sessionId)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("sessionId must be a UUID"))
                    return@get
                }
                val session = manager.getSession(sessionId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("chat session not found"))
                call.respond(
                    HistoryResponse(
                        sessionId = session.id,
                        messages = session.messages.map { message ->
                            HistoryMessageDto(
                                role = if (message.role == MessageRole.USER) "USER" else "ASSISTANT",
                                content = message.text,
                                createdAt = message.createdAt,
                                generationId = message.generationId,
                                interrupted = message.interrupted,
                            )
                        },
                    ),
                )
            }

            post("/{sessionId}/finish") {
                val sessionId = call.parameters["sessionId"].orEmpty()
                if (!isUuid(sessionId)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("sessionId must be a UUID"))
                    return@post
                }
                val session = manager.finish(sessionId)
                val report = session.report
                if (report == null) {
                    call.respond(
                        HttpStatusCode.Accepted,
                        ErrorResponse(session.reportError ?: "Training is finished; the report is being generated."),
                    )
                    return@post
                }
                call.respond(report.toChatDto(sessionId))
            }

            get("/{sessionId}/report") {
                val sessionId = call.parameters["sessionId"].orEmpty()
                if (!isUuid(sessionId)) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("sessionId must be a UUID"))
                    return@get
                }
                val session = manager.getSession(sessionId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("chat session not found"))
                val report = session.report
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("training report not found"))
                call.respond(report.toChatDto(sessionId))
            }
        }
    }
}

private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

private fun TrainingReport.toChatDto(sessionId: String): ChatReportDto = ChatReportDto(
    sessionId = sessionId,
    generatedAt = generatedAt,
    overallScore = overallScore,
    summary = summary,
    recommendations = recommendations,
    criteria = criteria,
    evaluationStatus = evaluationStatus,
)
