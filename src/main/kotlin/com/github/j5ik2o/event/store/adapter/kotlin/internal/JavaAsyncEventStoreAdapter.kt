package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import kotlinx.coroutines.future.await
import kotlin.jvm.optionals.getOrNull

internal class JavaAsyncEventStoreAdapter<P, A>(
    private val underlying: AsyncEventStore<P, A>,
) : EventStoreAsync<P, A> {
    override suspend fun persistEvent(event: EventEnvelope<P>) {
        underlying.persistEvent(event).await()
    }

    override suspend fun persistEventAndSnapshot(
        event: EventEnvelope<P>,
        snapshot: SnapshotEnvelope<A>,
    ) {
        underlying.persistEventAndSnapshot(event, snapshot).await()
    }

    override suspend fun getLatestSnapshotById(aggregateId: AggregateId): SnapshotReadResult<A>? =
        underlying.getLatestSnapshotById(aggregateId).await().getOrNull()

    override suspend fun getEventsByIdSinceSeqNr(
        aggregateId: AggregateId,
        seqNr: Long,
    ): List<EventEnvelope<P>> = underlying.getEventsByIdSinceSeqNr(aggregateId, seqNr).await()
}
