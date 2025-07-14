package org.example.spring

import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

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
) {
    private val queue = ConcurrentLinkedQueue<UUID>()
    fun put(id: UUID) {
        queue.offer(id)
    }

    @Scheduled(fixedRateString = "PT10S")
    fun write() {
        while (true) {
            val batch = mutableListOf<UUID>()
            for (counter in 0..10) {
                val id = queue.poll()
                if (id == null) break
                batch.add(id)
            }
            if (batch.isEmpty()) return
            persistedRepository
                .findByIdIn(batch)
                .map(Persisted::forIndex)
                .let(indexedRepository::saveAll)
        }
    }
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