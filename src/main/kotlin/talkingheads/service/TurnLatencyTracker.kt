package talkingheads.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

data class TurnLatencySnapshot(
    val turnId: String,
    val outcome: String,
    val elapsedMillis: Map<String, Long>,
)

class TurnLatencyTracker(
    private val turnId: String,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val logger = LoggerFactory.getLogger(TurnLatencyTracker::class.java)
    private val startedAt = nanoTime()
    private val stages = ConcurrentHashMap<String, Long>()
    private val finished = AtomicBoolean(false)

    fun mark(stage: String): Long {
        require(stage.isNotBlank()) { "stage must not be blank" }
        val elapsed = ((nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)
        val previous = stages.putIfAbsent(stage, elapsed)
        if (previous == null) {
            logger.info("turn_latency_stage turnId={} stage={} elapsedMs={}", turnId, stage, elapsed)
            return elapsed
        }
        return previous
    }

    fun finish(outcome: String): TurnLatencySnapshot {
        val snapshot = TurnLatencySnapshot(turnId, outcome, stages.toSortedMap())
        if (finished.compareAndSet(false, true)) {
            logger.info("turn_latency_summary turnId={} outcome={} stages={}", turnId, outcome, snapshot.elapsedMillis)
        }
        return snapshot
    }
}
