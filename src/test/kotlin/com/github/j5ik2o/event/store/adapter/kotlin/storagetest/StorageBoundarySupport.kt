package com.github.j5ik2o.event.store.adapter.kotlin.storagetest

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailure
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailureListener
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope
import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared envelopes and assertions for the real-backend tests that call the Kotlin public entry points. */
object StorageBoundarySupport {
    val id: AggregateId = AggregateId.of("Account", "kotlin-boundary")

    fun config(listener: RetentionFailureListener? = null): EventStoreConfig<String, String> {
        val builder =
            EventStoreConfig
                .builder<String, String>()
                .payloadSerializer(JsonPayloadSerializer.of(String::class.java))
                .snapshotSerializer(JsonPayloadSerializer.of(String::class.java))
        if (listener != null) builder.retentionFailureListener(listener)
        return builder.build()
    }

    fun event(seqNr: Long): EventEnvelope<String> =
        EventEnvelope
            .builder<String>()
            .aggregateId(id)
            .seqNr(seqNr)
            .occurredAt(Instant.EPOCH)
            .manifest("event")
            .payload("event-$seqNr")
            .build()

    fun snapshot(seqNr: Long): SnapshotEnvelope<String> =
        SnapshotEnvelope
            .builder<String>()
            .seqNr(seqNr)
            .manifest("snapshot")
            .aggregate("snapshot-$seqNr")
            .build()

    /** Absent head, head only, snapshot plus tail, ranged event read, then a duplicate append. */
    fun writeAndReadSync(store: EventStore<String, String>) {
        assertNull(store.getLatestSnapshotById(id))
        assertEquals(Unit, store.persistEvent(event(1)))
        val headOnly = requireNotNull(store.getLatestSnapshotById(id))
        assertEquals(1L, headOnly.headSeqNr())
        assertTrue(headOnly.snapshot().isEmpty)
        assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))
        store.persistEvent(event(3))
        assertRestoredSync(store)
        assertFailsWith<OptimisticLockException> { store.persistEvent(event(3)) }
        assertFailsWith<OptimisticLockException> { store.persistEventAndSnapshot(event(3), snapshot(3)) }
        assertRestoredSync(store)
    }

    fun assertRestoredSync(store: EventStore<String, String>) {
        val read = requireNotNull(store.getLatestSnapshotById(id))
        assertEquals(3L, read.headSeqNr())
        val restored = read.snapshot().orElseThrow()
        assertEquals(2L, restored.seqNr())
        assertEquals("snapshot", restored.manifest())
        assertEquals("snapshot-2", restored.aggregate())
        val events = store.getEventsByIdSinceSeqNr(id, 2)
        assertEquals(listOf(2L, 3L), events.map { it.seqNr() })
        assertEquals(listOf("event-2", "event-3"), events.map { it.payload() })
        assertEquals(listOf(Instant.EPOCH, Instant.EPOCH), events.map { it.occurredAt() })
    }

    suspend fun writeAndReadAsync(store: EventStoreAsync<String, String>) {
        assertNull(store.getLatestSnapshotById(id))
        assertEquals(Unit, store.persistEvent(event(1)))
        val headOnly = requireNotNull(store.getLatestSnapshotById(id))
        assertEquals(1L, headOnly.headSeqNr())
        assertTrue(headOnly.snapshot().isEmpty)
        assertEquals(Unit, store.persistEventAndSnapshot(event(2), snapshot(2)))
        store.persistEvent(event(3))
        assertRestoredAsync(store)
        assertFailsWith<OptimisticLockException> { store.persistEvent(event(3)) }
        assertFailsWith<OptimisticLockException> { store.persistEventAndSnapshot(event(3), snapshot(3)) }
        assertRestoredAsync(store)
    }

    suspend fun assertRestoredAsync(store: EventStoreAsync<String, String>) {
        val read = requireNotNull(store.getLatestSnapshotById(id))
        assertEquals(3L, read.headSeqNr())
        val restored = read.snapshot().orElseThrow()
        assertEquals(2L, restored.seqNr())
        assertEquals("snapshot", restored.manifest())
        assertEquals("snapshot-2", restored.aggregate())
        val events = store.getEventsByIdSinceSeqNr(id, 2)
        assertEquals(listOf(2L, 3L), events.map { it.seqNr() })
        assertEquals(listOf("event-2", "event-3"), events.map { it.payload() })
    }

    /** A listener that always throws an Error and counts how often the failure path reached it. */
    class ThrowingListener : RetentionFailureListener {
        var calls = 0
            private set

        override fun onRetentionFailure(failure: RetentionFailure) {
            calls++
            throw AssertionError("listener failed")
        }
    }

    /** Overrides doAppend so the Error reaches the logging caller unchanged. */
    class ThrowingAppender : AppenderBase<ILoggingEvent>() {
        var warnCalls = 0
            private set

        override fun doAppend(event: ILoggingEvent) {
            if (event.level == Level.WARN) {
                warnCalls++
                throw AssertionError("log failed")
            }
        }

        override fun append(eventObject: ILoggingEvent) {}
    }

    /** Attaches a throwing WARN appender to the Java store's logger for the duration of [block]. */
    fun <T> withThrowingWarnLog(
        owner: Class<*>,
        block: (ThrowingAppender) -> T,
    ): T {
        val logger = LoggerFactory.getLogger(owner) as Logger
        val appender = ThrowingAppender()
        appender.context = logger.loggerContext
        appender.start()
        logger.addAppender(appender)
        try {
            return block(appender)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
