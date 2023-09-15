# event-store-adapter-kotlin

[![CI](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-kotlin/actions/workflows/ci.yml)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-kotlin/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.j5ik2o/event-store-adapter-kotlin)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![tokei](https://tokei.rs/b1/github/j5ik2o/event-store-adapter-kotlin)](https://github.com/XAMPPRocky/tokei)

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
```

## Table Specifications

See [docs/DATABASE_SCHEMA.md](docs/DATABASE_SCHEMA.md).

## License.

MIT License. See [LICENSE](LICENSE) for details.

## Other language implementations

- [for Java](https://github.com/j5ik2o/event-store-adapter-java)
- [for Scala](https://github.com/j5ik2o/event-store-adapter-scala)
- [for Rust](https://github.com/j5ik2o/event-store-adapter-rs)
- [for Go](https://github.com/j5ik2o/event-store-adapter-go)
