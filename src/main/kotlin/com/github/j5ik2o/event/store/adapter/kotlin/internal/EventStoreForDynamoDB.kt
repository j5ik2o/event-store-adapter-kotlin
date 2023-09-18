package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.java.Aggregate
import com.github.j5ik2o.event.store.adapter.java.AggregateId
import com.github.j5ik2o.event.store.adapter.java.Event
import com.github.j5ik2o.event.store.adapter.java.EventSerializer
import com.github.j5ik2o.event.store.adapter.java.KeyResolver
import com.github.j5ik2o.event.store.adapter.java.SnapshotSerializer
import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import com.github.j5ik2o.event.store.adapter.java.internal.EventStoreForDynamoDB as JavaEventStoreForDynamoDB

class EventStoreForDynamoDB<AID : AggregateId, A : Aggregate<A, AID>, E : Event<AID>>
(private val underlying: JavaEventStoreForDynamoDB<AID, A, E>) : EventStore<AID, A, E> {

    override fun withKeepSnapshotCount(keepSnapshotCount: Long): EventStoreForDynamoDB<AID, A, E> {
        val updated = underlying.withKeepSnapshotCount(keepSnapshotCount)
        return EventStoreForDynamoDB(updated)
    }

    override fun withDeleteTtl(deleteTtl: Duration): EventStoreForDynamoDB<AID, A, E> {
        val updated = underlying.withDeleteTtl(deleteTtl.toJavaDuration())
        return EventStoreForDynamoDB(updated)
    }

    override fun withSnapshotSerializer(snapshotSerializer: SnapshotSerializer<AID, A>): EventStoreForDynamoDB<AID, A, E> {
        val updated = underlying.withSnapshotSerializer(snapshotSerializer)
        return EventStoreForDynamoDB(updated)
    }

    override fun withEventSerializer(eventSerializer: EventSerializer<AID, E>): EventStoreForDynamoDB<AID, A, E> {
        val updated = underlying.withEventSerializer(eventSerializer)
        return EventStoreForDynamoDB(updated)
    }

    override fun withKeyResolver(keyResolver: KeyResolver<AID>): EventStoreForDynamoDB<AID, A, E> {
        val updated = underlying.withKeyResolver(keyResolver)
        return EventStoreForDynamoDB(updated)
    }

    override fun getLatestSnapshotById(clazz: Class<A>, aggregateId: AID): A? {
        return underlying.getLatestSnapshotById(clazz, aggregateId).getOrNull()
    }

    override fun getEventsByIdSinceSequenceNumber(clazz: Class<E>, aggregateId: AID, sequenceNumber: Long): List<E> {
        return underlying.getEventsByIdSinceSequenceNumber(clazz, aggregateId, sequenceNumber)
    }

    override fun persistEvent(event: E, version: Long) {
        underlying.persistEvent(event, version)
    }

    override fun persistEventAndSnapshot(event: E, aggregate: A) {
        underlying.persistEventAndSnapshot(event, aggregate)
    }
}
