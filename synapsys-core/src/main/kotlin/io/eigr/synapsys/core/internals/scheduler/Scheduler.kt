package io.eigr.synapsys.core.internals.scheduler

import io.eigr.synapsys.core.internals.loggerFor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * The `Scheduler` class is responsible for managing the execution of `ActorExecutor` instances
 * in a concurrent and distributed manner across multiple worker threads.
 * It implements a **work-stealing** strategy to balance the workload between workers.
 *
 * ## Overview
 * - The scheduler distributes actor execution across multiple worker queues.
 * - Each worker continuously processes actor messages in a loop.
 * - If a worker has no actors to process, it attempts to steal work from other workers.
 * - Enforces a **reduction limit** (`maxReductions`) per actor execution to prevent monopolization.
 * - Supports actor suspension and resumption to efficiently handle high concurrency.
 *
 * ## Features
 * - **Work Queue**: Uses `ConcurrentLinkedQueue` to store actors for each worker.
 * - **Work Stealing**: Workers can steal actors from other workers if their queue is empty.
 * - **Message Processing**: Actors execute a limited number of messages per activation.
 * - **Dynamic Scheduling**: Actors are re-queued after execution to ensure fairness.
 * - **Logging**: Provides detailed logging for scheduling operations.
 *
 * @param maxReductions The maximum number of messages an actor can process before being suspended.
 * @param numWorkers The number of worker threads for actor execution (default: CPU core count).
 * @param processTimeThresholdMs The time threshold in milliseconds beyond which additional reductions are added per exceeded interval (default: 10ms).
 * @param timePenaltyFactor The multiplier applied per exceeded time interval to calculate additional reductions (default: 2).
 */
