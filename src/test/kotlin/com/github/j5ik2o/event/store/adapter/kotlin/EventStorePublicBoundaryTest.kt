package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException
import com.github.j5ik2o.event.store.adapter.java.core.ErrorCategory
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EventStorePublicBoundaryTest {
    private val id = AggregateId.of("Account", "public-memory")
    private val time = Instant.parse("2026-10-10T00:00:00.123456789Z")

    private fun config(
        serializer: PayloadSerializer<String> = JsonPayloadSerializer.of(String::class.java),
    ): EventStoreConfig<String, String> =
        EventStoreConfig
            .builder<String, String>()
            .payloadSerializer(serializer)
            .snapshotSerializer(JsonPayloadSerializer.of(String::class.java))
            .build()

    private fun event(seq: Long): EventEnvelope<String> =
        EventEnvelope
            .builder<String>()
            .aggregateId(id)
            .seqNr(seq)
            .occurredAt(time)
            .manifest("自由なイベント-$seq")
            .payload("payload-$seq")
            .build()

    private fun snapshot(): SnapshotEnvelope<String> =
        SnapshotEnvelope
            .builder<String>()
            .seqNr(2)
            .manifest("自由な状態")
            .aggregate("state-2")
            .build()

    private fun assertEnvelope(
        actual: EventEnvelope<String>,
        seq: Long,
    ) {
        assertEquals(id, actual.aggregateId())
        assertEquals(seq, actual.seqNr())
        assertEquals(time, actual.occurredAt())
        assertEquals("自由なイベント-$seq", actual.manifest())
        assertEquals("payload-$seq", actual.payload())
    }

    @Test
    fun synchronousMemoryPreservesEnvelopesAndAllThreeReadResults() {
        val storage = MemoryStorage.create()
        val store = EventStore.ofMemory(storage, config())
        assertNull(store.getLatestSnapshotById(id))
        assertEquals(Unit, store.persistEvent(event(1)))
        val headOnly = requireNotNull(store.getLatestSnapshotById(id))
        assertEquals(1, headOnly.headSeqNr())
        assertTrue(headOnly.snapshot().isEmpty)
        assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot()))
        store.persistEvent(event(3))
        val read = requireNotNull(store.getLatestSnapshotById(id))
        assertEquals(3, read.headSeqNr())
        val restored = read.snapshot().orElseThrow()
        assertEquals(2, restored.seqNr())
        assertEquals("自由な状態", restored.manifest())
        assertEquals("state-2", restored.aggregate())
        val events = store.getEventsByIdSinceSeqNr(id, 2)
        assertEquals(2, events.size)
        events.forEachIndexed { index, actual -> assertEnvelope(actual, index + 2L) }
        assertEquals(3, EventStore.ofMemory(storage, config()).getLatestSnapshotById(id)?.headSeqNr())
        assertNull(EventStore.ofMemory(MemoryStorage.create(), config()).getLatestSnapshotById(id))
    }

    @Test
    fun asynchronousMemoryPreservesEnvelopesAndAllThreeReadResults() =
        runTest {
            val storage = MemoryStorage.create()
            val store = EventStoreAsync.ofMemory(storage, config())
            assertNull(store.getLatestSnapshotById(id))
            assertEquals(Unit, store.persistEvent(event(1)))
            val headOnly = requireNotNull(store.getLatestSnapshotById(id))
            assertEquals(1, headOnly.headSeqNr())
            assertTrue(headOnly.snapshot().isEmpty)
            assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot()))
            store.persistEvent(event(3))
            val read = requireNotNull(store.getLatestSnapshotById(id))
            assertEquals(3, read.headSeqNr())
            assertEquals(2, read.snapshot().orElseThrow().seqNr())
            assertEquals("自由な状態", read.snapshot().orElseThrow().manifest())
            assertEquals("state-2", read.snapshot().orElseThrow().aggregate())
            val events = store.getEventsByIdSinceSeqNr(id, 2)
            assertEquals(2, events.size)
            events.forEachIndexed { index, actual -> assertEnvelope(actual, index + 2L) }
            assertEquals(3, EventStoreAsync.ofMemory(storage, config()).getLatestSnapshotById(id)?.headSeqNr())
            assertNull(EventStoreAsync.ofMemory(MemoryStorage.create(), config()).getLatestSnapshotById(id))
        }

    @Test
    fun actualJavaWriteErrorsKeepClassificationRuleAndCause() =
        runTest {
            for (asynchronous in listOf(false, true)) {
                val cause = IOException("serializer boundary")
                val failing =
                    object : PayloadSerializer<String> {
                        override fun serialize(value: String): ByteArray = throw cause

                        override fun deserialize(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)
                    }
                val sync = EventStore.ofMemory(MemoryStorage.create(), config(failing))
                val async = EventStoreAsync.ofMemory(MemoryStorage.create(), config(failing))
                val failure =
                    assertFailsWith<SerializationException> {
                        if (asynchronous) async.persistEvent(event(1)) else sync.persistEvent(event(1))
                    }
                assertEquals(ErrorCategory.SERIALIZATION, failure.category())
                assertSame(cause, failure.cause)
                val validSync = EventStore.ofMemory(MemoryStorage.create(), config())
                val validAsync = EventStoreAsync.ofMemory(MemoryStorage.create(), config())
                val gap =
                    assertFailsWith<ContractViolationException> {
                        if (asynchronous) validAsync.persistEvent(event(2)) else validSync.persistEvent(event(2))
                    }
                assertEquals(ErrorCategory.CONTRACT_VIOLATION, gap.category())
                assertEquals("W-8", gap.rule())
                assertEquals(2, gap.seqNr().asLong)
                if (asynchronous) validAsync.persistEvent(event(1)) else validSync.persistEvent(event(1))
                val duplicate =
                    assertFailsWith<OptimisticLockException> {
                        if (asynchronous) validAsync.persistEvent(event(1)) else validSync.persistEvent(event(1))
                    }
                assertEquals(ErrorCategory.OPTIMISTIC_LOCK, duplicate.category())
            }
        }

    /** This double owns futures, rather than reimplementing a store or its error rules. */
    private class PendingStore : AsyncEventStore<String, String> {
        val write = CompletableFuture<Void>()
        val latest = CompletableFuture<Optional<SnapshotReadResult<String>>>()
        val events = CompletableFuture<List<EventEnvelope<String>>>()

        override fun persistEvent(event: EventEnvelope<String>): CompletableFuture<Void> = write

        override fun persistEventAndSnapshot(
            event: EventEnvelope<String>,
            snapshot: SnapshotEnvelope<String>,
        ): CompletableFuture<Void> = write

        override fun getLatestSnapshotById(aggregateId: AggregateId): CompletableFuture<Optional<SnapshotReadResult<String>>> = latest

        override fun getEventsByIdSinceSeqNr(
            aggregateId: AggregateId,
            seqNr: Long,
        ): CompletableFuture<List<EventEnvelope<String>>> = events
    }

    @Test
    fun cancellationReachesTheAwaitedJavaFutureForEveryOperation() =
        runTest {
            for (operation in listOf("persistEvent", "persistEventAndSnapshot", "getLatestSnapshotById", "getEventsByIdSinceSeqNr")) {
                val pending = PendingStore()
                val store = EventStoreAsync.fromJava(pending)
                var cancelled = false
                val job =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            when (operation) {
                                "persistEvent" -> store.persistEvent(event(1))
                                "persistEventAndSnapshot" -> store.persistEventAndSnapshot(event(2), snapshot())
                                "getLatestSnapshotById" -> store.getLatestSnapshotById(id)
                                "getEventsByIdSinceSeqNr" -> store.getEventsByIdSinceSeqNr(id, 1)
                            }
                        } catch (error: CancellationException) {
                            cancelled = true
                            throw error
                        }
                    }
                val future =
                    when (operation) {
                        "getLatestSnapshotById" -> pending.latest
                        "getEventsByIdSinceSeqNr" -> pending.events
                        else -> pending.write
                    }
                assertFalse(future.isDone)
                job.cancelAndJoin()
                assertTrue(cancelled)
                assertTrue(future.isCancelled)
            }
        }
}
