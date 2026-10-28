# event-store-adapter-kotlin

[![CI](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml/badge.svg?branch=main&event=push)](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml?query=branch%3Amain)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.j5ik2o/event-store-adapter-kotlin.svg)](https://central.sonatype.com/artifact/io.github.j5ik2o/event-store-adapter-kotlin)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)](https://github.com/j5ik2o/event-store-adapter-kotlin/blob/main/build.gradle.kts)
[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://github.com/j5ik2o/event-store-adapter-kotlin/blob/main/build.gradle.kts)
[![Contract v5](https://img.shields.io/badge/contract-v5-blueviolet.svg)](https://github.com/j5ik2o/event-store-adapter/tree/contract-v5/docs/spec)
[![Storages](https://img.shields.io/badge/storages-memory%20%7C%20DynamoDB%20%7C%20SQLite%20%7C%20Spanner%20%7C%20Bigtable-informational.svg)](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec/storage)
[![Renovate](https://img.shields.io/badge/renovate-enabled-brightgreen.svg)](https://renovatebot.com)
[![License](https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg)](https://github.com/j5ik2o/event-store-adapter-kotlin#license)

## Overview

A thin Kotlin wrapper over the new public [Java event store](https://github.com/j5ik2o/event-store-adapter-java), with memory, DynamoDB, SQLite, Spanner and Bigtable storage and suspending operations. [日本語](README.ja.md)

This source makes a breaking API and storage-layout cutover. This library follows [common contract v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md); its major version 5 matches the contract revision (core contract: [core-contract.md](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/core-contract.md)). It depends on Java `5.1.0`, which provides the SQLite, Spanner and Bigtable storages. See the [migration guide](docs/MIGRATION_GUIDE.md).

## Installation

The library is published to Maven Central. The Java public types are exported through the Kotlin library's Gradle `api` dependency.

```kotlin
repositories {
    mavenCentral()
}
val adapterVersion = "5.1.0"
dependencies {
    implementation("io.github.j5ik2o:event-store-adapter-kotlin:${adapterVersion}")
}
```

The SQLite, Spanner and Bigtable storages are available in Kotlin `5.1.0`.

## Quick start

Build envelopes using the Java `core` types. The following example uses both memory factories, all four operations, and the asynchronous DynamoDB factory.

```kotlin
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import java.time.Instant

val config = EventStoreConfig.builder<String, String>()
    .payloadSerializer(JsonPayloadSerializer.of(String::class.java))
    .snapshotSerializer(JsonPayloadSerializer.of(String::class.java))
    .build()
val storage = MemoryStorage.create()
val sync = EventStore.ofMemory(storage, config)
val async = EventStoreAsync.ofMemory(storage, config)
val id = AggregateId.of("Account", "1")

suspend fun appendAndRead() {
    check(async.getLatestSnapshotById(id) == null)
    val first = EventEnvelope.builder<String>()
        .aggregateId(id).seqNr(1)
        .occurredAt(Instant.parse("2026-10-10T00:00:00.123456789Z"))
        .manifest("account-name-v1").payload("Alice").build()
    async.persistEvent(first)
    val headOnly = requireNotNull(sync.getLatestSnapshotById(id))
    check(headOnly.snapshot().isEmpty)
    check(headOnly.headSeqNr() == 1L)

    val second = EventEnvelope.builder<String>()
        .aggregateId(id).seqNr(2)
        .occurredAt(Instant.parse("2026-10-10T00:00:01.123456789Z"))
        .manifest("account-name-v1").payload("Bob").build()
    val snapshot = SnapshotEnvelope.builder<String>()
        .aggregate("Bob").seqNr(2).manifest("account-state-v1").build()
    async.persistEventAndSnapshot(second, snapshot)
    val read = requireNotNull(async.getLatestSnapshotById(id))
    check(read.headSeqNr() == 2L)
    check(read.snapshot().orElseThrow().aggregate() == "Bob")
    check(async.getEventsByIdSinceSeqNr(id, 1).map { it.seqNr() } == listOf(1L, 2L))
}

suspend fun dynamoDbStore(client: DynamoDbAsyncClient): EventStoreAsync<String, String> {
    val tables = DynamoDbTableConfig.builder()
        .journalTableName("account-journal")
        .snapshotTableName("account-snapshot")
        .headTableName("account-head")
        .snapshotAidIndexName("active-history")
        .build()
    return EventStoreAsync.ofDynamoDB(client, tables, config)
}
```

For synchronous DynamoDB use `EventStore.ofDynamoDB(client, tables, config)` with a `DynamoDbClient`.

`getLatestSnapshotById` returns null only when the aggregate head is absent. A present `SnapshotReadResult` can have no snapshot, or a snapshot at a different sequence from its `headSeqNr()`. Replay starts at snapshot sequence + 1, or 1 if no snapshot exists. Event reads include the starting sequence and return every matching envelope in ascending order. Check that replay reaches the observed head. DynamoDB reads the head and current snapshot non-atomically, with strong consistency per item; a newer snapshot can legitimately be ahead of the observed head.

Executable repository examples are in [synchronous](src/test/kotlin/com/github/j5ik2o/event/store/adapter/kotlin/internal/UserAccountRepositorySync.kt) and [asynchronous](src/test/kotlin/com/github/j5ik2o/event/store/adapter/kotlin/internal/UserAccountRepositoryAsync.kt) form.

## Storages

### Memory

The memory storage lives in a single process. It does not persist to files, survive restarts, or share data across processes. Reuse a `MemoryStorage` to share records and its immutable retention settings; separate storage instances are independent.

### DynamoDB

Provision three independent tables before creating either store. Clients belong to the caller; stop using them only after outstanding requests reach their terminal state. For DynamoDB, keep the three tables in one Region and write to them only from that Region. Global tables (multi-Region replicas) are not supported. The table layout is described in the [table schema](docs/DYNAMODB_DATABASE_SCHEMA.md).

### SQLite

`EventStore.ofSqlite(storage, config)` and `EventStoreAsync.ofSqlite(storage, config)` wrap a `SqliteStorage` that you have opened. The caller owns the storage and closes it; the wrapper never does. Prepare the file once with `SqliteStorage.initialize`, then open it with `SqliteStorage.open` on every start. The same function therefore works on the first start and on every restart. The layout is described in the [table schema](docs/SQLITE_DATABASE_SCHEMA.md).

```kotlin
import com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteStorage
import com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteStorageConfig
import java.nio.file.Files
import java.nio.file.Path

fun openSqliteStorage(
    file: Path,
    settings: SqliteStorageConfig,
): SqliteStorage {
    if (Files.notExists(file)) SqliteStorage.initialize(file, settings)
    return SqliteStorage.open(file, settings)
}
```

```kotlin
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy
import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync

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
```

### Spanner

`EventStore.ofSpanner(storage, config)` and `EventStoreAsync.ofSpanner(storage, config)` wrap a `SpannerStorage`. Create the five tables and the configuration row outside the library, as the [table schema](docs/SPANNER_DATABASE_SCHEMA.md) describes. For the example below, create them with the table prefix `account` and no snapshot history: the configuration row has `keep_snapshot_count = NULL` and `retention_mode = 'delete'`, which matches `SpannerRetentionPolicy.none()`. The SQL in the schema document uses the prefix `esa` and keeps 3 snapshots, so replace the prefix and those two values. `SpannerStorage.open` only checks them. The caller owns the `DatabaseClient`. Opening again with the same table prefix, on the first start or after a restart, sees the same data.

```kotlin
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerRetentionPolicy
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerStorage
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerStorageConfig
import com.google.cloud.spanner.DatabaseClient

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
```

```kotlin
val store = EventStore.ofSpanner(openSpannerStorage(client, "account"), config)
```

### Bigtable

`EventStore.ofBigtable(storage, config)` and `EventStoreAsync.ofBigtable(storage, config)` wrap a `BigtableStorage`. Create the two tables with their column families outside the library, as the [table schema](docs/BIGTABLE_DATABASE_SCHEMA.md) describes. Call `BigtableStorage.initialize` once, on the first start only; a second call fails with `ConfigurationException`. Call `BigtableStorage.open` on every start. The caller owns the data and admin clients.

```kotlin
import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableStorage
import com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableStorageConfig
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient
import com.google.cloud.bigtable.data.v2.BigtableDataClient

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
```

```kotlin
initializeBigtableOnce(data, settings) // first start only
val store = EventStore.ofBigtable(openBigtableStorage(data, admin, settings), config)
```

## Serialization

Payload and aggregate types do not implement library interfaces. Serializers are selected when creating a store, through `payloadSerializer` and `snapshotSerializer` on `EventStoreConfig.builder()`; the Quick start example uses `JsonPayloadSerializer.of(...)`.

## Retention

Retention is set on `MemoryStorage.create(RetentionPolicy.delete(n))` or `DynamoDbTableConfig.builder().retentionPolicy(...)`. No retention policy means only the current snapshot. Memory, SQLite and Bigtable support deletion with `RetentionPolicy.delete(n)`; Spanner uses `SpannerRetentionPolicy`; DynamoDB also supports `RetentionPolicy.ttl(n, graceSeconds)`. Retention failure is logged and can be observed through `EventStoreConfig.retentionFailureListener`; it does not undo a committed append.

## Errors

The Java `core` exception types preserve five classifications: `OptimisticLockException`, `ContractViolationException`, `SerializationException`, `ConfigurationException`, and `StorageException`. Contract violations retain `rule()` and `seqNr()`; wrapped causes are preserved. Suspending factories and operations use `kotlinx.coroutines.future.await`. Cancelling the caller cancels the awaited Java result; an already submitted storage request can still finish or commit. Await its independent terminal state before releasing caller-owned resources.

## Migration

The cutover from the old Kotlin API and DynamoDB layout is described in the [migration guide](docs/MIGRATION_GUIDE.md).

## Conformance

Run `./gradlew build spotlessCheck` with Docker available. Tests use DynamoDB Local 3.3.1 at digest `sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab`, the Cloud Spanner emulator 1.5.58 and the Cloud Bigtable emulator, each pinned by digest in the test helpers; SQLite uses a real file. The distributed [conformance data](conformance/README.md) is checked through Kotlin entry points; the report is written to `build/reports/conformance/report.json` with case and rule results.

To change this repository, see the [contributing guide](CONTRIBUTING.md).

## License

Dual-licensed under MIT or Apache-2.0 (`MIT OR Apache-2.0`), at your option. See [LICENSE-MIT](LICENSE-MIT) and [LICENSE-APACHE](LICENSE-APACHE).
