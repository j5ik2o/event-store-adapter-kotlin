package com.github.j5ik2o.event.store.adapter.java.dynamodbtest

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbEventStore
import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import software.amazon.awssdk.core.SdkResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinDynamoDbBoundaryTest {
    companion object {
        @RegisterExtension
        @JvmField
        val fixture = DynamoDbConfigurationFixture()
    }

    private val id = AggregateId.of("Account", "public-dynamodb")
    private val time = Instant.parse("2026-10-10T00:00:00.123456789Z")
    private val config =
        EventStoreConfig
            .builder<String, String>()
            .payloadSerializer(JsonPayloadSerializer.of(String::class.java))
            .snapshotSerializer(JsonPayloadSerializer.of(String::class.java))
            .build()

    private fun event(seq: Long): EventEnvelope<String> =
        EventEnvelope
            .builder<String>()
            .aggregateId(id)
            .seqNr(seq)
            .occurredAt(time)
            .manifest("event-$seq")
            .payload("payload-$seq")
            .build()

    private fun snapshot(seq: Long): SnapshotEnvelope<String> =
        SnapshotEnvelope
            .builder<String>()
            .seqNr(seq)
            .manifest("state-$seq")
            .aggregate("aggregate-$seq")
            .build()

    private suspend fun <T> operate(
        c: DynamoDbTestContext,
        number: Int,
        writing: Boolean,
        action: suspend () -> T,
    ): T {
        val operation = c.faults.begin(number, writing)
        try {
            return action()
        } finally {
            c.recorder.requestsFinished(operation).get(30, TimeUnit.SECONDS)
            assertEquals(0, c.faults.pending(operation))
            assertEquals("passed", c.faults.finish(operation).status)
        }
    }

    @Test
    fun bothFactoriesAndAllOperationsRestoreExactEnvelopeValues() =
        runBlocking {
            for (asynchronous in listOf(false, true)) {
                val c = fixture.createContext()
                try {
                    DynamoDbConfigurationTables.create(c, RetentionPolicy.none())
                    val tables = DynamoDbConfigurationTables.config(c).build()
                    val sync = if (asynchronous) null else operate(c, 0, false) { EventStore.ofDynamoDB(c.client, tables, config) }
                    val async = if (asynchronous) operate(c, 0, false) { EventStoreAsync.ofDynamoDB(c.async, tables, config) } else null
                    val absent =
                        operate(c, 1, false) {
                            if (asynchronous) async!!.getLatestSnapshotById(id) else sync!!.getLatestSnapshotById(id)
                        }
                    assertNull(absent)
                    val written =
                        operate(c, 2, true) {
                            if (asynchronous) async!!.persistEvent(event(1)) else sync!!.persistEvent(event(1))
                        }
                    assertEquals(Unit, written)
                    val headOnly =
                        operate(c, 3, false) {
                            if (asynchronous) async!!.getLatestSnapshotById(id) else sync!!.getLatestSnapshotById(id)
                        }
                    assertEquals(1L, requireNotNull(headOnly).headSeqNr())
                    assertTrue(headOnly.snapshot().isEmpty)
                    val writtenSnapshot =
                        operate(c, 4, true) {
                            if (asynchronous) {
                                async!!.persistEventAndSnapshot(event(2), snapshot(2))
                            } else {
                                sync!!.persistEventAndSnapshot(event(2), snapshot(2))
                            }
                        }
                    assertEquals(Unit, writtenSnapshot)
                    operate(c, 5, true) { if (asynchronous) async!!.persistEvent(event(3)) else sync!!.persistEvent(event(3)) }
                    val read =
                        requireNotNull(
                            operate(c, 6, false) {
                                if (asynchronous) async!!.getLatestSnapshotById(id) else sync!!.getLatestSnapshotById(id)
                            },
                        )
                    assertEquals(3L, read.headSeqNr())
                    assertEquals(2L, read.snapshot().orElseThrow().seqNr())
                    assertEquals("state-2", read.snapshot().orElseThrow().manifest())
                    assertEquals("aggregate-2", read.snapshot().orElseThrow().aggregate())
                    val events =
                        operate(c, 7, false) {
                            if (asynchronous) async!!.getEventsByIdSinceSeqNr(id, 2) else sync!!.getEventsByIdSinceSeqNr(id, 2)
                        }
                    assertEquals(listOf(2L, 3L), events.map { it.seqNr() })
                    events.forEach {
                        assertEquals(id, it.aggregateId())
                        assertEquals(time, it.occurredAt())
                        assertEquals("event-${it.seqNr()}", it.manifest())
                        assertEquals("payload-${it.seqNr()}", it.payload())
                    }
                } finally {
                    c.close()
                    fixture.releaseClosedContext(c)
                }
            }
        }

    private class CapturingStore(
        private val underlying: AsyncEventStore<String, String>,
    ) : AsyncEventStore<String, String> {
        val awaited = AtomicReference<CompletableFuture<*>?>()

        private fun <T> capture(future: CompletableFuture<T>): CompletableFuture<T> = future.also { awaited.set(it) }

        override fun persistEvent(event: EventEnvelope<String>): CompletableFuture<Void> = capture(underlying.persistEvent(event))

        override fun persistEventAndSnapshot(
            event: EventEnvelope<String>,
            snapshot: SnapshotEnvelope<String>,
        ): CompletableFuture<Void> = capture(underlying.persistEventAndSnapshot(event, snapshot))

        override fun getLatestSnapshotById(aggregateId: AggregateId): CompletableFuture<Optional<SnapshotReadResult<String>>> =
            capture(underlying.getLatestSnapshotById(aggregateId))

        override fun getEventsByIdSinceSeqNr(
            aggregateId: AggregateId,
            seqNr: Long,
        ): CompletableFuture<List<EventEnvelope<String>>> = capture(underlying.getEventsByIdSinceSeqNr(aggregateId, seqNr))
    }

    private class GatedResponse : FaultRegistry.Effect {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)

        override fun supports(injection: FaultRegistry.Injection): Boolean = injection == FaultRegistry.Injection.REPLACE_RESPONSE

        override fun response(
            request: DynamoDbRequestRecorder.Request,
            response: SdkResponse,
        ): SdkResponse {
            entered.countDown()
            check(released.await(20, TimeUnit.SECONDS)) { "The test must release the actual SDK response" }
            return response
        }
    }

    @Test
    fun cancellationKeepsEachActualRequestAliveUntilItsIndependentTerminalState() =
        runBlocking {
            val observations = DynamoDbJson.`object`()
            val operations =
                listOf(
                    "persistEvent",
                    "persistEventAndSnapshot",
                    "getLatestSnapshotById",
                    "getEventsByIdSinceSeqNr",
                    "ofDynamoDB",
                )
            for (name in operations) {
                val c = fixture.createContext()
                val gate = GatedResponse()
                val observed = observations.putObject(name)
                var operation: FaultRegistry.Operation? = null
                try {
                    DynamoDbConfigurationTables.create(c, RetentionPolicy.none())
                    val tables = DynamoDbConfigurationTables.config(c).build()
                    val sync = operate(c, 0, false) { EventStore.ofDynamoDB(c.client, tables, config) }
                    operate(c, 1, true) { sync.persistEvent(event(1)) }
                    val java = operate(c, 2, false) { DynamoDbEventStore.createAsync(c.async, tables, config).await() }
                    val capturing = CapturingStore(java)
                    val kotlin = EventStoreAsync.fromJava(capturing)
                    val phase =
                        when (name) {
                            "persistEvent", "persistEventAndSnapshot" -> "commit"
                            "getLatestSnapshotById" -> "read-snapshot"
                            "getEventsByIdSinceSeqNr" -> "read-events"
                            else -> "configuration-read"
                        }
                    val fault = c.faults.register(3, phase, 1, FaultRegistry.Injection.REPLACE_RESPONSE, gate)
                    operation = c.faults.begin(3, name.startsWith("persist"))
                    val job =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            when (name) {
                                "persistEvent" -> kotlin.persistEvent(event(2))
                                "persistEventAndSnapshot" -> kotlin.persistEventAndSnapshot(event(2), snapshot(2))
                                "getLatestSnapshotById" -> kotlin.getLatestSnapshotById(id)
                                "getEventsByIdSinceSeqNr" -> kotlin.getEventsByIdSinceSeqNr(id, 1)
                                "ofDynamoDB" -> EventStoreAsync.ofDynamoDB(c.async, tables, config)
                            }
                        }
                    assertTrue(withContext(Dispatchers.IO) { gate.entered.await(10, TimeUnit.SECONDS) })
                    observed.put("pending_before_cancel", c.faults.pending(operation))
                    assertEquals(1, c.faults.pending(operation))
                    assertFalse(job.isCompleted)
                    job.cancelAndJoin()
                    assertFailsWith<CancellationException> { job.await() }
                    observed.put("coroutine_cancelled", job.isCancelled)
                    if (name != "ofDynamoDB") {
                        assertTrue(requireNotNull(capturing.awaited.get()).isCancelled)
                        observed.put("awaited_java_future_cancelled", true)
                    }
                    observed.put("pending_after_cancel", c.faults.pending(operation))
                    assertEquals(1, c.faults.pending(operation))
                    assertFalse(c.closed())
                    gate.released.countDown()
                    c.recorder.requestsFinished(operation).get(30, TimeUnit.SECONDS)
                    assertEquals(0, c.faults.pending(operation))
                    assertEquals(0, c.faults.reservations(fault))
                    assertEquals(1, c.faults.applications(fault))
                    assertEquals("passed", c.faults.finish(operation).status)
                    observed.put("pending_at_terminal", 0).put("reservations_at_terminal", 0)
                    observed.put("request_terminal", true)
                    observed.set<com.fasterxml.jackson.databind.JsonNode>(
                        "requests",
                        DynamoDbConfigurationFixture.requestsJson(c.recorder.requests()),
                    )
                } finally {
                    gate.released.countDown()
                    if (operation != null) c.recorder.requestsFinished(operation).get(30, TimeUnit.SECONDS)
                    c.close()
                    observed.put("resources_closed", c.closed())
                    fixture.releaseClosedContext(c)
                    val directory = Path.of("build/reports/kotlin-public-boundary")
                    Files.createDirectories(directory)
                    DynamoDbJson.write(directory.resolve("cancellation.json"), observations)
                }
            }
        }
}
