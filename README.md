# event-store-adapter-kotlin

[![CI](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-kotlin/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-kotlin)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

This library is designed to turn DynamoDB into an Event Store for Event Sourcing.

[日本語](./README.ja.md)

# Usage

You can easily implement an Event Sourcing-enabled repository using EventStore.

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
        val (userAccount, version) = eventStore
            .getLatestSnapshotById(UserAccount::class.java, id) ?: return null
        val events = eventStore
            .getEventsByIdSinceSequenceNumber(
                UserAccountEvent::class.java, id, userAccount.sequenceNumber + 1)
        return UserAccount.replay(events, userAccount, version)
    }
}
```

The following is an example of the repository usage.

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

## Table Specifications

See [docs/DATABASE_SCHEMA.md](docs/DATABASE_SCHEMA.md).

## License.

MIT License. See [LICENSE](LICENSE) for details.

## Other language implementations

- [for Java](https://github.com/j5ik2o/event-store-adapter-java)
- [for Scala](https://github.com/j5ik2o/event-store-adapter-scala)
- [for Kotlin](https://github.com/j5ik2o/event-store-adapter-kotlin)
- [for Rust](https://github.com/j5ik2o/event-store-adapter-rs)
- [for Go](https://github.com/j5ik2o/event-store-adapter-go)
- [for JavaScript/TypeScript](https://github.com/j5ik2o/event-store-adapter-js)
