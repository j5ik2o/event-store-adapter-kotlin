package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.junit5.JUnit5Asserter.fail

@Testcontainers
class EventStoreSyncForDynamoDBTest {

    companion object {
        val LOGGER: Logger = LoggerFactory.getLogger(EventStoreSyncForDynamoDBTest::class.java)
        const val JOURNAL_TABLE_NAME = "journal"
        const val SNAPSHOT_TABLE_NAME = "snapshot"
        const val JOURNAL_AID_INDEX_NAME: String = "journal-aid-index"
        const val SNAPSHOT_AID_INDEX_NAME: String = "snapshot-aid-index"
    }

    private val localstackImage: DockerImageName = DockerImageName.parse("localstack/localstack:2.1.0")

    @Container
    private val localstack: LocalStackContainer =
        LocalStackContainer(localstackImage).withServices(LocalStackContainer.Service.DYNAMODB)

    @Test
    fun persistAndGet() = runTest {
        DynamoDBSyncUtils.createDynamoDbClient(localstack).use { client ->
            DynamoDBSyncUtils.createJournalTable(
                client,
                JOURNAL_TABLE_NAME,
                JOURNAL_AID_INDEX_NAME,
            )
            DynamoDBSyncUtils.createSnapshotTable(
                client,
                SNAPSHOT_TABLE_NAME,
                SNAPSHOT_AID_INDEX_NAME,
            )
            client.listTables().tableNames().forEach(System.out::println)

            val eventStore = EventStore.ofDynamoDB<UserAccountId, UserAccount, UserAccountEvent>(
                client,
                JOURNAL_TABLE_NAME,
                SNAPSHOT_TABLE_NAME,
                JOURNAL_AID_INDEX_NAME,
                SNAPSHOT_AID_INDEX_NAME,
                32,
            )

            val id = UserAccountId(IdGenerator.generate().toString())
            val aggregateAndEvent = UserAccount.create(id, "test-1")

            eventStore
                .persistEventAndSnapshot(aggregateAndEvent.second, aggregateAndEvent.first)

            val result =
                eventStore.getLatestSnapshotById(UserAccount::class.java, id)

            if (result != null) {
                assertEquals(result.first.id, aggregateAndEvent.first.id)
                LOGGER.info("result = {}", result)
            } else {
                fail("result is null")
            }
        }
    }
}
