package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableEventStore
import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableStorage
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig

object BigtableEventStores {
    fun <P, A> of(
        storage: BigtableStorage,
        config: EventStoreConfig<P, A>,
    ): EventStore<P, A> = EventStore.fromJava(BigtableEventStore.create(storage, config))

    fun <P, A> ofAsync(
        storage: BigtableStorage,
        config: EventStoreConfig<P, A>,
    ): EventStoreAsync<P, A> = EventStoreAsync.fromJava(BigtableEventStore.createAsync(storage, config))
}
