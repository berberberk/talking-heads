package talkingheads

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.websocket.WebSockets
import kotlinx.serialization.json.Json
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import talkingheads.config.AppConfig
import talkingheads.di.appModule
import talkingheads.http.configureRoutes
import talkingheads.model.ErrorResponse
import talkingheads.service.SessionNotFoundException
import talkingheads.service.EvaluationException
import talkingheads.service.ScribeTokenException
/** Запускает HTTP-сервер корпоративного тренажёра с настройками окружения. */
fun main() {
    val config = AppConfig.fromEnvironment()
    embeddedServer(
        factory = Netty,
        host = config.host,
        port = config.port,
        module = { module(config) },
    ).start(wait = true)
}

/** Подключает DI, HTTP-плагины и маршруты Kotlin runtime. */
fun Application.module(config: AppConfig = AppConfig.fromEnvironment()) {
    install(Koin) {
        slf4jLogger()
        modules(appModule(config))
    }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = true
                classDiscriminator = "type"
            },
        )
    }

    install(WebSockets) {
        pingPeriodMillis = 20_000
        timeoutMillis = 30_000
        maxFrameSize = 1024L * 1024L
        masking = false
    }

    install(CallLogging)

    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowCredentials = false

        if (config.allowedOrigins.isEmpty()) {
            anyHost()
        } else {
            config.allowedOrigins.forEach { origin ->
                val noScheme = origin.substringAfter("://")
                val scheme = origin.substringBefore("://", missingDelimiterValue = "http")
                allowHost(noScheme, schemes = listOf(scheme))
            }
        }
    }

    install(StatusPages) {
        exception<SessionNotFoundException> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "Not found"))
        }
        exception<EvaluationException> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.BadGateway, ErrorResponse(cause.message ?: "evaluation failed"))
        }
        exception<ScribeTokenException> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.BadGateway, ErrorResponse(cause.message ?: "Speech recognition is unavailable"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "Bad request"))
        }
        exception<IllegalStateException> { call, cause ->
            call.respond(io.ktor.http.HttpStatusCode.Conflict, ErrorResponse(cause.message ?: "Conflict"))
        }
        exception<Throwable> { call, cause ->
            this@module.environment.log.error("Unhandled error", cause)
            call.respond(io.ktor.http.HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    configureRoutes()
}
