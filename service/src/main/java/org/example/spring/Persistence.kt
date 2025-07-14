package org.example.spring

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Pageable
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Query
import org.springframework.data.repository.Repository
import java.util.*


@jakarta.persistence.Entity
class Persisted(
    @get:jakarta.persistence.Id
    var id: UUID,
    var payload: String,
)

interface PersistedRepository : Repository<Persisted, UUID> {
    fun findByIdIn(ids: Collection<UUID>): Set<Persisted>
}

@Document(indexName = "persisted")
class IndexedPersisted(
    @get:Id
    val id: UUID,
    val payload: String,
)
fun Persisted.forIndex() = IndexedPersisted(id = id, payload = payload)

interface IndexedRepository : Repository<IndexedPersisted, UUID> {
    @Query("{\"match\": {\"payload\": {\"query\": \"?0\"}}}")
    fun findByPayload(payload: String, pageable: Pageable): List<IndexedPersisted>

    fun saveAll(objs: Iterable<IndexedPersisted>): Iterable<IndexedPersisted>
}