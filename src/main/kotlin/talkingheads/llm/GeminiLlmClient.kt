package talkingheads.llm

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import talkingheads.model.MessageRole
import talkingheads.model.TrainingMessage

class GeminiLlmClient(
    private val httpClient: HttpClient,
    private val json: Json,
    private val apiKey: String,
    private val model: String,
    private val fallbackModels: List<String> = emptyList(),
    private val thinkingLevel: String = "minimal",
    private val evaluationThinkingLevel: String = "medium",
    private val firstDeltaTimeoutMillis: Long = 3_000,
) : LlmClient {
    private val logger = LoggerFactory.getLogger(GeminiLlmClient::class.java)

    override fun streamReply(
        history: List<TrainingMessage>,
        systemPrompt: String,
    ): Flow<String> = streamGenerate(
        body = requestBody(
            history = history,
            systemPrompt = systemPrompt,
            jsonMode = false,
        ),
    )

    override suspend fun generateText(
        prompt: String,
        systemPrompt: String,
        jsonMode: Boolean,
    ): String {
        val syntheticHistory = listOf(
            TrainingMessage(
                role = MessageRole.USER,
                text = prompt,
                generationId = 0,
                createdAt = "",
            ),
        )
        return streamGenerate(
            requestBody(syntheticHistory, systemPrompt, jsonMode),
        ).toList().joinToString(separator = "")
    }

    private fun streamGenerate(body: JsonObject): Flow<String> = flow {
        val models = (listOf(model) + fallbackModels).distinct()
        var lastFailure: Throwable? = null

        for ((index, candidateModel) in models.withIndex()) {
            var emitted = false
            try {
                requestStream(candidateModel, body).collect { chunk ->
                    emitted = true
                    emit(chunk)
                }
                return@flow
            } catch (e: TimeoutCancellationException) {
                lastFailure = e
                if (index == models.lastIndex) throw e
                logger.warn(
                    "gemini_first_delta_timeout failedModel={} nextModel={} timeoutMs={}",
                    candidateModel,
                    models[index + 1],
                    firstDeltaTimeoutMillis,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastFailure = e
                // Never retry another model after text has already been emitted: it would duplicate spoken output.
                if (emitted || index == models.lastIndex) throw e
                logger.warn(
                    "gemini_model_fallback failedModel={} nextModel={} reason={}",
                    candidateModel,
                    models[index + 1],
                    e.message?.take(240),
                )
            }
        }
        throw lastFailure ?: IllegalStateException("Gemini generation failed")
    }

    /**
     * Запрашивает SSE и ограничивает ожидание именно первого текстового delta.
     *
     * После первого слова timeout больше не применяется: длинная, но уже начавшаяся
     * реплика не должна быть оборвана. Timeout до первого текста позволяет перейти
     * на fallback до того, как пользователь услышит затянувшуюся паузу.
     */
    private fun requestStream(candidateModel: String, body: JsonObject): Flow<String> = flow {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:streamGenerateContent?alt=sse"
        httpClient.preparePost(url) {
            header("x-goog-api-key", apiKey)
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(body.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val errorBody = runCatching { response.body<String>() }.getOrDefault("")
                error("Gemini API ${response.status.value}: $errorBody")
            }

            val channel = response.bodyAsChannel()
            val firstChunks = withTimeout(firstDeltaTimeoutMillis) {
                readNextTextChunks(channel)
            }
            check(firstChunks.isNotEmpty()) { "Gemini stream closed before the first text delta" }
            for (chunk in firstChunks) {
                emit(chunk)
            }

            while (!channel.isClosedForRead) {
                for (chunk in readNextTextChunks(channel)) {
                    emit(chunk)
                }
            }
        }
    }

    /** Читает SSE до следующего непустого текстового delta либо до закрытия канала. */
    private suspend fun readNextTextChunks(channel: io.ktor.utils.io.ByteReadChannel): List<String> {
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: return emptyList()
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val element = try {
                json.parseToJsonElement(payload)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            val chunks = extractText(element).filter(String::isNotEmpty)
            if (chunks.isNotEmpty()) return chunks
        }
        return emptyList()
    }

    private fun requestBody(
        history: List<TrainingMessage>,
        systemPrompt: String,
        jsonMode: Boolean,
    ): JsonObject = buildJsonObject {
        put("system_instruction", buildJsonObject {
            put("parts", buildJsonArray {
                add(buildJsonObject { put("text", systemPrompt) })
            })
        })

        put("contents", buildJsonArray {
            history.forEach { message ->
                add(buildJsonObject {
                    put("role", when (message.role) {
                        MessageRole.USER -> "user"
                        MessageRole.MODEL -> "model"
                    })
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            val suffix = if (message.interrupted && message.role == MessageRole.MODEL) {
                                "\n[Эта реплика была прервана пользователем.]"
                            } else {
                                ""
                            }
                            put("text", message.text + suffix)
                        })
                    })
                })
            }
        })

        put("generationConfig", buildJsonObject {
            put("temperature", JsonPrimitive(0.5))
            put("maxOutputTokens", JsonPrimitive(if (jsonMode) 800 else 200))
            put("thinkingConfig", buildJsonObject {
                put("thinkingLevel", if (jsonMode) evaluationThinkingLevel else thinkingLevel)
            })
            if (jsonMode) {
                put("responseMimeType", "application/json")
            }
        })
    }

    private fun extractText(root: JsonElement): List<String> {
        val candidates = root.asObject()["candidates"] as? JsonArray ?: return emptyList()
        return candidates.flatMap { candidate ->
            val content = candidate.asObject()["content"].asObject()
            val parts = content["parts"] as? JsonArray ?: return@flatMap emptyList()
            parts.mapNotNull { part ->
                val objectPart = part.asObject()
                if (objectPart["thought"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
                objectPart["text"]?.jsonPrimitive?.contentOrNull
            }
        }
    }

    private fun JsonElement?.asObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
}
