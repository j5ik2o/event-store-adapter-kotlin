package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbEventStore
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

object DynamoDbEventStores {
    fun <P, A> of(
        client: DynamoDbClient,
        tables: DynamoDbTableConfig,
        config: EventStoreConfig<P, A>,
    ): EventStore<P, A> = EventStore.fromJava(DynamoDbEventStore.create(client, tables, config))

    suspend fun <P, A> ofAsync(
        client: DynamoDbAsyncClient,
        tables: DynamoDbTableConfig,
        config: EventStoreConfig<P, A>,
    ): EventStoreAsync<P, A> = EventStoreAsync.fromJava(DynamoDbEventStore.createAsync(client, tables, config).await())
}
