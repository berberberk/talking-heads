package talkingheads.llm

import kotlinx.coroutines.flow.Flow
import talkingheads.model.TrainingMessage

interface LlmClient {
    fun streamReply(
        history: List<TrainingMessage>,
        systemPrompt: String,
    ): Flow<String>

    suspend fun generateText(
        prompt: String,
        systemPrompt: String,
        jsonMode: Boolean = false,
    ): String
}
