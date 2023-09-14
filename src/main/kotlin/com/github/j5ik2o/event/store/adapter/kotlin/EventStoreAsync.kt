package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.Aggregate
import com.github.j5ik2o.event.store.adapter.java.AggregateId
import com.github.j5ik2o.event.store.adapter.java.Event
import com.github.j5ik2o.event.store.adapter.kotlin.internal.EventStoreAsyncForDynamoDB
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import com.github.j5ik2o.event.store.adapter.java.internal.EventStoreAsyncForDynamoDB as JavaEventStoreAsyncForDynamoDB

interface EventStoreAsync<AID : AggregateId, A : Aggregate<AID>, E : Event<AID>> : EventStoreOptions<EventStoreAsync<AID, A, E>, AID, A, E> {

    companion object {
        fun <AID : AggregateId, A : Aggregate<AID>, E : Event<AID>> ofDynamoDB(underlying: JavaEventStoreAsyncForDynamoDB<AID, A, E>): EventStoreAsyncForDynamoDB<AID, A, E> {
            return EventStoreAsyncForDynamoDB(underlying)
        }

        fun <AID : AggregateId, A : Aggregate<AID>, E : Event<AID>> ofDynamoDB(
            dynamoDbAsyncClient: DynamoDbAsyncClient,
            journalTableName: String,
            snapshotTableName: String,
            journalAidIndexName: String,
            snapshotAidIndexName: String,
            shardCount: Long,
        ): EventStoreAsyncForDynamoDB<AID, A, E> {
            return ofDynamoDB(
                JavaEventStoreAsyncForDynamoDB.create(
                    dynamoDbAsyncClient,
                    journalTableName,
                    snapshotTableName,
                    journalAidIndexName,
                    snapshotAidIndexName,
                    shardCount,
                ),
            )
        }
    }

    suspend fun getLatestSnapshotById(
        clazz: Class<A>,
        aggregateId: AID,
    ): Pair<A, Long>?

    suspend fun getEventsByIdSinceSequenceNumber(
        clazz: Class<E>,
        aggregateId: AID,
        sequenceNumber: Long,
    ): List<E>

    suspend fun persistEvent(event: E, version: Long)

    suspend fun persistEventAndSnapshot(
        event: E,
        aggregate: A,
    )
}
