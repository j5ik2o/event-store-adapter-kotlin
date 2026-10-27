package com.github.j5ik2o.event.store.adapter.kotlin.examples

import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableEmulator
import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableStorageConfig
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerEmulator
import com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteStorageConfig
import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import com.github.j5ik2o.event.store.adapter.kotlin.storagetest.StorageBoundarySupport.config
import com.github.j5ik2o.event.store.adapter.kotlin.storagetest.StorageBoundarySupport.event
import com.github.j5ik2o.event.store.adapter.kotlin.storagetest.StorageBoundarySupport.id
import com.github.j5ik2o.event.store.adapter.kotlin.storagetest.StorageBoundarySupport.snapshot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * The README storage examples run through public types only: a first start that prepares the
 * storage, then a restart that opens the same storage again and sees the data written before.
 */
class StorageExamplesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun sqliteExamplePreparesTheFileOnFirstStartAndReopensItOnRestart() {
        val file = directory.resolve("example.db")
        val settings = SqliteStorageConfig.builder().retentionPolicy(RetentionPolicy.none()).build()

        openSqliteStorage(file, settings).use { storage ->
            EventStore.ofSqlite(storage, config()).persistEventAndSnapshot(event(1), snapshot(1))
        }
        openSqliteStorage(file, settings).use { storage ->
            val store = EventStore.ofSqlite(storage, config())
            assertEquals(1L, requireNotNull(store.getLatestSnapshotById(id)).headSeqNr())
            store.persistEvent(event(2))
        }
        openSqliteStorage(file, settings).use { storage ->
            val store = runBlocking { EventStoreAsync.ofSqlite(storage, config()).getEventsByIdSinceSeqNr(id, 1) }
            assertEquals(listOf(1L, 2L), store.map { it.seqNr() })
        }
    }

    @Test
    fun sqliteAppendExampleCompilesOnItsOwnAndReadsTheEventBackAfterReopening() {
        val events = runBlocking { appendThenReopenSqlite(directory.resolve("append.db"), config(), event(1)) }

        assertEquals(listOf(1L), events.map { it.seqNr() })
    }

    @Test
    fun spannerExampleOpensTheSameTablesOnFirstStartAndOnRestart() {
        val emulator = SpannerEmulator.shared()
        val prefix = emulator.createStore(null, SpannerEmulator.ConfigRow.matching(null, "delete", null))

        EventStore.ofSpanner(openSpannerStorage(emulator.client(), prefix), config()).persistEventAndSnapshot(event(1), snapshot(1))
        val restarted = EventStore.ofSpanner(openSpannerStorage(emulator.client(), prefix), config())

        assertEquals(1L, requireNotNull(restarted.getLatestSnapshotById(id)).headSeqNr())
        restarted.persistEvent(event(2))
        assertEquals(2L, requireNotNull(restarted.getLatestSnapshotById(id)).headSeqNr())
    }

    @Test
    fun bigtableExampleInitializesOnceOnFirstStartAndOnlyOpensOnRestart() {
        val emulator = BigtableEmulator.shared()
        val tables = emulator.createTables()
        val settings =
            BigtableStorageConfig
                .builder()
                .journalTableId(tables.journal)
                .headTableId(tables.head)
                .retentionPolicy(RetentionPolicy.none())
                .build()

        initializeBigtableOnce(emulator.data(), settings)
        EventStore
            .ofBigtable(openBigtableStorage(emulator.data(), emulator.admin(), settings), config())
            .persistEventAndSnapshot(event(1), snapshot(1))
        val restarted = EventStore.ofBigtable(openBigtableStorage(emulator.data(), emulator.admin(), settings), config())

        assertEquals(1L, requireNotNull(restarted.getLatestSnapshotById(id)).headSeqNr())
        restarted.persistEvent(event(2))
        assertEquals(2L, requireNotNull(restarted.getLatestSnapshotById(id)).headSeqNr())
    }
}
