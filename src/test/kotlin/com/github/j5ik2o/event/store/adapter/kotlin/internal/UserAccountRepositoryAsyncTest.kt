package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserAccountRepositoryAsyncTest {
    @Test
    fun replayWithoutSnapshotAndThenFromOlderSnapshot() =
        runTest {
            val config =
                EventStoreConfig
                    .builder<UserAccountEvent, UserAccount>()
                    .payloadSerializer(JsonPayloadSerializer.of(UserAccountEvent::class.java))
                    .snapshotSerializer(JsonPayloadSerializer.of(UserAccount::class.java))
                    .build()
            val repository = UserAccountRepositoryAsync(EventStoreAsync.ofMemory(MemoryStorage.create(), config))
            val id = AggregateId.of("UserAccount", "repository-async")
            assertNull(repository.findById(id))

            fun event(
                seq: Long,
                name: String,
            ) = EventEnvelope
                .builder<UserAccountEvent>()
                .aggregateId(id)
                .seqNr(seq)
                .occurredAt(Instant.parse("2026-10-10T00:00:00.123456789Z"))
                .manifest("account-name-v1")
                .payload(UserAccountEvent(name))
                .build()
            repository.storeEvent(event(1, "first"))
            assertEquals(UserAccount("first"), repository.findById(id))
            repository.storeEventAndSnapshot(
                event(2, "second"),
                SnapshotEnvelope
                    .builder<UserAccount>()
                    .seqNr(2)
                    .manifest("account-v1")
                    .aggregate(UserAccount("second"))
                    .build(),
            )
            repository.storeEvent(event(3, "third"))
            assertEquals(UserAccount("third"), repository.findById(id))
        }
}
