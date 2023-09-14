package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync

class UserAccountRepositoryAsync(private val eventStore: EventStoreAsync<UserAccountId, UserAccount, UserAccountEvent>) {

    suspend fun storeEvent(event: UserAccountEvent, version: Long) {
        eventStore.persistEvent(event, version)
    }

    suspend fun storeEventAndSnapshot(event: UserAccountEvent, aggregate: UserAccount) {
        eventStore.persistEventAndSnapshot(event, aggregate)
    }

    suspend fun findById(id: UserAccountId): UserAccount? {
        val (userAccount, version) = eventStore.getLatestSnapshotById(UserAccount::class.java, id) ?: return null
        val events = eventStore.getEventsByIdSinceSequenceNumber(UserAccountEvent::class.java, id, userAccount.sequenceNumber + 1)
        return UserAccount.replay(events, userAccount, version)
    }
}
