package org.example.spring

import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.thread.Threading
import org.springframework.context.SmartLifecycle
import org.springframework.core.env.Environment
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.NANOSECONDS
import kotlin.time.Duration.Companion.seconds

@Service
class Notifier(
    private val brokerMessagingTemplate: SimpMessagingTemplate,
) {
    private val safeWhere = Regex("""^[\w.-]+$""")

    fun notify(where: String, what: String) {
        if (safeWhere matches where) {
            brokerMessagingTemplate.convertAndSend("/topic/echo/$where", what)
        } else {
            throw IllegalArgumentException("where.unsafe")
        }
    }
}


@Service
class Indexer(
    private val persistedRepository: PersistedRepository,
    private val indexedRepository: IndexedRepository,
    environment: Environment,
) : SmartLifecycle {
    private val queue = LinkedBlockingQueue<UUID>()
    private val thread = (if (Threading.VIRTUAL.isActive(environment)) Thread.ofVirtual() else Thread.ofPlatform())
        .name(this::class.simpleName).unstarted(this::process)
    private val logger = LoggerFactory.getLogger(javaClass)

    fun put(id: UUID) {
        queue.offer(id)
    }

    private fun process() {
        logger.atInfo().log("Started")
        while (!thread.isInterrupted) cycle()
        logger.atInfo().log("Stopped")
    }

    private fun cycle() {
        val batch = mutableListOf<UUID>()
        val waitTill = System.nanoTime() + 10.seconds.inWholeNanoseconds
        try {
            repeat(10) {
                val id = queue.poll(waitTill - System.nanoTime(), NANOSECONDS) ?: return@repeat
                batch.add(id)
            }
        } catch (_: InterruptedException) {
            // Exit the loop
            thread.interrupt()
            // but first write what's left.
        }
        // Start waiting for an element again
        if (batch.isEmpty()) return

        persistedRepository
            .findByIdIn(batch)
            .map(Persisted::forIndex)
            .let(indexedRepository::saveAll)
    }

    override fun stop(callback: Runnable) {
        stop()
        thread.join()
        callback.run()
    }

    override fun stop() = thread.interrupt()

    override fun start() = thread.start()

    override fun isRunning(): Boolean = thread.isAlive
}

@Service
class Creator(
    private val transactionTemplate: TransactionTemplate,
    private val entityManager: EntityManager,
    private val indexer: Indexer,
) {
    fun create(value: Persisted) {
        transactionTemplate.execute {
            entityManager.persist(value)
        }
        indexer.put(value.id)
    }
}

@Service
class Matcher(
    private val indexedRepository: IndexedRepository,
) {
    fun match(query: String) = indexedRepository
        .findByPayload(query, PageRequest.of(0, 10, Sort.Direction.DESC, "_score"))
}