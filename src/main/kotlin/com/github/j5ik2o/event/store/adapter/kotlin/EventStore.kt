package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.Aggregate
import com.github.j5ik2o.event.store.adapter.java.AggregateId
import com.github.j5ik2o.event.store.adapter.java.Event
import com.github.j5ik2o.event.store.adapter.kotlin.internal.EventStoreForDynamoDB
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import com.github.j5ik2o.event.store.adapter.java.internal.EventStoreForDynamoDB as JavaEventStoreForDynamoDB

/**
 * Represents an event store. / イベントストアを表します。
 *
 * @param AID Aggregate ID / 集約ID
 * @param A Aggregate / 集約
 * @param E Event / イベント
 */
interface EventStore<AID : AggregateId, A : Aggregate<A, AID>, E : Event<AID>> : EventStoreOptions<EventStore<AID, A, E>, AID, A, E> {

    companion object {
        /**
         * Create an instance of [EventStoreForDynamoDB]. / [EventStoreForDynamoDB]のインスタンスを作成します。
         *
         * @param AID Aggregate ID / 集約ID
         * @param A Aggregate / 集約
         * @param E Event / イベント
         * @param underlying Underlying instance / 下位のインスタンス
         */
        fun <AID : AggregateId, A : Aggregate<A, AID>, E : Event<AID>> ofDynamoDB(underlying: JavaEventStoreForDynamoDB<AID, A, E>): EventStoreForDynamoDB<AID, A, E> {
            return EventStoreForDynamoDB(underlying)
        }

        /**
         * Create an instance of [EventStoreForDynamoDB]. / [EventStoreForDynamoDB]のインスタンスを作成します。
         *
         * @param AID Aggregate ID / 集約ID
         * @param A Aggregate / 集約
         * @param E Event / イベント
         * @param dynamoDbClient DynamoDB client / DynamoDBクライアント
         * @param journalTableName Journal table name / ジャーナルテーブル名
         * @param snapshotTableName Snapshot table name / スナップショットテーブル名
         * @param journalAidIndexName Journal AID index name / ジャーナルAIDインデックス名
         * @param snapshotAidIndexName Snapshot AID index name / スナップショットAIDインデックス名
         * @param shardCount Shard count / シャード数
         * @return [EventStoreForDynamoDB] instance / [EventStoreForDynamoDB]のインスタンス
         */
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

    /**
     * Gets the latest snapshot by the aggregate id. / 集約IDによる最新のスナップショットを取得します。
     *
     * @param clazz Aggregate class / 集約クラス
     * @param aggregateId Aggregate ID / 集約ID
     * @return Aggregate instance / 集約のインスタンス
     * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreReadException if an error occurred during reading from the event store / イベントストアからの読み込み中にエラーが発生した場合
     * @throws com.github.j5ik2o.event.store.adapter.java.DeserializationException if an error occurred during serialization / シリアライズ中にエラーが発生した場合
     */
    fun getLatestSnapshotById(
        clazz: Class<A>,
        aggregateId: AID,
    ): A?

    /**
     * Gets the events by the aggregate id and since the sequence number. / IDとシーケンス番号以降のイベントを取得します。
     *
     * @param clazz Event class / イベントクラス
     * @param aggregateId Aggregate ID / 集約ID
     * @param sequenceNumber Sequence number / シーケンス番号
     * @return List of events / イベントのリスト
     * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreReadException if an error occurred during reading from the event store / イベントストアからの読み込み中にエラーが発生した場合
     * @throws com.github.j5ik2o.event.store.adapter.java.DeserializationException if an error occurred during serialization / シリアライズ中にエラーが発生した場合
     */
    fun getEventsByIdSinceSequenceNumber(
        clazz: Class<E>,
        aggregateId: AID,
        sequenceNumber: Long,
    ): List<E>

    /**
     * Persists an event only. / イベントのみを永続化します。
     *
     * @param event Event / イベント
     * @param version Version / バージョン
     * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreWriteException if an error occurred during writing to the event store / イベントストアへの書き込み中にエラーが発生した場合
     * @throws com.github.j5ik2o.event.store.adapter.java.SerializationException if an error occurred during serialization / シリアライズ中にエラーが発生した場合
     * @throws com.github.j5ik2o.event.store.adapter.java.TransactionException if an error occurred during transaction / トランザクション中にエラーが発生した場合
     */
    fun persistEvent(event: E, version: Long)

    /**
     * Persists an event and a snapshot. / イベントとスナップショットを永続化します。
     *
     * @param event Event / イベント
     * @param aggregate Aggregate / 集約
     * @throws com.github.j5ik2o.event.store.adapter.java.EventStoreWriteException if an error occurred during writing to the event store / イベントストアへの書き込み中にエラーが発生した場合
     * @throws com.github.j5ik2o.event.store.adapter.java.SerializationException if an error occurred during serialization / シリアライズ中にエラーが発生した場合
     * @throws com.github.j5ik2o.event.store.adapter.java.TransactionException if an error occurred during transaction / トランザクション中にエラーが発生した場合
     */
    fun persistEventAndSnapshot(
        event: E,
        aggregate: A,
    )
}
