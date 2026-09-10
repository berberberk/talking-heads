package talkingheads.service

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import talkingheads.config.AppConfig

class ElevenLabsException(message: String) : RuntimeException(message)

class ElevenLabsStreamingTtsClient(
    private val httpClient: HttpClient,
    private val config: AppConfig,
) : StreamingTtsClient {
    private val logger = LoggerFactory.getLogger(ElevenLabsStreamingTtsClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override fun synthesize(textDeltas: Flow<String>): Flow<TtsAudioFrame> = channelFlow {
        val apiKey = requireNotNull(config.elevenLabsApiKey) { "ELEVENLABS_API_KEY is not configured" }
        val voiceId = requireNotNull(config.elevenLabsVoiceId) { "ELEVENLABS_VOICE_ID is not configured" }
        val url = "wss://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream-input" +
            "?model_id=${config.elevenLabsModel}&output_format=pcm_16000&sync_alignment=true"
        var sentCharacters = 0
        var pcmBytes = 0L
        var firstAudioLogged = false
        val startedAt = System.nanoTime()

        val chunks = Channel<String>(Channel.BUFFERED)
        val producer = launch {
            try {
                val buffer = WordSafeTextBuffer(config.streamMinChars)
                textDeltas.collect { delta ->
                    buffer.append(delta).forEach { chunks.send(it) }
                }
                buffer.finish().takeIf(String::isNotEmpty)?.let { chunks.send(it) }
            } finally {
                chunks.close()
            }
        }

        try {
            // ElevenLabs закрывает idle WebSocket до первой пригодной части ответа Gemini.
            // Подключаемся только после того, как word-safe буфер собрал текст для синтеза.
            val firstChunk = chunks.receiveCatching().getOrNull() ?: return@channelFlow
            httpClient.webSocket(urlString = url) {
                send(Frame.Text(json.encodeToString(JsonObject.serializer(), initialMessage(apiKey))))
                sentCharacters += firstChunk.length
                send(Frame.Text(json.encodeToString(JsonObject.serializer(), textMessage(firstChunk, false))))
                val sender = launch {
                    for (chunk in chunks) {
                        sentCharacters += chunk.length
                        send(Frame.Text(json.encodeToString(JsonObject.serializer(), textMessage(chunk, false))))
                    }
                    send(Frame.Text(json.encodeToString(JsonObject.serializer(), textMessage("", true))))
                }
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val payload = parseAudio(frame.readText())
                    if (payload.pcm.isNotEmpty()) {
                        if (!firstAudioLogged) {
                            firstAudioLogged = true
                            logger.info("tts_first_audio durationMs={}", (System.nanoTime() - startedAt) / 1_000_000)
                        }
                        pcmBytes += payload.pcm.size
                        this@channelFlow.send(payload)
                    }
                    if (payload.isFinal) break
                }
                sender.join()
                outgoing.close()
            }
        } catch (exception: ElevenLabsException) {
            throw exception
        } catch (_: Exception) {
            throw ElevenLabsException("ElevenLabs streaming TTS failed")
        } finally {
            producer.cancel()
            logger.info(
                "tts_stream_completed durationMs={} characters={} pcmBytes={} firstAudio={}",
                (System.nanoTime() - startedAt) / 1_000_000,
                sentCharacters,
                pcmBytes,
                firstAudioLogged,
            )
        }
    }.buffer(capacity = 1)

    private fun initialMessage(apiKey: String): JsonObject = buildJsonObject {
        put("text", JsonPrimitive(" "))
        put("xi_api_key", JsonPrimitive(apiKey))
        put("generation_config", buildJsonObject {
            put("chunk_length_schedule", buildJsonArray {
                add(JsonPrimitive(50))
                add(JsonPrimitive(80))
                add(JsonPrimitive(120))
            })
        })
    }

    private fun textMessage(text: String, flush: Boolean): JsonObject = buildJsonObject {
        put("text", JsonPrimitive(text))
        if (flush) put("flush", JsonPrimitive(true))
    }

    internal fun parseAudio(rawMessage: String): TtsAudioFrame = try {
        val objectMessage = json.parseToJsonElement(rawMessage).jsonObject
        val providerError = (objectMessage["error"] as? JsonPrimitive)?.contentOrNull
        if (!providerError.isNullOrBlank()) {
            throw ElevenLabsException("ElevenLabs TTS error: $providerError")
        }
        val encoded = (objectMessage["audio"] as? JsonPrimitive)?.contentOrNull
        val pcm = encoded?.let { Base64.getDecoder().decode(it) } ?: ByteArray(0)
        val isFinal = (objectMessage["is_final"] as? JsonPrimitive)?.booleanOrNull
            ?: (objectMessage["isFinal"] as? JsonPrimitive)?.booleanOrNull
            ?: false
        TtsAudioFrame(pcm, parseAlignment(objectMessage["alignment"] as? JsonObject), isFinal)
    } catch (exception: ElevenLabsException) {
        throw exception
    } catch (_: Exception) {
        throw ElevenLabsException("ElevenLabs returned an invalid audio frame")
    }

    private fun parseAlignment(rawAlignment: JsonObject?): TtsAlignment? {
        val chars = rawAlignment?.get("chars")?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return null
        val starts = rawAlignment["char_start_times_ms"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: return null
        val durations = rawAlignment["char_durations_ms"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: return null
        return TtsAlignment(chars, starts, durations).takeIf {
            it.chars.isNotEmpty() && it.chars.size == it.charStartTimesMs.size && it.chars.size == it.charDurationsMs.size
        }
    }
}
