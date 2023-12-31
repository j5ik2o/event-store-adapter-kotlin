package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.java.Aggregate
import com.github.j5ik2o.event.store.adapter.java.AggregateId
import com.github.j5ik2o.event.store.adapter.java.Event
import com.github.j5ik2o.event.store.adapter.java.EventSerializer
import com.github.j5ik2o.event.store.adapter.java.KeyResolver
import com.github.j5ik2o.event.store.adapter.java.SnapshotSerializer
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import com.github.j5ik2o.event.store.adapter.java.internal.EventStoreAsyncForDynamoDB as JavaEventStoreAsyncForDynamoDB

suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCoroutine { cont: Continuation<T> ->
        whenComplete { result, exception ->
            if (exception == null) {
                cont.resume(result)
            } else {
                cont.resumeWithException(exception)
            }
        }
    }

class EventStoreAsyncForDynamoDB<AID : AggregateId, A : Aggregate<A, AID>, E : Event<AID>>(
    private val underlying: JavaEventStoreAsyncForDynamoDB<AID, A, E>,
) : EventStoreAsync<AID, A, E> {

    override fun withKeepSnapshotCount(keepSnapshotCount: Long): EventStoreAsyncForDynamoDB<AID, A, E> {
        val updated = underlying.withKeepSnapshotCount(keepSnapshotCount)
        return EventStoreAsyncForDynamoDB(updated)
    }

    override fun withDeleteTtl(deleteTtl: Duration): EventStoreAsyncForDynamoDB<AID, A, E> {
        val updated = underlying.withDeleteTtl(deleteTtl.toJavaDuration())
        return EventStoreAsyncForDynamoDB(updated)
    }

    override fun withSnapshotSerializer(snapshotSerializer: SnapshotSerializer<AID, A>): EventStoreAsyncForDynamoDB<AID, A, E> {
        val updated = underlying.withSnapshotSerializer(snapshotSerializer)
        return EventStoreAsyncForDynamoDB(updated)
    }

    override fun withEventSerializer(eventSerializer: EventSerializer<AID, E>): EventStoreAsyncForDynamoDB<AID, A, E> {
        val updated = underlying.withEventSerializer(eventSerializer)
        return EventStoreAsyncForDynamoDB(updated)
    }

    override fun withKeyResolver(keyResolver: KeyResolver<AID>): EventStoreAsyncForDynamoDB<AID, A, E> {
        val updated = underlying.withKeyResolver(keyResolver)
        return EventStoreAsyncForDynamoDB(updated)
    }

    override suspend fun getLatestSnapshotById(
        clazz: Class<A>,
        aggregateId: AID,
    ): A? = coroutineScope {
        underlying.getLatestSnapshotById(clazz, aggregateId).await().getOrNull()
    }

    override suspend fun getEventsByIdSinceSequenceNumber(
        clazz: Class<E>,
        aggregateId: AID,
        sequenceNumber: Long,
    ): List<E> = coroutineScope {
        underlying.getEventsByIdSinceSequenceNumber(clazz, aggregateId, sequenceNumber).await()
    }

    override suspend fun persistEvent(event: E, version: Long): Unit = coroutineScope {
        underlying.persistEvent(event, version).await()
    }

    override suspend fun persistEventAndSnapshot(
        event: E,
        aggregate: A,
    ): Unit = coroutineScope {
        underlying.persistEventAndSnapshot(event, aggregate).await()
    }
}
