package talkingheads.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import talkingheads.model.TrainingSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileTrainingRepository(
    private val directory: Path,
    private val json: Json,
) : TrainingRepository {
    private val locks = ConcurrentHashMap<String, Mutex>()

    init {
        Files.createDirectories(directory)
    }

    override suspend fun create(session: TrainingSession): TrainingSession {
        val path = pathFor(session.id)
        val lock = locks.computeIfAbsent(session.id) { Mutex() }
        return lock.withLock {
            require(!path.exists()) { "Session ${session.id} already exists" }
            writeAtomically(path, json.encodeToString(TrainingSession.serializer(), session))
            session
        }
    }

    override suspend fun get(sessionId: String): TrainingSession? {
        val path = pathFor(sessionId)
        if (!path.exists()) return null
        val lock = locks.computeIfAbsent(sessionId) { Mutex() }
        return lock.withLock {
            if (!path.exists()) return@withLock null
            json.decodeFromString(TrainingSession.serializer(), path.readText())
        }
    }

    override suspend fun save(session: TrainingSession): TrainingSession {
        val path = pathFor(session.id)
        val lock = locks.computeIfAbsent(session.id) { Mutex() }
        return lock.withLock {
            writeAtomically(path, json.encodeToString(TrainingSession.serializer(), session))
            session
        }
    }


    override suspend fun update(
        sessionId: String,
        transform: (TrainingSession) -> TrainingSession,
    ): TrainingSession? {
        val path = pathFor(sessionId)
        if (!path.exists()) return null
        val lock = locks.computeIfAbsent(sessionId) { Mutex() }
        return lock.withLock {
            if (!path.exists()) return@withLock null
            val current = json.decodeFromString(TrainingSession.serializer(), path.readText())
            val updated = transform(current)
            writeAtomically(path, json.encodeToString(TrainingSession.serializer(), updated))
            updated
        }
    }

    private fun pathFor(sessionId: String): Path = directory.resolve("$sessionId.json")

    private fun writeAtomically(target: Path, content: String) {
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        tmp.writeText(content)
        try {
            Files.move(
                tmp,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
