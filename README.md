# event-store-adapter-kotlin

[![CI](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-kotlin/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-kotlin)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/license/mit/)

A thin Kotlin wrapper over the new public [Java event store](https://github.com/j5ik2o/event-store-adapter-java), with memory and DynamoDB storage and suspending operations. [日本語](README.ja.md)

This source makes a breaking API and storage-layout cutover to the [common contract](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/core-contract.md), revision 4. It depends on Java distribution `2.0.0-SNAPSHOT`; that distribution version is separate from the contract revision. A formal Kotlin release has not been made by this change, and the Kotlin version file is unchanged. See the [migration guide](docs/MIGRATION.md).

## Installation

Choose a Kotlin build that contains this cutover. The snapshot repository is required while the Java dependency is a snapshot. The Java public types are exported through the Kotlin library's Gradle `api` dependency.

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
}
val adapterVersion = "..." // Select a build containing this API cutover.
dependencies {
    implementation("io.github.j5ik2o:event-store-adapter-kotlin:${adapterVersion}")
}
```

## Usage

Payload and aggregate types do not implement library interfaces. Build envelopes using the Java `core` types; serializers are selected when creating a store. The following example uses both memory factories, all four operations, and the asynchronous DynamoDB factory.

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

For synchronous DynamoDB use `EventStore.ofDynamoDB(client, tables, config)` with a `DynamoDbClient`. Provision three independent tables before creating either store. Clients belong to the caller; stop using them only after outstanding requests reach their terminal state. Reuse a `MemoryStorage` to share records and its immutable retention settings; separate storage instances are independent.

`getLatestSnapshotById` returns null only when the aggregate head is absent. A present `SnapshotReadResult` can have no snapshot, or a snapshot at a different sequence from its `headSeqNr()`. Replay starts at snapshot sequence + 1, or 1 if no snapshot exists. Event reads include the starting sequence and return every matching envelope in ascending order. Check that replay reaches the observed head. DynamoDB reads the head and current snapshot non-atomically, with strong consistency per item; a newer snapshot can legitimately be ahead of the observed head.

Retention is set on `MemoryStorage.create(RetentionPolicy.delete(n))` or `DynamoDbTableConfig.builder().retentionPolicy(...)`. No retention policy means only the current snapshot. Memory supports deletion; DynamoDB also supports `RetentionPolicy.ttl(n, graceSeconds)`. Retention failure is logged and can be observed through `EventStoreConfig.retentionFailureListener`; it does not undo a committed append.

The Java `core` exception types preserve five classifications: `OptimisticLockException`, `ContractViolationException`, `SerializationException`, `ConfigurationException`, and `StorageException`. Contract violations retain `rule()` and `seqNr()`; wrapped causes are preserved. Suspending factories and operations use `kotlinx.coroutines.future.await`. Cancelling the caller cancels the awaited Java result; an already submitted storage request can still finish or commit. Await its independent terminal state before releasing caller-owned resources.

Executable repository examples are in [synchronous](src/test/kotlin/com/github/j5ik2o/event/store/adapter/kotlin/internal/UserAccountRepositorySync.kt) and [asynchronous](src/test/kotlin/com/github/j5ik2o/event/store/adapter/kotlin/internal/UserAccountRepositoryAsync.kt) form.

## Storage and verification

See the [table schema](docs/DATABASE_SCHEMA.md) and [migration guide](docs/MIGRATION.md). Run `./gradlew build spotlessCheck` with Docker available. Tests use DynamoDB Local 3.3.1 at digest `sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab`. The distributed [conformance data](conformance/README.md) is checked through Kotlin entry points; the report is written to `build/reports/conformance/report.json` with case and rule results.

## License

[MIT](https://opensource.org/license/mit/).
