package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import kotlin.jvm.optionals.getOrNull

class UserAccountRepositoryAsync(
    private val eventStore: EventStoreAsync<UserAccountEvent, UserAccount>,
) {
    suspend fun storeEvent(event: EventEnvelope<UserAccountEvent>) = eventStore.persistEvent(event)

    suspend fun storeEventAndSnapshot(
        event: EventEnvelope<UserAccountEvent>,
        snapshot: SnapshotEnvelope<UserAccount>,
    ) = eventStore.persistEventAndSnapshot(event, snapshot)

    suspend fun findById(id: AggregateId): UserAccount? {
        val read = eventStore.getLatestSnapshotById(id) ?: return null
        val snapshot = read.snapshot().getOrNull()
        val events = eventStore.getEventsByIdSinceSeqNr(id, (snapshot?.seqNr() ?: 0L) + 1)
        val replayedSeqNr = events.lastOrNull()?.seqNr() ?: snapshot?.seqNr() ?: 0L
        check(replayedSeqNr >= read.headSeqNr()) { "Replay has not reached the observed head" }
        return UserAccount.replay(events, snapshot?.aggregate())
    }
}
