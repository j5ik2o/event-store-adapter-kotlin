package com.github.j5ik2o.event.store.adapter.java.sqlite

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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Calls the Kotlin public entry points against a real SQLite file; the caller owns and closes the storage. */
class KotlinSqliteBoundaryTest {
    @TempDir
    lateinit var directory: Path

    private val failRetention =
        "CREATE TRIGGER fail_retention BEFORE DELETE ON snapshot_history BEGIN " +
            "SELECT RAISE(ABORT, 'injected retention failure'); END"

    private fun settings(policy: RetentionPolicy) =
        SqliteStorageConfig
            .builder()
            .retentionPolicy(policy)
            .lockWait(Duration.ofSeconds(10))
            .build()

    private fun file(): Path = directory.resolve("store.db")

    private fun <T> raw(
        file: Path,
        read: (java.sql.Statement) -> T,
    ): T = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()).use { c -> c.createStatement().use(read) }

    private fun longs(
        file: Path,
        sql: String,
    ): List<Long> =
        raw(file) { s ->
            s.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add(rs.getLong(1)) }
            }
        }

    private fun execute(
        file: Path,
        sql: String,
    ) = raw(file) { it.execute(sql) }

    private fun assertSecondAppendIsStored(file: Path) {
        assertEquals(listOf(2L), longs(file, "SELECT seq_nr FROM head"))
        assertEquals(listOf(1L, 2L), longs(file, "SELECT seq_nr FROM journal ORDER BY seq_nr"))
        assertEquals(listOf(2L), longs(file, "SELECT seq_nr FROM snapshot_current"))
        assertEquals(listOf(1L, 2L), longs(file, "SELECT seq_nr FROM snapshot_history ORDER BY seq_nr"))
    }

    @Test
    fun syncStoreAppendsReadsReopensAndRejectsDuplicateAppendOnARealFile() {
        val file = file()
        val settings = settings(RetentionPolicy.none())
        SqliteStorage.initialize(file, settings)
        SqliteStorage.open(file, settings).use { storage ->
            StorageBoundarySupport.writeAndReadSync(EventStore.ofSqlite(storage, config()))
        }
        SqliteStorage.open(file, settings).use { reopened ->
            StorageBoundarySupport.assertRestoredSync(EventStore.ofSqlite(reopened, config()))
        }
    }

    @Test
    fun asyncStoreAppendsReadsReopensAndRejectsDuplicateAppendOnARealFile() =
        runBlocking {
            val file = file()
            val settings = settings(RetentionPolicy.none())
            SqliteStorage.initialize(file, settings)
            SqliteStorage.open(file, settings).use { storage ->
                StorageBoundarySupport.writeAndReadAsync(EventStoreAsync.ofSqlite(storage, config()))
            }
            SqliteStorage.open(file, settings).use { reopened ->
                StorageBoundarySupport.assertRestoredAsync(EventStoreAsync.ofSqlite(reopened, config()))
            }
        }

    @Test
    fun syncAppendSucceedsWhenTheListenerThrowsAnAssertionError() {
        val file = file()
        SqliteStorage.initialize(file, settings(RetentionPolicy.delete(1)))
        val listener = ThrowingListener()
        SqliteStorage.open(file, settings(RetentionPolicy.delete(1))).use { storage ->
            val store = EventStore.ofSqlite(storage, config(listener))
            store.persistEventAndSnapshot(event(1), snapshot(1))
            execute(file, failRetention)

            assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))
        }
        assertEquals(1, listener.calls)
        assertSecondAppendIsStored(file)
    }

    @Test
    fun syncAppendSucceedsWhenTheRetentionLogThrowsAnAssertionError() {
        val file = file()
        SqliteStorage.initialize(file, settings(RetentionPolicy.delete(1)))
        val notifications = mutableListOf<Any>()
        val appender =
            withThrowingWarnLog(SqliteEventStore::class.java) { appender ->
                SqliteStorage.open(file, settings(RetentionPolicy.delete(1))).use { storage ->
                    val store = EventStore.ofSqlite(storage, config(RetentionFailureListener { notifications.add(it) }))
                    store.persistEventAndSnapshot(event(1), snapshot(1))
                    execute(file, failRetention)

                    assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))
                }
                appender
            }
        assertEquals(1, appender.warnCalls)
        assertEquals(1, notifications.size)
        assertSecondAppendIsStored(file)
    }

    @Test
    fun asyncAppendCompletesWhenTheListenerThrowsAnAssertionError() =
        runBlocking {
            val file = file()
            SqliteStorage.initialize(file, settings(RetentionPolicy.delete(1)))
            val listener = ThrowingListener()
            SqliteStorage.open(file, settings(RetentionPolicy.delete(1))).use { storage ->
                val store = EventStoreAsync.ofSqlite(storage, config(listener))
                store.persistEventAndSnapshot(event(1), snapshot(1))
                execute(file, failRetention)

                assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))
            }
            assertEquals(1, listener.calls)
            assertSecondAppendIsStored(file)
        }

    @Test
    fun asyncAppendCompletesWhenTheRetentionLogThrowsAnAssertionError() =
        runBlocking {
            val file = file()
            SqliteStorage.initialize(file, settings(RetentionPolicy.delete(1)))
            val notifications = mutableListOf<Any>()
            val appender =
                withThrowingWarnLog(SqliteEventStore::class.java) { appender ->
                    runBlocking {
                        SqliteStorage.open(file, settings(RetentionPolicy.delete(1))).use { storage ->
                            val store = EventStoreAsync.ofSqlite(storage, config(RetentionFailureListener { notifications.add(it) }))
                            store.persistEventAndSnapshot(event(1), snapshot(1))
                            execute(file, failRetention)

                            assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))
                        }
                    }
                    appender
                }
            assertTrue(appender.warnCalls == 1, "the retention warning path must be reached once")
            assertEquals(1, notifications.size)
            assertSecondAppendIsStored(file)
        }
}
