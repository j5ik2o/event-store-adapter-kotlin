package com.github.j5ik2o.event.store.adapter.kotlin.apisurface.fixtures.missingpersistandsnapshot

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult

interface EventStore<P, A> {
    fun persistEvent(event: EventEnvelope<P>)

    fun getLatestSnapshotById(aggregateId: AggregateId): SnapshotReadResult<A>?

    fun getEventsByIdSinceSeqNr(
        aggregateId: AggregateId,
        seqNr: Long,
    ): List<EventEnvelope<P>>
}
