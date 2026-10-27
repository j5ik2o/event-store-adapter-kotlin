package com.github.j5ik2o.event.store.adapter.java.spanner

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailureListener
import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import com.github.j5ik2o.event.store.adapter.kotlin.storagetest.StorageBoundarySupport
import com.github.j5ik2o.event.store.adapter.kotlin.storagetest.StorageBoundarySupport.ThrowingListener
import com.github.j5ik2o.event.store.adapter.kotlin.storagetest.StorageBoundarySupport.config
import com.github.j5ik2o.event.store.adapter.kotlin.storagetest.StorageBoundarySupport.event
import com.github.j5ik2o.event.store.adapter.kotlin.storagetest.StorageBoundarySupport.snapshot
import com.github.j5ik2o.event.store.adapter.kotlin.storagetest.StorageBoundarySupport.withThrowingWarnLog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Calls the Kotlin public entry points against the real Spanner emulator; the caller owns the client. */
class KotlinSpannerBoundaryTest {
    private val emulator = SpannerEmulator.shared()

    private fun settings(
        prefix: String,
        policy: SpannerRetentionPolicy,
    ) = SpannerStorageConfig
        .builder()
        .tablePrefix(prefix)
        .retentionPolicy(policy)
        .build()

    private fun store(keep: Int?): String = emulator.createStore(null, SpannerEmulator.ConfigRow.matching(keep, "delete", null))

    /** Fails retention on the second append, after the first one has been stored. */
    private class FailRetentionAfterFirst : SpannerStorageHooks {
        var calls = 0

        override fun beforeRetentionDelete(
            aggregateId: AggregateId,
            seqNrs: List<Long>,
        ) {
            calls++
            throw IllegalStateException("injected retention failure")
        }
    }

    private fun open(
        prefix: String,
        hooks: SpannerStorageHooks,
    ): SpannerStorage = SpannerStorage.open(emulator.client(), settings(prefix, SpannerRetentionPolicy.delete(1)), hooks)

    private fun assertBothAppendsAreStored(prefix: String) {
        assertEquals(listOf(2L), emulator.longs("SELECT seq_nr FROM ${prefix}_head"))
        assertEquals(listOf(1L, 2L), emulator.longs("SELECT seq_nr FROM ${prefix}_journal ORDER BY seq_nr"))
        assertEquals(listOf(2L), emulator.longs("SELECT seq_nr FROM ${prefix}_snapshot_current"))
        assertEquals(listOf(1L, 2L), emulator.longs("SELECT seq_nr FROM ${prefix}_snapshot_history ORDER BY seq_nr"))
    }

    @Test
    fun syncStoreAppendsReadsReopensAndRejectsDuplicateAppendOnTheEmulator() {
        val prefix = store(null)
        val settings = settings(prefix, SpannerRetentionPolicy.none())
        StorageBoundarySupport.writeAndReadSync(EventStore.ofSpanner(SpannerStorage.open(emulator.client(), settings), config()))
        StorageBoundarySupport.assertRestoredSync(EventStore.ofSpanner(SpannerStorage.open(emulator.client(), settings), config()))
    }

    @Test
    fun asyncStoreAppendsReadsReopensAndRejectsDuplicateAppendOnTheEmulator() =
        runBlocking {
            val prefix = store(null)
            val settings = settings(prefix, SpannerRetentionPolicy.none())
            StorageBoundarySupport.writeAndReadAsync(EventStoreAsync.ofSpanner(SpannerStorage.open(emulator.client(), settings), config()))
            StorageBoundarySupport.assertRestoredAsync(
                EventStoreAsync.ofSpanner(SpannerStorage.open(emulator.client(), settings), config()),
            )
        }

    @Test
    fun syncAppendSucceedsWhenTheListenerThrowsAnAssertionError() {
        val prefix = store(1)
        val hooks = FailRetentionAfterFirst()
        val listener = ThrowingListener()
        val store = EventStore.ofSpanner(open(prefix, hooks), config(listener))
        store.persistEventAndSnapshot(event(1), snapshot(1))
        val hooksBefore = hooks.calls
        val listenerBefore = listener.calls

        assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))

        assertEquals(listenerBefore + 1, listener.calls)
        assertEquals(hooksBefore + 1, hooks.calls)
        assertBothAppendsAreStored(prefix)
    }

    @Test
    fun syncAppendSucceedsWhenTheRetentionLogThrowsAnAssertionError() {
        val prefix = store(1)
        val hooks = FailRetentionAfterFirst()
        val notifications = mutableListOf<Any>()
        withThrowingWarnLog(SpannerEventStore::class.java) { appender ->
            val store = EventStore.ofSpanner(open(prefix, hooks), config(RetentionFailureListener { notifications.add(it) }))
            store.persistEventAndSnapshot(event(1), snapshot(1))
            val warnsBefore = appender.warnCalls
            val notificationsBefore = notifications.size

            assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))

            assertEquals(warnsBefore + 1, appender.warnCalls)
            assertEquals(notificationsBefore + 1, notifications.size)
        }
        assertBothAppendsAreStored(prefix)
    }

    @Test
    fun asyncAppendCompletesWhenTheListenerThrowsAnAssertionError() =
        runBlocking {
            val prefix = store(1)
            val hooks = FailRetentionAfterFirst()
            val listener = ThrowingListener()
            val store = EventStoreAsync.ofSpanner(open(prefix, hooks), config(listener))
            store.persistEventAndSnapshot(event(1), snapshot(1))
            val hooksBefore = hooks.calls
            val listenerBefore = listener.calls

            assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))

            assertEquals(listenerBefore + 1, listener.calls)
            assertEquals(hooksBefore + 1, hooks.calls)
            assertBothAppendsAreStored(prefix)
        }

    @Test
    fun asyncAppendCompletesWhenTheRetentionLogThrowsAnAssertionError() =
        runBlocking {
            val prefix = store(1)
            val hooks = FailRetentionAfterFirst()
            val notifications = mutableListOf<Any>()
            withThrowingWarnLog(SpannerEventStore::class.java) { appender ->
                runBlocking {
                    val store = EventStoreAsync.ofSpanner(open(prefix, hooks), config(RetentionFailureListener { notifications.add(it) }))
                    store.persistEventAndSnapshot(event(1), snapshot(1))
                    val warnsBefore = appender.warnCalls
                    val notificationsBefore = notifications.size

                    assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))

                    assertEquals(warnsBefore + 1, appender.warnCalls)
                    assertEquals(notificationsBefore + 1, notifications.size)
                }
            }
            assertBothAppendsAreStored(prefix)
        }
}
