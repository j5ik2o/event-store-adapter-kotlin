package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.java.Aggregate
import com.github.j5ik2o.event.store.adapter.java.AggregateId
import com.github.j5ik2o.event.store.adapter.java.Event
import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import kotlin.jvm.optionals.getOrNull
import com.github.j5ik2o.event.store.adapter.java.internal.EventStoreForDynamoDB as JavaEventStoreForDynamoDB

class EventStoreForDynamoDB<AID : AggregateId, A : Aggregate<AID>, E : Event<AID>>
(private val underling: JavaEventStoreForDynamoDB<AID, A, E>) : EventStore<AID, A, E> {

    override fun getLatestSnapshotById(clazz: Class<A>, aggregateId: AID): Pair<A, Long>? {
        return underling.getLatestSnapshotById(clazz, aggregateId).map {
            Pair(it.aggregate, it.version)
        }.getOrNull()
    }

    override fun getEventsByIdSinceSequenceNumber(clazz: Class<E>, aggregateId: AID, sequenceNumber: Long): List<E> {
        return underling.getEventsByIdSinceSequenceNumber(clazz, aggregateId, sequenceNumber)
    }

    override fun persistEvent(event: E, version: Long) {
        underling.persistEvent(event, version)
    }

    override fun persistEventAndSnapshot(event: E, aggregate: A) {
        underling.persistEventAndSnapshot(event, aggregate)
    }
}