class Scheduler(
    private val maxReductions: Int,
    private val numWorkers: Int = Runtime.getRuntime().availableProcessors(),
    private val processTimeThresholdMs: Long = 10,
    private val timePenaltyFactor: Int = 2
) {
    private val log = loggerFor(this::class.java)

    /** A list of worker queues, each holding actor executors assigned to a specific worker. */
    internal val actorExecutorQueues = List(numWorkers) { ConcurrentLinkedQueue<ActorExecutor<*>>() }

    /** A coroutine scope used for launching worker loops and processing tasks asynchronously. */
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Initializes the scheduler, starting worker threads to process actor messages.
     * Each worker continuously processes actors in its queue, or steals work when idle.
     */
    init {
        log.info("[Scheduler] Starting Scheduler with {} workers", numWorkers)
        repeat(numWorkers) { workerId ->
            scope.launch {
                workerLoop(workerId)
            }
        }
    }

    /**
     * Enqueues an `ActorExecutor` for execution by assigning it to a randomly selected worker queue.
     * The actor execution is resumed before being added to the queue.
     *
     * @param actorExecutor The actor executor to be scheduled for execution.
     */
    fun enqueue(actorExecutor: ActorExecutor<*>) {
        // ThreadLocalRandom avoid unnecessary object creations
        actorExecutor.resumeExecution()
        actorExecutorQueues[ThreadLocalRandom.current().nextInt(numWorkers)].offer(actorExecutor)
    }

    /**
     * Removes an actor from the scheduler based on its ID.
     *
     * @param actorId The ID of the actor to remove.
     * @return `true` if the actor was found and removed, `false` otherwise.
     */
    fun removeActor(actorId: String): Boolean {
        // This imperative code reduces memory allocations
        var removed = false
        for (queue in actorExecutorQueues) {
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().actor.id == actorId) {
                    iterator.remove()
                    removed = true
                }
            }
        }
        if (removed) log.info("[Scheduler] Removed actor {} from scheduler", actorId)
        else if (log.isWarnEnabled) log.warn("[Scheduler] Actor {} not found in scheduler", actorId)
        return removed
    }

    /**
     * Clears all worker queues, removing all scheduled actor executors.
     */
    fun cleanAllWorkerQueues() {
        actorExecutorQueues.forEach { it.clear() }
        log.info("[Scheduler] All worker queues cleared")
    }

    /**
     * @internal
     * The worker loop that continuously processes actors assigned to a worker.
     * If no actors are available, the worker attempts to steal work from other workers.
     *
     * @param workerId The ID of the worker executing this loop.
     */
    private suspend fun workerLoop(workerId: Int) {
        val queue = actorExecutorQueues[workerId]
        while (true) {
            val actorExecutor = queue.poll() ?: stealWork(workerId)
            actorExecutor?.let {
                scope.launch { processActor(actorExecutor) }
            } ?: delay(10) // Small delay to prevent busy-waiting.
        }
    }

    /**
     * @internal
     * Processes an actor executor, handling its messages until the reduction limit is reached.
     * If an actor still has pending messages after reaching the reduction limit, it is re-enqueued.
     *
     * @param actorExecutor The actor executor to process.
     */
    private suspend fun processActor(actorExecutor: ActorExecutor<*>) {
        var reductions = 0
        if (log.isTraceEnabled) log.trace("Process {}. IsActive {}. Has Message {}", actorExecutor.actor.id, actorExecutor.isActive, actorExecutor.hasMessages())
        actorExecutor.resumeExecution()

        while (isProcessable(actorExecutor, reductions)) {
            val message = actorExecutor.dequeueMessage() ?: break

            log.trace("[Scheduler] Previous reduction counts {}", reductions)
            val startTime = System.nanoTime()
            actorExecutor.processMessage(message)
            val durationNs = System.nanoTime() - startTime

            if (TimeUnit.NANOSECONDS.toMillis(durationNs) <= processTimeThresholdMs) {
                reductions++
            } else {
                reductions += 1 + ((durationNs / (processTimeThresholdMs * 1_000_000L)) * timePenaltyFactor).toInt()
                if (log.isDebugEnabled) log.debug("[Scheduler] Added {} reductions for message (took {} ns)", reductions, durationNs)
            }
        }

        if (isNotProcessable(actorExecutor, reductions)) {
            if (log.isTraceEnabled) log.trace(
                "[Scheduler] Has messages: {}. Reductions: {}. Max reductions: {}",
                actorExecutor.hasMessages(),
                reductions,
                maxReductions
            )
            if (log.isTraceEnabled) log.trace(
                "[Scheduler] Suspending actor {} on Thread: {}",
                actorExecutor.actor.id,
                Thread.currentThread()
            )

            scope.launch {
                actorExecutor.suspendExecution()
            }

            enqueue(actorExecutor)
            if (log.isTraceEnabled) log.trace(
                "[Scheduler] Enqueued actor {} from Thread: {}",
                actorExecutor.actor.id,
                Thread.currentThread()
            )
        }
    }

    /**
     * @internal
     * Determines if an actor executor can continue processing messages.
     *
     * @param actorExecutor The actor executor to check.
     * @param reductions The number of messages processed so far.
     * @return `true` if the actor can continue processing, `false` otherwise.
     */
    private fun isProcessable(actorExecutor: ActorExecutor<*>, reductions: Int): Boolean =
        actorExecutor.isActive && actorExecutor.hasMessages() && reductions < maxReductions

    /**
     * @internal
     * Determines if an actor executor should be suspended after processing.
     *
     * @param actorExecutor The actor executor to check.
     * @param reductions The number of messages processed so far.
     * @return `true` if the actor should be suspended, `false` otherwise.
     */
    private fun isNotProcessable(actorExecutor: ActorExecutor<*>, reductions: Int): Boolean =
        !actorExecutor.hasMessages() || reductions >= maxReductions

    /**
     * @internal
     * Attempts to steal work from other worker queues if the current worker is idle.
     *
     * @param workerId The ID of the worker attempting to steal work.
     * @return The stolen `ActorExecutor` if available, otherwise `null`.
     */
    internal fun stealWork(workerId: Int): ActorExecutor<*>? {
        // This imperative code reduces memory allocations
        for (i in actorExecutorQueues.indices) {
            if (i != workerId) {
                actorExecutorQueues[i].poll()?.let { return it }
            }
        }
        return null
    }
}

