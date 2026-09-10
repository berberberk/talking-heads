package talkingheads.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import talkingheads.config.AppConfig
import talkingheads.config.StorageBackend
import talkingheads.llm.GeminiLlmClient
import talkingheads.llm.LlmClient
import talkingheads.repository.DatabaseFactory
import talkingheads.repository.FileTrainingRepository
import talkingheads.repository.PostgresTrainingRepository
import talkingheads.repository.TrainingRepository
import talkingheads.service.ElevenLabsStreamingTtsClient
import talkingheads.service.ElevenLabsScribeTokenClient
import talkingheads.service.ReportService
import talkingheads.service.SimliSessionTokenClient
import talkingheads.service.StreamingTtsClient
import talkingheads.service.TrainingSessionManager
import talkingheads.scenario.ScenarioCatalog
import talkingheads.scenario.ScenarioPromptProvider
import talkingheads.scenario.ScenarioResolver
import javax.sql.DataSource

fun appModule(config: AppConfig) = module {
    single { config }
    single {
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            classDiscriminator = "type"
        }
    }
    single {
        HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 90_000
                socketTimeoutMillis = 90_000
            }
            install(WebSockets)
        }
    }

    if (config.storageBackend == StorageBackend.POSTGRES) {
        single<DataSource> { DatabaseFactory.create(get()) }
        single<TrainingRepository> { PostgresTrainingRepository(get(), get()) }
    } else {
        single<TrainingRepository> { FileTrainingRepository(get<AppConfig>().dataDir, get()) }
    }

    single<LlmClient> {
        GeminiLlmClient(
            httpClient = get(),
            json = get(),
            apiKey = get<AppConfig>().geminiApiKey,
            model = get<AppConfig>().geminiModel,
            fallbackModels = get<AppConfig>().geminiFallbackModels,
            thinkingLevel = get<AppConfig>().geminiThinkingLevel,
            evaluationThinkingLevel = get<AppConfig>().geminiEvaluationThinkingLevel,
            firstDeltaTimeoutMillis = get<AppConfig>().geminiFirstDeltaTimeoutMillis,
        )
    }
    single<StreamingTtsClient> { ElevenLabsStreamingTtsClient(get(), get()) }
    single { ElevenLabsScribeTokenClient(get(), get()) }
    single { SimliSessionTokenClient(get(), get()) }
    single(createdAtStart = true) { ScenarioCatalog() }
    single { ScenarioResolver(get()) }
    single { ScenarioPromptProvider() }
    single { ReportService(get(), get()) }
    single {
        TrainingSessionManager(
            repository = get(),
            llmClient = get(),
            reportService = get(),
            appConfig = get(),
            scenarioResolver = get(),
            scenarioPromptProvider = get(),
            coroutineContext = Dispatchers.IO,
        )
    }
}
