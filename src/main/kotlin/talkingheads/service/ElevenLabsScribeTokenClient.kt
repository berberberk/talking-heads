package talkingheads.service

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import talkingheads.config.AppConfig

/** Обозначает безопасно обработанную ошибку upstream-сервиса распознавания речи. */
class ScribeTokenException(message: String) : RuntimeException(message)

/** Получает одноразовый Scribe-токен, не раскрывая ElevenLabs API key браузеру. */
class ElevenLabsScribeTokenClient(
    private val httpClient: HttpClient,
    private val config: AppConfig,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Запрашивает новый токен для каждого browser-подключения Scribe Realtime. */
    suspend fun createToken(): String {
        val apiKey = config.elevenLabsApiKey
            ?: throw ScribeTokenException("Speech recognition is not configured")
        val response = try {
            httpClient.post(SCRIBE_TOKEN_URL) {
                header("xi-api-key", apiKey)
            }
        } catch (exception: Exception) {
            throw ScribeTokenException("Speech recognition service is unavailable")
        }
        if (!response.status.isSuccess()) {
            throw ScribeTokenException("Speech recognition service is unavailable")
        }
        val token = runCatching {
            json.decodeFromString<ScribeTokenPayload>(response.bodyAsText()).token.trim()
        }.getOrElse {
            throw ScribeTokenException("Speech recognition service returned an invalid token")
        }
        if (token.isBlank()) {
            throw ScribeTokenException("Speech recognition service returned an invalid token")
        }
        return token
    }

    private companion object {
        const val SCRIBE_TOKEN_URL = "https://api.elevenlabs.io/v1/single-use-token/realtime_scribe"
    }
}

/** Описывает минимальный безопасный ответ token endpoint ElevenLabs. */
@Serializable
private data class ScribeTokenPayload(val token: String = "")
