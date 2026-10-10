# event-store-adapter-kotlin

[![CI](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-kotlin/badge.svg)](https://maven-badges.herokuapp.com/maven-central/io.github.j5ik2o/event-store-adapter-kotlin)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/license/mit/)

[Javaイベントストア](https://github.com/j5ik2o/event-store-adapter-java)の新公開型を使う薄いKotlinラッパーです。メモリ、DynamoDB、コルーチンの中断関数を提供します。[English](README.md)

このソースは公開操作と保存配置を[共通契約](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/core-contract.md)の第4版へ破壊的に切り替えています。Java配布 `2.0.0-SNAPSHOT` に依存し、その配布版は共通契約の版とは別です。この変更ではKotlinの正式公開や版番号の変更を行っていません。[移行案内](docs/MIGRATION.ja.md)を参照してください。

## 導入方法

この切替を含むKotlinの配布を選んでください。Java依存がSnapshotの間は、次のリポジトリも必要です。Gradleの `api` 依存を通じてJavaの公開型を利用できます。

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
}
val adapterVersion = "..." // この切替を含む配布版を指定してください。
dependencies {
    implementation("io.github.j5ik2o:event-store-adapter-kotlin:${adapterVersion}")
}
```

## 使い方

ペイロードと集約状態にライブラリのインターフェイス実装は不要です。Javaの `core` 型で封筒を構築し、生成時にシリアライザを指定します。次の例はメモリの同期・非同期生成、4操作、非同期DynamoDB生成を使います。

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

同期DynamoDBは `DynamoDbClient` を使って `EventStore.ofDynamoDB(client, tables, config)` で生成します。独立した3表を事前に外部で用意してください。クライアントは呼出側が所有し、実行中要求の終端を待ってから解放します。同じ `MemoryStorage` を渡すと記録と不変の保持設定を共有し、別々に作った保存先は独立します。

`getLatestSnapshotById` のnullは集約ヘッドの不存在です。存在する `SnapshotReadResult` の中でもスナップショットは不存在を表せ、その封筒の番号と `headSeqNr()` は独立しています。リプレイはスナップショット番号+1、封筒がなければ1から始めます。イベント取得は指定番号を含み、全対象の封筒を番号の昇順で返します。リプレイが読取時のヘッド番号に届いたか確認してください。DynamoDBはヘッドと現在のスナップショットを各項目の強整合で非原子的に読むため、封筒がヘッド番号より新しい組も有効です。

保持は `MemoryStorage.create(RetentionPolicy.delete(n))` または `DynamoDbTableConfig.builder().retentionPolicy(...)` で指定します。未設定では現在のスナップショットだけを保存します。メモリは削除方式、DynamoDBは削除方式と `RetentionPolicy.ttl(n, graceSeconds)` による期限切れ方式を提供します。保持失敗はログと任意の `EventStoreConfig.retentionFailureListener` で通知し、確定した追記の成功は維持します。

Javaの `core` 例外型で楽観ロック、契約違反、直列化、設定、保存先の5分類を区別できます。型は `OptimisticLockException`、`ContractViolationException`、`SerializationException`、`ConfigurationException`、`StorageException` です。契約違反の `rule()` と `seqNr()`、例外原因を保持します。非同期生成・操作は `kotlinx.coroutines.future.await` で待ちます。取消は待機しているJava完了結果へ伝わりますが、送信済みの保存先要求はその後も終端や確定へ到達し得ます。呼出側資源は実要求の終端後に解放してください。

実行可能なリポジトリ例は[同期](src/test/kotlin/com/github/j5ik2o/event/store/adapter/kotlin/internal/UserAccountRepositorySync.kt)と[非同期](src/test/kotlin/com/github/j5ik2o/event/store/adapter/kotlin/internal/UserAccountRepositoryAsync.kt)を参照してください。

## 保存形式と検証

[テーブル仕様](docs/DATABASE_SCHEMA.ja.md)と[移行案内](docs/MIGRATION.ja.md)があります。Dockerを起動して `./gradlew build spotlessCheck` を実行します。試験はDynamoDB Local 3.3.1の固定ダイジェスト `sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab` を使います。[配布適合データ](conformance/README.md)をKotlin入口経由で検査し、ケース・規則ごとの結果を `build/reports/conformance/report.json` へ保存します。

## ライセンス

[MITライセンス](https://opensource.org/license/mit/)です。
