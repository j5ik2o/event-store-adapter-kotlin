package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.Aggregate
import com.github.j5ik2o.event.store.adapter.java.AggregateAndVersion
import com.github.j5ik2o.event.store.adapter.java.AggregateId
import com.github.j5ik2o.event.store.adapter.java.Event

interface EventStoreAsync<AID : AggregateId, A : Aggregate<AID>, E : Event<AID>> {

    suspend fun getLatestSnapshotById(
        clazz: Class<A>, aggregateId: AID
    ): AggregateAndVersion<AID, A>?

    suspend fun getEventsByIdSinceSequenceNumber(
        clazz: Class<E>, aggregateId: AID, sequenceNumber: Long
    ): List<E>

    suspend fun persistEvent(event: E, version: Long)

    suspend fun persistEventAndSnapshot(
        event: E,
        aggregate: A
    )

}