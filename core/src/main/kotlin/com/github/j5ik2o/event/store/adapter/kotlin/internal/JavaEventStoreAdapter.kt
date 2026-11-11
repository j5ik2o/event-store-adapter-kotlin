package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult
import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import kotlin.jvm.optionals.getOrNull
import com.github.j5ik2o.event.store.adapter.java.core.EventStore as JavaEventStore

internal class JavaEventStoreAdapter<P, A>(
    private val underlying: JavaEventStore<P, A>,
) : EventStore<P, A> {
    override fun persistEvent(event: EventEnvelope<P>) = underlying.persistEvent(event)

    override fun persistEventAndSnapshot(
        event: EventEnvelope<P>,
        snapshot: SnapshotEnvelope<A>,
    ) = underlying.persistEventAndSnapshot(event, snapshot)

    override fun getLatestSnapshotById(aggregateId: AggregateId): SnapshotReadResult<A>? =
        underlying.getLatestSnapshotById(aggregateId).getOrNull()

    override fun getEventsByIdSinceSeqNr(
        aggregateId: AggregateId,
        seqNr: Long,
    ): List<EventEnvelope<P>> = underlying.getEventsByIdSinceSeqNr(aggregateId, seqNr)
}
