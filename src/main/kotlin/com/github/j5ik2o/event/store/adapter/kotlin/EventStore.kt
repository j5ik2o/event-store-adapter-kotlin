package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.Aggregate
import com.github.j5ik2o.event.store.adapter.java.AggregateId
import com.github.j5ik2o.event.store.adapter.java.Event
import com.github.j5ik2o.event.store.adapter.kotlin.internal.EventStoreForDynamoDB
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import com.github.j5ik2o.event.store.adapter.java.internal.EventStoreForDynamoDB as JavaEventStoreForDynamoDB

interface EventStore<AID : AggregateId, A : Aggregate<A, AID>, E : Event<AID>> : EventStoreOptions<EventStore<AID, A, E>, AID, A, E> {

    companion object {
        fun <AID : AggregateId, A : Aggregate<A, AID>, E : Event<AID>> ofDynamoDB(underlying: JavaEventStoreForDynamoDB<AID, A, E>): EventStoreForDynamoDB<AID, A, E> {
            return EventStoreForDynamoDB(underlying)
        }
        fun <AID : AggregateId, A : Aggregate<A, AID>, E : Event<AID>> ofDynamoDB(
            dynamoDbClient: DynamoDbClient,
            journalTableName: String,
            snapshotTableName: String,
            journalAidIndexName: String,
            snapshotAidIndexName: String,
            shardCount: Long,
        ): EventStoreForDynamoDB<AID, A, E> {
            return ofDynamoDB(
                JavaEventStoreForDynamoDB.create(
                    dynamoDbClient,
                    journalTableName,
                    snapshotTableName,
                    journalAidIndexName,
                    snapshotAidIndexName,
                    shardCount,
                ),
            )
        }
    }

    fun getLatestSnapshotById(
        clazz: Class<A>,
        aggregateId: AID,
    ): A?

    fun getEventsByIdSinceSequenceNumber(
        clazz: Class<E>,
        aggregateId: AID,
        sequenceNumber: Long,
    ): List<E>

    fun persistEvent(event: E, version: Long)

    fun persistEventAndSnapshot(
        event: E,
        aggregate: A,
    )
}
