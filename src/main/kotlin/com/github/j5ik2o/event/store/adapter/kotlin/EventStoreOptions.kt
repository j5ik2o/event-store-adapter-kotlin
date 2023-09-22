package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.Aggregate
import com.github.j5ik2o.event.store.adapter.java.AggregateId
import com.github.j5ik2o.event.store.adapter.java.Event
import com.github.j5ik2o.event.store.adapter.java.EventSerializer
import com.github.j5ik2o.event.store.adapter.java.KeyResolver
import com.github.j5ik2o.event.store.adapter.java.SnapshotSerializer
import kotlin.time.Duration

interface EventStoreOptions<This : EventStoreOptions<This, AID, A, E>, AID : AggregateId, A : Aggregate<A, AID>, E : Event<AID>> {
    /**
     * Specifies the number of snapshots to keep. / スナップショットを保持する数を指定します。
     *
     * @param keepSnapshotCount the number of snapshots kept / スナップショットを保持する数
     * @return [This]
     */
    fun withKeepSnapshotCount(keepSnapshotCount: Long): This

    /**
     * Specifies the time until it is deleted by TTL. / TTLによって削除されるまでの時間を指定します。
     *
     * @param deleteTtl Time until it is deleted by TTL / TTLによって削除されるまでの時間
     * @return [This]
     */
    fun withDeleteTtl(deleteTtl: Duration): This

    /**
     * Specifies the key resolver. / キーリゾルバを指定します。
     *
     * @param keyResolver [KeyResolver] instance / [KeyResolver]のインスタンス
     * @return [This]
     */
    fun withKeyResolver(keyResolver: KeyResolver<AID>): This

    /**
     * Specifies the event serializer. / イベントシリアライザを指定します。
     *
     * @param eventSerializer [EventSerializer] instance / [EventSerializer]のインスタンス
     * @return [This]
     */
    fun withEventSerializer(eventSerializer: EventSerializer<AID, E>): This

    /**
     * Specifies the snapshot serializer. / スナップショットシリアライザを指定します。
     *
     * @param snapshotSerializer [SnapshotSerializer] instance / [SnapshotSerializer]のインスタンス
     * @return [This]
     */
    fun withSnapshotSerializer(snapshotSerializer: SnapshotSerializer<AID, A>): This
}
