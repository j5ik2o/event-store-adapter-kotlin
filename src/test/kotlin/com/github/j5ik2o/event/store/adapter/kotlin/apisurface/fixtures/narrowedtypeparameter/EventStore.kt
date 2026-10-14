package com.github.j5ik2o.event.store.adapter.kotlin.apisurface.fixtures.narrowedtypeparameter

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult

interface EventStore<P : Comparable<P>, A> {
    fun persistEvent(event: EventEnvelope<P>)

    fun persistEventAndSnapshot(
        event: EventEnvelope<P>,
        snapshot: SnapshotEnvelope<A>,
    )

    fun getLatestSnapshotById(aggregateId: AggregateId): SnapshotReadResult<A>?

    fun getEventsByIdSinceSeqNr(
        aggregateId: AggregateId,
        seqNr: Long,
    ): List<EventEnvelope<P>>
}
