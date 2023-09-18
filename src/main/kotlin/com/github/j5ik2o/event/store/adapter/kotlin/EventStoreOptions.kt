package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.Aggregate
import com.github.j5ik2o.event.store.adapter.java.AggregateId
import com.github.j5ik2o.event.store.adapter.java.Event
import com.github.j5ik2o.event.store.adapter.java.EventSerializer
import com.github.j5ik2o.event.store.adapter.java.KeyResolver
import com.github.j5ik2o.event.store.adapter.java.SnapshotSerializer
import kotlin.time.Duration

interface EventStoreOptions<This : EventStoreOptions<This, AID, A, E>, AID : AggregateId, A : Aggregate<A, AID>, E : Event<AID>> {

    fun withKeepSnapshotCount(keepSnapshotCount: Long): This

    fun withDeleteTtl(deleteTtl: Duration): This

    fun withKeyResolver(keyResolver: KeyResolver<AID>): This

    fun withEventSerializer(eventSerializer: EventSerializer<AID, E>): This

    fun withSnapshotSerializer(snapshotSerializer: SnapshotSerializer<AID, A>): This
}
