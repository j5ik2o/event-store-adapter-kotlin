package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerEventStore
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerStorage

object SpannerEventStores {
    fun <P, A> of(
        storage: SpannerStorage,
        config: EventStoreConfig<P, A>,
    ): EventStore<P, A> = EventStore.fromJava(SpannerEventStore.create(storage, config))

    fun <P, A> ofAsync(
        storage: SpannerStorage,
        config: EventStoreConfig<P, A>,
    ): EventStoreAsync<P, A> = EventStoreAsync.fromJava(SpannerEventStore.createAsync(storage, config))
}
