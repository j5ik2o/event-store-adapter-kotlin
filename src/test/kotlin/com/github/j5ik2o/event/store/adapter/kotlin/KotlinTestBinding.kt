package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import kotlinx.coroutines.runBlocking
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.github.j5ik2o.event.store.adapter.java.core.EventStore as JavaEventStore

/** Test-only facades let the preserved Java observers invoke real Kotlin entry points. */
object KotlinTestBinding {
    private val invocations = ConcurrentHashMap<String, AtomicLong>()

    private fun invoked(name: String) {
        invocations.computeIfAbsent(name) { AtomicLong() }.incrementAndGet()
    }

    @JvmStatic
    fun counts(): Map<String, Long> = invocations.mapValues { it.value.get() }.toSortedMap()

    @JvmStatic
    fun <P, A> memory(
        storage: MemoryStorage,
        config: EventStoreConfig<P, A>,
    ): JavaEventStore<P, A> {
        invoked("ofMemory")
        return syncFacade(EventStore.ofMemory(storage, config))
    }

    @JvmStatic
    fun <P, A> dynamo(
        client: DynamoDbClient,
        tables: DynamoDbTableConfig,
        config: EventStoreConfig<P, A>,
    ): JavaEventStore<P, A> {
        invoked("ofDynamoDB")
        return syncFacade(EventStore.ofDynamoDB(client, tables, config))
    }

    @JvmStatic
    fun <P, A> dynamoAsync(
        client: DynamoDbAsyncClient,
        tables: DynamoDbTableConfig,
        config: EventStoreConfig<P, A>,
    ): CompletableFuture<AsyncEventStore<P, A>> =
        CompletableFuture.supplyAsync {
            invoked("async.ofDynamoDB")
            runBlocking { asyncFacade(EventStoreAsync.ofDynamoDB(client, tables, config)) }
        }

    @JvmStatic
    fun <P, A> sync(underlying: JavaEventStore<P, A>): JavaEventStore<P, A> {
        invoked("fromJava")
        return syncFacade(EventStore.fromJava(underlying))
    }

    @JvmStatic
    fun <P, A> async(underlying: AsyncEventStore<P, A>): AsyncEventStore<P, A> {
        invoked("async.fromJava")
        return asyncFacade(EventStoreAsync.fromJava(underlying))
    }

    private fun <P, A> syncFacade(store: EventStore<P, A>): JavaEventStore<P, A> =
        object : JavaEventStore<P, A> {
            override fun persistEvent(event: EventEnvelope<P>) {
                invoked("persistEvent")
                store.persistEvent(event)
            }

            override fun persistEventAndSnapshot(
                event: EventEnvelope<P>,
                snapshot: SnapshotEnvelope<A>,
            ) {
                invoked("persistEventAndSnapshot")
                store.persistEventAndSnapshot(event, snapshot)
            }

            override fun getLatestSnapshotById(aggregateId: AggregateId): Optional<SnapshotReadResult<A>> {
                invoked("getLatestSnapshotById")
                return Optional.ofNullable(store.getLatestSnapshotById(aggregateId))
            }

            override fun getEventsByIdSinceSeqNr(
                aggregateId: AggregateId,
                seqNr: Long,
            ): List<EventEnvelope<P>> {
                invoked("getEventsByIdSinceSeqNr")
                return store.getEventsByIdSinceSeqNr(aggregateId, seqNr)
            }
        }

    private fun <P, A> asyncFacade(store: EventStoreAsync<P, A>): AsyncEventStore<P, A> =
        object : AsyncEventStore<P, A> {
            override fun persistEvent(event: EventEnvelope<P>): CompletableFuture<Void> =
                CompletableFuture.runAsync {
                    invoked("async.persistEvent")
                    runBlocking { store.persistEvent(event) }
                }

            override fun persistEventAndSnapshot(
                event: EventEnvelope<P>,
                snapshot: SnapshotEnvelope<A>,
            ): CompletableFuture<Void> =
                CompletableFuture.runAsync {
                    invoked("async.persistEventAndSnapshot")
                    runBlocking { store.persistEventAndSnapshot(event, snapshot) }
                }

            override fun getLatestSnapshotById(aggregateId: AggregateId): CompletableFuture<Optional<SnapshotReadResult<A>>> =
                CompletableFuture.supplyAsync {
                    invoked("async.getLatestSnapshotById")
                    runBlocking { Optional.ofNullable(store.getLatestSnapshotById(aggregateId)) }
                }

            override fun getEventsByIdSinceSeqNr(
                aggregateId: AggregateId,
                seqNr: Long,
            ): CompletableFuture<List<EventEnvelope<P>>> =
                CompletableFuture.supplyAsync {
                    invoked("async.getEventsByIdSinceSeqNr")
                    runBlocking { store.getEventsByIdSinceSeqNr(aggregateId, seqNr) }
                }
        }
}
