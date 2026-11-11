package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteEventStore
import com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteStorage

object SqliteEventStores {
    fun <P, A> of(
        storage: SqliteStorage,
        config: EventStoreConfig<P, A>,
    ): EventStore<P, A> = EventStore.fromJava(SqliteEventStore.create(storage, config))

    fun <P, A> ofAsync(
        storage: SqliteStorage,
        config: EventStoreConfig<P, A>,
    ): EventStoreAsync<P, A> = EventStoreAsync.fromJava(SqliteEventStore.createAsync(storage, config))
}
