package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbEventStore
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryEventStore
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import com.github.j5ik2o.event.store.adapter.kotlin.internal.JavaEventStoreAdapter
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import com.github.j5ik2o.event.store.adapter.java.core.EventStore as JavaEventStore

/** Synchronous operations using Java envelopes. / Java封筒を使う同期操作。 */
interface EventStore<P, A> {
    companion object {
        /** Shares the supplied storage. / 渡された保存先を共有します。 */
        fun <P, A> ofMemory(
            storage: MemoryStorage,
            config: EventStoreConfig<P, A>,
        ): EventStore<P, A> = fromJava(MemoryEventStore.create(storage, config))

        /** Validates three provisioned tables; the caller owns the client. / 外部の3表を照合し、クライアントは呼出側が所有します。 */
        fun <P, A> ofDynamoDB(
            client: DynamoDbClient,
            tables: DynamoDbTableConfig,
            config: EventStoreConfig<P, A>,
        ): EventStore<P, A> = fromJava(DynamoDbEventStore.create(client, tables, config))

        /** Wraps the new Java public interface. / 新しいJava公開インターフェイスを包みます。 */
        fun <P, A> fromJava(underlying: JavaEventStore<P, A>): EventStore<P, A> = JavaEventStoreAdapter(underlying)
    }

    fun persistEvent(event: EventEnvelope<P>)

    fun persistEventAndSnapshot(
        event: EventEnvelope<P>,
        snapshot: SnapshotEnvelope<A>,
    )

    /** Null means no head; a present result can have no snapshot. / nullはヘッド不存在。結果の中の封筒も不存在を表せます。 */
    fun getLatestSnapshotById(aggregateId: AggregateId): SnapshotReadResult<A>?

    /** Returns every envelope with seq_nr >= seqNr in ascending order. / 指定番号以上の全封筒を昇順で返します。 */
    fun getEventsByIdSinceSeqNr(
        aggregateId: AggregateId,
        seqNr: Long,
    ): List<EventEnvelope<P>>
}
