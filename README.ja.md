> この文書は [README.md](README.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# event-store-adapter-kotlin

[![CI](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml/badge.svg?branch=main&event=push)](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml?query=branch%3Amain)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.j5ik2o/event-store-adapter-kotlin.svg)](https://central.sonatype.com/artifact/io.github.j5ik2o/event-store-adapter-kotlin)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-purple.svg)](https://github.com/j5ik2o/event-store-adapter-kotlin/blob/main/build.gradle.kts)
[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://github.com/j5ik2o/event-store-adapter-kotlin/blob/main/build.gradle.kts)
[![Contract v5](https://img.shields.io/badge/contract-v5-blueviolet.svg)](https://github.com/j5ik2o/event-store-adapter/tree/contract-v5/docs/spec)
[![Storages](https://img.shields.io/badge/storages-memory%20%7C%20DynamoDB%20%7C%20SQLite%20%7C%20Spanner%20%7C%20Bigtable-informational.svg)](https://github.com/j5ik2o/event-store-adapter/tree/main/docs/spec/storage)
[![Renovate](https://img.shields.io/badge/renovate-enabled-brightgreen.svg)](https://renovatebot.com)
[![License](https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg)](https://github.com/j5ik2o/event-store-adapter-kotlin#license)

## 概要

[Javaイベントストア](https://github.com/j5ik2o/event-store-adapter-java)の新公開型を使う薄いKotlinラッパーです。メモリ、DynamoDB、SQLite、Spanner、Bigtable とコルーチンの中断関数を提供します。[English](README.md)

このソースは公開操作と保存配置を破壊的に切り替えています。[共通契約 v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md)に従います。メジャーの番号 5 は、共通契約の版と同じです（中核の契約は[core-contract.md](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/core-contract.md)）。SQLite・Spanner・Bigtable を提供する Java `5.1.0` に依存します。[移行案内](docs/MIGRATION_GUIDE.ja.md)を参照してください。

## インストール

Maven Central に公開しています。Gradleの `api` 依存を通じてJavaの公開型を利用できます。

```kotlin
repositories {
    mavenCentral()
}
val adapterVersion = "5.1.0"
dependencies {
    implementation("io.github.j5ik2o:event-store-adapter-kotlin:${adapterVersion}")
}
```

SQLite・Spanner・Bigtable の保存先は Kotlin `5.1.0` で使えます。

## はじめに

Javaの `core` 型で封筒を構築します。次の例はメモリの同期・非同期生成、4操作、非同期DynamoDB生成を使います。

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

同期DynamoDBは `DynamoDbClient` を使って `EventStore.ofDynamoDB(client, tables, config)` で生成します。

`getLatestSnapshotById` のnullは集約ヘッドの不存在です。存在する `SnapshotReadResult` の中でもスナップショットは不存在を表せ、その封筒の番号と `headSeqNr()` は独立しています。リプレイはスナップショット番号+1、封筒がなければ1から始めます。イベント取得は指定番号を含み、全対象の封筒を番号の昇順で返します。リプレイが読取時のヘッド番号に届いたか確認してください。DynamoDBはヘッドと現在のスナップショットを各項目の強整合で非原子的に読むため、封筒がヘッド番号より新しい組も有効です。

実行可能なリポジトリ例は[同期](src/test/kotlin/com/github/j5ik2o/event/store/adapter/kotlin/internal/UserAccountRepositorySync.kt)と[非同期](src/test/kotlin/com/github/j5ik2o/event/store/adapter/kotlin/internal/UserAccountRepositoryAsync.kt)を参照してください。

## 保存先

### メモリ

メモリの保存先は単一プロセスのメモリだけを使います。ファイルへの保存、再起動後の回復、プロセス間の共有はしません。同じ `MemoryStorage` を渡すと記録と不変の保持設定を共有し、別々に作った保存先は独立します。

### DynamoDB

独立した3表を事前に外部で用意してください。クライアントは呼出側が所有し、実行中要求の終端を待ってから解放します。DynamoDB では、3 つの表は同じリージョンに置き、書き込みもそのリージョンからだけ行ってください。グローバルテーブル（複数リージョンのレプリカ）は範囲外です。表の配置は[テーブル仕様](docs/DYNAMODB_DATABASE_SCHEMA.ja.md)を参照してください。

### SQLite

`EventStore.ofSqlite(storage, config)` と `EventStoreAsync.ofSqlite(storage, config)` は、呼び出し側が開いた `SqliteStorage` を包みます。保存先の所有者は呼び出し側で、閉じるのも呼び出し側です。包む層は閉じません。ファイルは `SqliteStorage.initialize` で一度だけ用意し、起動のたびに `SqliteStorage.open` で開きます。そのため、次の関数は初回の起動でも再起動でもそのまま使えます。配置は[スキーマ文書](docs/SQLITE_DATABASE_SCHEMA.ja.md)を参照してください。

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
    // 再起動後も同じ呼び出しで同じファイルを開けます。
    return openSqliteStorage(file, settings).use { storage ->
        EventStoreAsync.ofSqlite(storage, config).getEventsByIdSinceSeqNr(first.aggregateId(), 1)
    }
}
```

### Spanner

`EventStore.ofSpanner(storage, config)` と `EventStoreAsync.ofSpanner(storage, config)` は `SpannerStorage` を包みます。5つの表と設定行は、[スキーマ文書](docs/SPANNER_DATABASE_SCHEMA.ja.md)のとおりライブラリの外で作ります。次の例では、表の接頭辞を `account`、スナップショットの履歴なしで作ります。設定行は `keep_snapshot_count = NULL`、`retention_mode = 'delete'` とし、`SpannerRetentionPolicy.none()` に合わせます。スキーマ文書の SQL は接頭辞 `esa` とスナップショット3件の保持なので、接頭辞とこの2つの値を置き換えてください。`SpannerStorage.open` は照合だけを行います。`DatabaseClient` の所有者は呼び出し側です。同じ表の接頭辞で開き直せば、初回の起動でも再起動でも同じデータが見えます。

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

`EventStore.ofBigtable(storage, config)` と `EventStoreAsync.ofBigtable(storage, config)` は `BigtableStorage` を包みます。列ファミリーを持つ2つの表は、[スキーマ文書](docs/BIGTABLE_DATABASE_SCHEMA.ja.md)のとおりライブラリの外で作ります。`BigtableStorage.initialize` は初回の起動でだけ一度呼びます。2回目は `ConfigurationException` で失敗します。`BigtableStorage.open` は起動のたびに呼びます。データ用と管理用のクライアントの所有者は呼び出し側です。

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
initializeBigtableOnce(data, settings) // 初回の起動だけ
val store = EventStore.ofBigtable(openBigtableStorage(data, admin, settings), config)
```

## 直列化

ペイロードと集約状態にライブラリのインターフェイス実装は不要です。シリアライザは生成時に、`EventStoreConfig.builder()` の `payloadSerializer` と `snapshotSerializer` で指定します。はじめにの例は `JsonPayloadSerializer.of(...)` を使います。

## 保持

保持は `MemoryStorage.create(RetentionPolicy.delete(n))` または `DynamoDbTableConfig.builder().retentionPolicy(...)` で指定します。未設定では現在のスナップショットだけを保存します。メモリ・SQLite・Bigtable は `RetentionPolicy.delete(n)` による削除方式、Spanner は `SpannerRetentionPolicy`、DynamoDBは削除方式と `RetentionPolicy.ttl(n, graceSeconds)` による期限切れ方式を提供します。保持失敗はログと任意の `EventStoreConfig.retentionFailureListener` で通知し、確定した追記の成功は維持します。

## エラー

Javaの `core` 例外型で楽観ロック、契約違反、直列化、設定、保存先の5分類を区別できます。型は `OptimisticLockException`、`ContractViolationException`、`SerializationException`、`ConfigurationException`、`StorageException` です。契約違反の `rule()` と `seqNr()`、例外原因を保持します。非同期生成・操作は `kotlinx.coroutines.future.await` で待ちます。取消は待機しているJava完了結果へ伝わりますが、送信済みの保存先要求はその後も終端や確定へ到達し得ます。呼出側資源は実要求の終端後に解放してください。

## 移行

旧Kotlin公開操作とDynamoDB配置からの切り替えは[移行ガイド](docs/MIGRATION_GUIDE.ja.md)に記載しています。

## 適合

Dockerを起動して `./gradlew build spotlessCheck` を実行します。試験はDynamoDB Local 3.3.1の固定ダイジェスト `sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab`、Cloud Spanner エミュレーター 1.5.58、Cloud Bigtable エミュレーター（いずれも試験補助の中でダイジェスト固定）を使い、SQLite は実ファイルで試験します。[配布適合データ](conformance/README.md)をKotlin入口経由で検査し、ケース・規則ごとの結果を `build/reports/conformance/report.json` へ保存します。

このリポジトリを変更する手順は[開発者向けの案内](CONTRIBUTING.ja.md)を参照してください。

## ライセンス

MIT と Apache-2.0 のどちらかを選べる二重ライセンス（`MIT OR Apache-2.0`）です。[LICENSE-MIT](LICENSE-MIT) と [LICENSE-APACHE](LICENSE-APACHE) を参照してください。
