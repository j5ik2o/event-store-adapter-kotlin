package com.github.j5ik2o.event.store.adapter.java.bigtable

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailureListener
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy
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
import kotlin.test.assertTrue

/** Calls the Kotlin public entry points against the real Bigtable emulator; the caller owns both clients. */
class KotlinBigtableBoundaryTest {
    private val emulator = BigtableEmulator.shared()

    private fun settings(
        tables: BigtableEmulator.Tables,
        policy: RetentionPolicy,
    ) = BigtableStorageConfig
        .builder()
        .journalTableId(tables.journal)
        .headTableId(tables.head)
        .retentionPolicy(policy)
        .build()

    private fun open(
        tables: BigtableEmulator.Tables,
        policy: RetentionPolicy,
    ): BigtableStorage = BigtableStorage.open(emulator.data(), emulator.admin(), settings(tables, policy))

    private fun open(
        tables: BigtableEmulator.Tables,
        hooks: BigtableStorageHooks,
    ): BigtableStorage = BigtableStorage.open(emulator.data(), emulator.admin(), settings(tables, RetentionPolicy.delete(1)), hooks)

    /** Fails retention on the second append, after the first one has been stored. */
    private class FailRetentionAfterFirst : BigtableStorageHooks {
        var calls = 0

        override fun beforeRetentionDelete(
            aggregateId: AggregateId,
            seqNrs: List<Long>,
        ) {
            calls++
            throw IllegalStateException("injected retention failure")
        }
    }

    /** Both appends are visible through a fresh public store opened on the same tables. */
    private fun assertBothAppendsAreStored(tables: BigtableEmulator.Tables) {
        val reopened = EventStore.ofBigtable(open(tables, RetentionPolicy.delete(1)), config())
        val read = requireNotNull(reopened.getLatestSnapshotById(StorageBoundarySupport.id))
        assertEquals(2L, read.headSeqNr())
        assertEquals(2L, read.snapshot().orElseThrow().seqNr())
        assertEquals(listOf(1L, 2L), reopened.getEventsByIdSinceSeqNr(StorageBoundarySupport.id, 1).map { it.seqNr() })
    }

    @Test
    fun syncStoreInitializesAppendsReadsReopensAndRejectsDuplicateAppendOnTheEmulator() {
        val tables = emulator.createTables()
        val settings = settings(tables, RetentionPolicy.none())
        BigtableStorage.initialize(emulator.data(), settings)
        StorageBoundarySupport.writeAndReadSync(EventStore.ofBigtable(open(tables, RetentionPolicy.none()), config()))
        StorageBoundarySupport.assertRestoredSync(EventStore.ofBigtable(open(tables, RetentionPolicy.none()), config()))
    }

    @Test
    fun asyncStoreInitializesAppendsReadsReopensAndRejectsDuplicateAppendOnTheEmulator() =
        runBlocking {
            val tables = emulator.createTables()
            BigtableStorage.initialize(emulator.data(), settings(tables, RetentionPolicy.none()))
            StorageBoundarySupport.writeAndReadAsync(EventStoreAsync.ofBigtable(open(tables, RetentionPolicy.none()), config()))
            StorageBoundarySupport.assertRestoredAsync(EventStoreAsync.ofBigtable(open(tables, RetentionPolicy.none()), config()))
        }

    @Test
    fun syncAppendSucceedsWhenTheListenerThrowsAnAssertionError() {
        val tables = emulator.createStore("1")
        val hooks = FailRetentionAfterFirst()
        val listener = ThrowingListener()
        val store = EventStore.ofBigtable(open(tables, hooks), config(listener))
        store.persistEventAndSnapshot(event(1), snapshot(1))
        val hooksBefore = hooks.calls
        val listenerBefore = listener.calls

        assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))

        assertEquals(listenerBefore + 1, listener.calls)
        assertEquals(hooksBefore + 1, hooks.calls)
        assertBothAppendsAreStored(tables)
    }

    @Test
    fun syncAppendSucceedsWhenTheRetentionLogThrowsAnAssertionError() {
        val tables = emulator.createStore("1")
        val hooks = FailRetentionAfterFirst()
        val notifications = mutableListOf<Any>()
        withThrowingWarnLog(BigtableEventStore::class.java) { appender ->
            val store = EventStore.ofBigtable(open(tables, hooks), config(RetentionFailureListener { notifications.add(it) }))
            store.persistEventAndSnapshot(event(1), snapshot(1))
            val warnsBefore = appender.warnCalls
            val notificationsBefore = notifications.size

            assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))

            assertEquals(warnsBefore + 1, appender.warnCalls)
            assertEquals(notificationsBefore + 1, notifications.size)
            assertTrue(hooks.calls >= 1)
        }
        assertBothAppendsAreStored(tables)
    }

    @Test
    fun asyncAppendCompletesWhenTheListenerThrowsAnAssertionError() =
        runBlocking {
            val tables = emulator.createStore("1")
            val hooks = FailRetentionAfterFirst()
            val listener = ThrowingListener()
            val store = EventStoreAsync.ofBigtable(open(tables, hooks), config(listener))
            store.persistEventAndSnapshot(event(1), snapshot(1))
            val hooksBefore = hooks.calls
            val listenerBefore = listener.calls

            assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))

            assertEquals(listenerBefore + 1, listener.calls)
            assertEquals(hooksBefore + 1, hooks.calls)
            assertBothAppendsAreStored(tables)
        }

    @Test
    fun asyncAppendCompletesWhenTheRetentionLogThrowsAnAssertionError() =
        runBlocking {
            val tables = emulator.createStore("1")
            val hooks = FailRetentionAfterFirst()
            val notifications = mutableListOf<Any>()
            withThrowingWarnLog(BigtableEventStore::class.java) { appender ->
                runBlocking {
                    val store = EventStoreAsync.ofBigtable(open(tables, hooks), config(RetentionFailureListener { notifications.add(it) }))
                    store.persistEventAndSnapshot(event(1), snapshot(1))
                    val warnsBefore = appender.warnCalls
                    val notificationsBefore = notifications.size

                    assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))

                    assertEquals(warnsBefore + 1, appender.warnCalls)
                    assertEquals(notificationsBefore + 1, notifications.size)
                }
            }
            assertBothAppendsAreStored(tables)
        }
}
