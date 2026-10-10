package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbEventStore
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryEventStore
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import com.github.j5ik2o.event.store.adapter.kotlin.internal.JavaAsyncEventStoreAdapter
import kotlinx.coroutines.future.await
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient

/** Suspending operations using cancellable coroutine await. / 取消可能なコルーチン待機を使う中断関数。 */
interface EventStoreAsync<P, A> {
    companion object {
        fun <P, A> ofMemory(
            storage: MemoryStorage,
            config: EventStoreConfig<P, A>,
        ): EventStoreAsync<P, A> = fromJava(MemoryEventStore.createAsync(storage, config))

        /** Awaits configuration validation; the caller owns the client. / 設定照合を待ち、クライアントは呼出側が所有します。 */
        suspend fun <P, A> ofDynamoDB(
            client: DynamoDbAsyncClient,
            tables: DynamoDbTableConfig,
            config: EventStoreConfig<P, A>,
        ): EventStoreAsync<P, A> = fromJava(DynamoDbEventStore.createAsync(client, tables, config).await())

        fun <P, A> fromJava(underlying: AsyncEventStore<P, A>): EventStoreAsync<P, A> = JavaAsyncEventStoreAdapter(underlying)
    }

    suspend fun persistEvent(event: EventEnvelope<P>)

    suspend fun persistEventAndSnapshot(
        event: EventEnvelope<P>,
        snapshot: SnapshotEnvelope<A>,
    )

    /** Null means no head; snapshot absence is preserved inside the result. / nullはヘッド不存在。封筒の不存在は結果内で保持します。 */
    suspend fun getLatestSnapshotById(aggregateId: AggregateId): SnapshotReadResult<A>?

    /** Returns all matching envelopes, including the starting sequence. / 開始番号を含む全対象封筒を返します。 */
    suspend fun getEventsByIdSinceSeqNr(
        aggregateId: AggregateId,
        seqNr: Long,
    ): List<EventEnvelope<P>>
}
