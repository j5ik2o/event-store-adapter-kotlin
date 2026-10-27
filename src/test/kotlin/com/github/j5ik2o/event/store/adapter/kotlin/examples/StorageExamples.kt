package com.github.j5ik2o.event.store.adapter.kotlin.examples

import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableStorage
import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableStorageConfig
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerRetentionPolicy
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerStorage
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerStorageConfig
import com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteStorage
import com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteStorageConfig
import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient
import com.google.cloud.bigtable.data.v2.BigtableDataClient
import com.google.cloud.spanner.DatabaseClient
import java.nio.file.Files
import java.nio.file.Path

// README storage examples: public types only, valid on the first start and on every restart.

fun openSqliteStorage(
    file: Path,
    settings: SqliteStorageConfig,
): SqliteStorage {
    if (Files.notExists(file)) SqliteStorage.initialize(file, settings)
    return SqliteStorage.open(file, settings)
}

fun openSpannerStorage(
    client: DatabaseClient,
    tablePrefix: String,
): SpannerStorage =
    SpannerStorage.open(
        client,
        SpannerStorageConfig
            .builder()
            .tablePrefix(tablePrefix)
            .retentionPolicy(SpannerRetentionPolicy.none())
            .build(),
    )

suspend fun appendThenReopenSqlite(
    file: Path,
    config: EventStoreConfig<String, String>,
    first: EventEnvelope<String>,
): List<EventEnvelope<String>> {
    val settings = SqliteStorageConfig.builder().retentionPolicy(RetentionPolicy.none()).build()
    openSqliteStorage(file, settings).use { storage ->
        EventStore.ofSqlite(storage, config).persistEvent(first)
    }
    // After a restart the same call opens the same file.
    return openSqliteStorage(file, settings).use { storage ->
        EventStoreAsync.ofSqlite(storage, config).getEventsByIdSinceSeqNr(first.aggregateId(), 1)
    }
}

/** Call once, on the first start only; a second call fails with ConfigurationException. */
fun initializeBigtableOnce(
    data: BigtableDataClient,
    settings: BigtableStorageConfig,
) = BigtableStorage.initialize(data, settings)

fun openBigtableStorage(
    data: BigtableDataClient,
    admin: BigtableTableAdminClient,
    settings: BigtableStorageConfig,
): BigtableStorage = BigtableStorage.open(data, admin, settings)
