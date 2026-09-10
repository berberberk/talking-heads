package talkingheads.repository

import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import talkingheads.model.TrainingSession

/**
 * PostgreSQL persistence for the integrated session model.
 * The full aggregate is stored as JSONB so generation/interruption/report fields remain atomic.
 */
class PostgresTrainingRepository(
    private val dataSource: DataSource,
    private val json: Json,
) : TrainingRepository {
    override suspend fun create(session: TrainingSession): TrainingSession = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO integrated_training_sessions (id, payload, created_at, updated_at) VALUES (?::uuid, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, session.id)
                statement.setObject(2, jsonb(encode(session)))
                statement.setTimestamp(3, Timestamp.from(Instant.parse(session.createdAt)))
                statement.setTimestamp(4, Timestamp.from(Instant.parse(session.updatedAt)))
                statement.executeUpdate()
            }
        }
        session
    }

    override suspend fun get(sessionId: String): TrainingSession? {
        if (!isUuid(sessionId)) return null
        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection -> read(connection, sessionId, forUpdate = false) }
        }
    }

    override suspend fun save(session: TrainingSession): TrainingSession = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "UPDATE integrated_training_sessions SET payload = ?, updated_at = ? WHERE id = ?::uuid",
            ).use { statement ->
                statement.setObject(1, jsonb(encode(session)))
                statement.setTimestamp(2, Timestamp.from(Instant.parse(session.updatedAt)))
                statement.setString(3, session.id)
                check(statement.executeUpdate() == 1) { "Session ${session.id} not found" }
            }
        }
        session
    }

    override suspend fun update(
        sessionId: String,
        transform: (TrainingSession) -> TrainingSession,
    ): TrainingSession? {
        if (!isUuid(sessionId)) return null
        return withContext(Dispatchers.IO) {
            dataSource.connection.use connectionUse@{ connection ->
                connection.autoCommit = false
                try {
                    val current = read(connection, sessionId, forUpdate = true)
                    if (current == null) {
                        connection.rollback()
                        return@connectionUse null
                    }
                    val updated = transform(current)
                    connection.prepareStatement(
                        "UPDATE integrated_training_sessions SET payload = ?, updated_at = ? WHERE id = ?::uuid",
                    ).use { statement ->
                        statement.setObject(1, jsonb(encode(updated)))
                        statement.setTimestamp(2, Timestamp.from(Instant.parse(updated.updatedAt)))
                        statement.setString(3, sessionId)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    updated
                } catch (e: Exception) {
                    connection.rollback()
                    throw e
                } finally {
                    connection.autoCommit = true
                }
            }
        }
    }

    private fun read(connection: Connection, sessionId: String, forUpdate: Boolean): TrainingSession? {
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        return connection.prepareStatement(
            "SELECT payload FROM integrated_training_sessions WHERE id = ?::uuid$suffix",
        ).use statementUse@{ statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use resultUse@{ result ->
                if (!result.next()) return@resultUse null
                decode(result.getString("payload"))
            }
        }
    }

    private fun encode(session: TrainingSession): String =
        json.encodeToString(TrainingSession.serializer(), session)

    private fun decode(raw: String): TrainingSession =
        json.decodeFromString(TrainingSession.serializer(), raw)

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private fun jsonb(value: String): PGobject = PGobject().apply {
        type = "jsonb"
        this.value = value
    }
}
