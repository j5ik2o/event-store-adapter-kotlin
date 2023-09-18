# event-store-adapter-kotlin

[![CI](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-kotlin/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-kotlin)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![tokei](https://tokei.rs/b1/github/j5ik2o/event-store-adapter-kotlin)](https://github.com/XAMPPRocky/tokei)

このライブラリ([j5ik2o/event-store-adapter-java](https://github.com/j5ik2o/event-store-adapter-java)のKotlinラッパー)は、DynamoDBをEvent Sourcing用のEvent Storeにするためのものです。

[English](./README.md)

# 使い方

EventStoreを使えば、Event Sourcing対応リポジトリを簡単に実装できます。

```kotlin
class UserAccountRepositoryAsync
  (private val eventStore: EventStoreAsync<UserAccountId, UserAccount, UserAccountEvent>) {

    suspend fun storeEvent(event: UserAccountEvent, version: Long) {
        eventStore.persistEvent(event, version)
    }

    suspend fun storeEventAndSnapshot(event: UserAccountEvent, aggregate: UserAccount) {
        eventStore.persistEventAndSnapshot(event, aggregate)
    }

    suspend fun findById(id: UserAccountId): UserAccount? {
        val userAccount = eventStore
            .getLatestSnapshotById(UserAccount::class.java, id) ?: return null
        val events = eventStore
            .getEventsByIdSinceSequenceNumber(
                UserAccountEvent::class.java, id, userAccount.sequenceNumber + 1)
        return UserAccount.replay(events, userAccount)
    }
}
```

以下はリポジトリの使用例です。

```kotlin
val eventStore = EventStoreAsync.ofDynamoDB<UserAccountId, UserAccount, UserAccountEvent>(
    client,
    JOURNAL_TABLE_NAME,
    SNAPSHOT_TABLE_NAME,
    JOURNAL_AID_INDEX_NAME,
    SNAPSHOT_AID_INDEX_NAME,
    32,
)
val userAccountRepository = UserAccountRepositoryAsync(eventStore)

val id = UserAccountId(IdGenerator.generate().toString())

val aggregateAndEvent1 = UserAccount.create(id, "test-1")
val aggregate1 = aggregateAndEvent1.first

userAccountRepository.storeEventAndSnapshot(aggregateAndEvent1.second, aggregate1)

val aggregateAndEvent2 = aggregate1.changeName("test-2")

userAccountRepository.storeEvent(aggregateAndEvent2.second, aggregateAndEvent2.first.version)

val result = userAccountRepository.findById(id)

if (result != null) {
    assertEquals(result.id, aggregateAndEvent1.first.id)
    assertEquals(result.name, "test-2")
} else {
    Assertions.fail<Any>("result is empty")
}
```

## テーブル仕様

[docs/DATABASE_SCHEMA.ja.md](docs/DATABASE_SCHEMA.ja.md)を参照してください。

## ライセンス

MITライセンスです。詳細は[LICENSE](LICENSE)を参照してください。

## 他の言語向けの実装

- [for Java](https://github.com/j5ik2o/event-store-adapter-java)
- [for Scala](https://github.com/j5ik2o/event-store-adapter-scala)
- [for Rust](https://github.com/j5ik2o/event-store-adapter-rs)
- [for Go](https://github.com/j5ik2o/event-store-adapter-go)
