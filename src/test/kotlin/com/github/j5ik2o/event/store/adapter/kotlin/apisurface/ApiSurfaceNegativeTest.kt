package com.github.j5ik2o.event.store.adapter.kotlin.apisurface

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import com.github.j5ik2o.event.store.adapter.kotlin.apisurface.fixtures.eventsreturnpayloads.EventStore as EventsReturnPayloads
import com.github.j5ik2o.event.store.adapter.kotlin.apisurface.fixtures.missingpersistandsnapshot.EventStore as MissingPersistAndSnapshot
import com.github.j5ik2o.event.store.adapter.kotlin.apisurface.fixtures.narrowedtypeparameter.EventStore as NarrowedTypeParameter
import com.github.j5ik2o.event.store.adapter.kotlin.apisurface.fixtures.nonnullsnapshot.EventStore as NonNullSnapshot
import com.github.j5ik2o.event.store.adapter.kotlin.apisurface.fixtures.nonsuspendasync.EventStoreAsync as NonSuspendAsync

class ApiSurfaceNegativeTest {
    private val envelope = "com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope"
    private val snapshotRead = "com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult"

    private fun check(
        name: String,
        replacement: KClass<*>,
    ): List<String> = ApiSurfaceChecker(ApiSurfaceFiles.surface(), ApiSurfaceFiles.mapping(), mapOf(name to replacement)).check()

    @Test
    fun `events returning payloads is reported`() {
        assertEquals(
            listOf(
                "get_events_by_id_since_seq_nr: EventStore.getEventsByIdSinceSeqNr " +
                    "returns kotlin.collections.List<P>, want kotlin.collections.List<$envelope<P>>",
            ),
            check("EventStore", EventsReturnPayloads::class),
        )
    }

    @Test
    fun `non-null snapshot result is reported`() {
        assertEquals(
            listOf(
                "get_latest_snapshot_by_id: EventStore.getLatestSnapshotById " +
                    "returns $snapshotRead<A>, want $snapshotRead<A>?",
            ),
            check("EventStore", NonNullSnapshot::class),
        )
    }

    @Test
    fun `async operation without suspend is reported`() {
        assertEquals(
            listOf("persist_event: EventStoreAsync.persistEvent is not suspend, want suspend"),
            check("EventStoreAsync", NonSuspendAsync::class),
        )
    }

    @Test
    fun `missing persistEventAndSnapshot is reported`() {
        assertEquals(
            listOf("persist_event_and_snapshot: EventStore has no function persistEventAndSnapshot"),
            check("EventStore", MissingPersistAndSnapshot::class),
        )
    }

    @Test
    fun `bounded type parameter is reported`() {
        assertEquals(
            listOf("event_store: type parameter P of EventStore is bounded by [kotlin.Comparable<P>]; want no bound"),
            check("EventStore", NarrowedTypeParameter::class),
        )
    }

    @Test
    fun `missing suspending store mapping is reported`() {
        val mapping = ApiSurfaceFiles.mapping() as ObjectNode
        val stores = mapping.path("stores") as ArrayNode
        for (i in stores.size() - 1 downTo 0) {
            if (stores.get(i).path("suspend").asBoolean(false)) stores.remove(i)
        }
        assertEquals(
            listOf("mapping: stores has no suspending store"),
            ApiSurfaceChecker(ApiSurfaceFiles.surface(), mapping).check(),
        )
    }

    @Test
    fun `missing base exception mapping is reported`() {
        val mapping = ApiSurfaceFiles.mapping() as ObjectNode
        (mapping.path("error_categories") as ObjectNode).remove("base")
        assertEquals(
            listOf("mapping: error_categories.base is missing"),
            ApiSurfaceChecker(ApiSurfaceFiles.surface(), mapping).check(),
        )
    }
}
