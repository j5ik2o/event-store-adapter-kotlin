package com.github.j5ik2o.event.store.adapter.kotlin.internal

import kotlinx.coroutines.test.runTest
import com.github.j5ik2o.event.store.adapter.java.internal.EventStoreAsyncForDynamoDB as JavaEventStoreAsyncForDynamoDB
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals

@Testcontainers
class EventStoreAsyncForDynamoDBTest {
    private val LOGGER = LoggerFactory.getLogger(EventStoreAsyncForDynamoDBTest::class.java)

    private val JOURNAL_TABLE_NAME = "journal"
    private val SNAPSHOT_TABLE_NAME = "snapshot"

    private val JOURNAL_AID_INDEX_NAME = "journal-aid-index"
    private val SNAPSHOT_AID_INDEX_NAME = "snapshot-aid-index"

    val localstackImage = DockerImageName.parse("localstack/localstack:2.1.0")

    @Container
    val localstack = LocalStackContainer(localstackImage).withServices(LocalStackContainer.Service.DYNAMODB)

    @Test
    fun test_persist_and_get() = runTest {
        DynamoDBAsyncUtils.createDynamoDbAsyncClient(localstack).use { client ->
            DynamoDBAsyncUtils.createJournalTable(
                client,
                JOURNAL_TABLE_NAME,
                JOURNAL_AID_INDEX_NAME
            )
                .join()
            DynamoDBAsyncUtils.createSnapshotTable(
                client,
                SNAPSHOT_TABLE_NAME,
                SNAPSHOT_AID_INDEX_NAME
            )
                .join()
            client.listTables().join().tableNames().forEach(System.out::println)
            val javaEventStore =
                JavaEventStoreAsyncForDynamoDB.create<UserAccountId, UserAccount, UserAccountEvent>(
                    client,
                    JOURNAL_TABLE_NAME,
                    SNAPSHOT_TABLE_NAME,
                    JOURNAL_AID_INDEX_NAME,
                    SNAPSHOT_AID_INDEX_NAME,
                    32
                )
            val eventStore = EventStoreAsyncForDynamoDB(javaEventStore)

            val id = UserAccountId(IdGenerator.generate().toString())
            val aggregateAndEvent = UserAccount.create(id, "test-1")
            eventStore
                .persistEventAndSnapshot(aggregateAndEvent.second, aggregateAndEvent.first)
            val result =
                eventStore.getLatestSnapshotById(UserAccount::class.java, id)
            if (result != null) {
                assertEquals(result.aggregate.id, aggregateAndEvent.first.id)
                LOGGER.info("result = {}", result)
            } else {
                Assertions.fail<Any>("result is empty")
            }
        }
    }

}