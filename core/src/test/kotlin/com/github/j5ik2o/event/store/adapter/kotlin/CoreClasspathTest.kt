package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CoreClasspathTest {
    @Test
    fun memoryInitializesWithoutAnyDriver() {
        runBlocking {
            assertFailsWith<ClassNotFoundException> { Class.forName("software.amazon.awssdk.services.dynamodb.DynamoDbClient") }
            val config =
                EventStoreConfig
                    .builder<String, String>()
                    .payloadSerializer(JsonPayloadSerializer.of(String::class.java))
                    .snapshotSerializer(JsonPayloadSerializer.of(String::class.java))
                    .build()
            assertNotNull(EventStore.ofMemory(MemoryStorage.create(), config))
            assertNotNull(EventStoreAsync.ofMemory(MemoryStorage.create(), config))
        }
    }
}
