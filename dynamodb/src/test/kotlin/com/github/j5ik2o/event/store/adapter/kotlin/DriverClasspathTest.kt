package com.github.j5ik2o.event.store.adapter.kotlin

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.interceptor.Context
import software.amazon.awssdk.core.interceptor.ExecutionAttributes
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.Projection
import software.amazon.awssdk.services.dynamodb.model.ProjectionType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification
import software.amazon.awssdk.services.dynamodb.model.StreamViewType
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class DriverClasspathTest {
    private class Local(
        image: String,
    ) : GenericContainer<Local>(image)

    @Test
    fun coreAndDynamoDbAloneInitializeAndReadWrite() {
        for (name in listOf(
            "com.google.cloud.spanner.Spanner",
            "com.google.cloud.bigtable.data.v2.BigtableDataClient",
            "org.sqlite.JDBC",
        )) {
            assertFailsWith<ClassNotFoundException> { Class.forName(name) }
        }
        val config =
            EventStoreConfig
                .builder<String, String>()
                .payloadSerializer(JsonPayloadSerializer.of(String::class.java))
                .snapshotSerializer(JsonPayloadSerializer.of(String::class.java))
                .build()
        val id = AggregateId.of("Order", "classpath")

        fun event(seq: Long) =
            EventEnvelope
                .builder<String>()
                .aggregateId(id)
                .seqNr(seq)
                .occurredAt(Instant.parse("2026-10-10T00:00:00Z"))
                .manifest("m")
                .payload("payload-$seq")
                .build()
        assertNotNull(EventStore.ofMemory(MemoryStorage.create(), config))
        runBlocking { assertNotNull(EventStoreAsync.ofMemory(MemoryStorage.create(), config)) }
        Local("amazon/dynamodb-local@sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab")
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory")
            .withCreateContainerCmdModifier { it.withName("esa-kotlin-java520-classpath-${java.util.UUID.randomUUID()}") }
            .use { local ->
                local.start()
                val commits = AtomicInteger()
                val recorder =
                    object : ExecutionInterceptor {
                        override fun beforeExecution(
                            context: Context.BeforeExecution,
                            executionAttributes: ExecutionAttributes,
                        ) {
                            if (context.request() is TransactWriteItemsRequest) commits.incrementAndGet()
                        }
                    }
                DynamoDbClient
                    .builder()
                    .endpointOverride(URI("http://${local.host}:${local.getMappedPort(8000)}"))
                    .region(
                        Region.US_EAST_1,
                    ).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                    .overrideConfiguration { it.addExecutionInterceptor(recorder) }
                    .httpClientBuilder(UrlConnectionHttpClient.builder())
                    .build()
                    .use { client ->
                        for ((name, sort) in listOf("journal" to "seq_nr", "snapshot" to "skey", "head" to null)) {
                            val attrs =
                                mutableListOf(
                                    AttributeDefinition
                                        .builder()
                                        .attributeName("aid")
                                        .attributeType(ScalarAttributeType.S)
                                        .build(),
                                )
                            val keys =
                                mutableListOf(
                                    KeySchemaElement
                                        .builder()
                                        .attributeName("aid")
                                        .keyType(KeyType.HASH)
                                        .build(),
                                )
                            if (sort != null) {
                                attrs.add(
                                    AttributeDefinition
                                        .builder()
                                        .attributeName(sort)
                                        .attributeType(ScalarAttributeType.N)
                                        .build(),
                                )
                                keys.add(
                                    KeySchemaElement
                                        .builder()
                                        .attributeName(sort)
                                        .keyType(KeyType.RANGE)
                                        .build(),
                                )
                            }
                            val request =
                                CreateTableRequest
                                    .builder()
                                    .tableName(
                                        name,
                                    ).billingMode(BillingMode.PAY_PER_REQUEST)
                                    .attributeDefinitions(attrs)
                                    .keySchema(keys)
                            if (name == "snapshot") {
                                attrs.add(
                                    AttributeDefinition
                                        .builder()
                                        .attributeName(
                                            "active_history_seq_nr",
                                        ).attributeType(ScalarAttributeType.N)
                                        .build(),
                                )
                                request.attributeDefinitions(attrs).globalSecondaryIndexes(
                                    GlobalSecondaryIndex
                                        .builder()
                                        .indexName("history")
                                        .keySchema(
                                            KeySchemaElement
                                                .builder()
                                                .attributeName("aid")
                                                .keyType(KeyType.HASH)
                                                .build(),
                                            KeySchemaElement
                                                .builder()
                                                .attributeName("active_history_seq_nr")
                                                .keyType(KeyType.RANGE)
                                                .build(),
                                        ).projection(Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                                        .build(),
                                )
                            }
                            if (name ==
                                "head"
                            ) {
                                request.streamSpecification(
                                    StreamSpecification
                                        .builder()
                                        .streamEnabled(true)
                                        .streamViewType(StreamViewType.NEW_IMAGE)
                                        .build(),
                                )
                            }
                            client.createTable(request.build())
                        }
                        val tables =
                            DynamoDbTableConfig
                                .builder()
                                .journalTableName(
                                    "journal",
                                ).snapshotTableName("snapshot")
                                .headTableName("head")
                                .snapshotAidIndexName("history")
                                .build()
                        val legacy = EventStore.ofDynamoDB(client, tables, config)
                        legacy.persistEvent(event(1))
                        val store = DynamoDbEventStores.of(client, tables, config)
                        store.persistEvent(event(2))
                        assertEquals(listOf("payload-1", "payload-2"), store.getEventsByIdSinceSeqNr(id, 1).map { it.payload() })
                        assertEquals(2, store.getLatestSnapshotById(id)!!.headSeqNr())
                        val raw =
                            object : PayloadSerializer<ByteArray> {
                                override fun serialize(value: ByteArray): ByteArray = value

                                override fun deserialize(value: ByteArray): ByteArray = value
                            }
                        val rawConfig =
                            EventStoreConfig
                                .builder<ByteArray, ByteArray>()
                                .payloadSerializer(
                                    raw,
                                ).snapshotSerializer(raw)
                                .build()
                        val oversized =
                            EventEnvelope
                                .builder<ByteArray>()
                                .aggregateId(AggregateId.of("Order", "9"))
                                .seqNr(1)
                                .occurredAt(Instant.ofEpochSecond(1762750000))
                                .manifest("m")
                                .payload(ByteArray(409512))
                                .build()
                        val before = commits.get()
                        val violation =
                            assertFailsWith<ContractViolationException> {
                                DynamoDbEventStores.of(client, tables, rawConfig).persistEvent(oversized)
                            }
                        assertEquals("D-7", violation.rule())
                        assertEquals(before, commits.get())
                        DynamoDbAsyncClient
                            .builder()
                            .endpointOverride(URI("http://${local.host}:${local.getMappedPort(8000)}"))
                            .region(
                                Region.US_EAST_1,
                            ).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                            .overrideConfiguration { it.addExecutionInterceptor(recorder) }
                            .build()
                            .use { asyncClient ->
                                runBlocking {
                                    val legacyAsync = EventStoreAsync.ofDynamoDB(asyncClient, tables, config)
                                    legacyAsync.persistEvent(event(3))
                                    val asyncStore = DynamoDbEventStores.ofAsync(asyncClient, tables, config)
                                    asyncStore.persistEvent(event(4))
                                    assertEquals(4, asyncStore.getEventsByIdSinceSeqNr(id, 1).size)
                                    val asyncBefore = commits.get()
                                    val asyncViolation =
                                        assertFailsWith<ContractViolationException> {
                                            DynamoDbEventStores.ofAsync(asyncClient, tables, rawConfig).persistEvent(oversized)
                                        }
                                    assertEquals("D-7", asyncViolation.rule())
                                    assertEquals(asyncBefore, commits.get())
                                }
                            }
                    }
            }
    }
}
