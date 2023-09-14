package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.kotlin.EventStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals

@Testcontainers
class UserAccountRepositorySyncTest {
    companion object {
        const val JOURNAL_TABLE_NAME = "journal"
        const val SNAPSHOT_TABLE_NAME = "snapshot"
        const val JOURNAL_AID_INDEX_NAME: String = "journal-aid-index"
        const val SNAPSHOT_AID_INDEX_NAME: String = "snapshot-aid-index"
    }
    private val localstackImage: DockerImageName = DockerImageName.parse("localstack/localstack:2.1.0")

    @Container
    private val localstack: LocalStackContainer = LocalStackContainer(localstackImage).withServices(LocalStackContainer.Service.DYNAMODB)

    @Test
    fun repositoryStoreAndFindById() = runTest {
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
            val eventStore =
                EventStore.ofDynamoDB<UserAccountId, UserAccount, UserAccountEvent>(
                    client,
                    JOURNAL_TABLE_NAME,
                    SNAPSHOT_TABLE_NAME,
                    JOURNAL_AID_INDEX_NAME,
                    SNAPSHOT_AID_INDEX_NAME,
                    32,
                )
            val userAccountRepository = UserAccountRepositorySync(eventStore)
            val id = UserAccountId(IdGenerator.generate().toString())
            val aggregateAndEvent1 = UserAccount.create(id, "test-1")
            val aggregate1 = aggregateAndEvent1.first

            userAccountRepository.storeEventAndSnapshot(aggregateAndEvent1.second, aggregate1)

            val aggregateAndEvent2 = aggregate1.changeName("test-2")
            userAccountRepository.storeEvent(aggregateAndEvent2.second, aggregateAndEvent2.first.version)
            val result = userAccountRepository.findById(id)

            if (result != null) {
                assertEquals(result.id, aggregateAndEvent1.first.id)
                assertEquals(result.name, "test-2")
            } else {
                Assertions.fail<Any>("result is empty")
            }
        }
    }
}
