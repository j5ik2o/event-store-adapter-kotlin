@file:OptIn(ExperimentalCoroutinesApi::class)

package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@Testcontainers
class UserAccountRepositoryAsyncTest {
    companion object {
        const val JOURNAL_TABLE_NAME = "journal"
        const val SNAPSHOT_TABLE_NAME = "snapshot"
        const val JOURNAL_AID_INDEX_NAME: String = "journal-aid-index"
        const val SNAPSHOT_AID_INDEX_NAME: String = "snapshot-aid-index"
    }
    private val localstackImage: DockerImageName = DockerImageName.parse("localstack/localstack:2.1.0")

    @Container
    private val localstack: LocalStackContainer = LocalStackContainer(localstackImage).withServices(LocalStackContainer.Service.DYNAMODB)

    private val testTimeFactor: Float = (System.getenv("TEST_TIME_FACTOR") ?: "1").toFloat()
    private val timeout = (60 * testTimeFactor).toInt().seconds

    @Test
    fun repositoryStoreAndFindById() = runTest {
        DynamoDBAsyncUtils.createDynamoDbAsyncClient(localstack).use { client ->
            DynamoDBAsyncUtils.createJournalTable(
                client,
                JOURNAL_TABLE_NAME,
                JOURNAL_AID_INDEX_NAME,
            )
                .join()
            DynamoDBAsyncUtils.createSnapshotTable(
                client,
                SNAPSHOT_TABLE_NAME,
                SNAPSHOT_AID_INDEX_NAME,
            )
                .join()
            client.listTables().join().tableNames().forEach(System.out::println)
            val eventStore =
                EventStoreAsync.ofDynamoDB<UserAccountId, UserAccount, UserAccountEvent>(
                    client,
                    JOURNAL_TABLE_NAME,
                    SNAPSHOT_TABLE_NAME,
                    JOURNAL_AID_INDEX_NAME,
                    SNAPSHOT_AID_INDEX_NAME,
                    32,
                )
            val userAccountRepository = UserAccountRepositoryAsync(eventStore)
            val id = UserAccountId(IdGenerator.generate().toString())
            val aggregateAndEvent1 = UserAccount.create(id, "test-1")
            val aggregate1 = aggregateAndEvent1.first

            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(timeout) {
                    userAccountRepository.storeEventAndSnapshot(aggregateAndEvent1.second, aggregate1)
                }

                val aggregateAndEvent2 = aggregate1.changeName("test-2")
                withTimeout(timeout) {
                    userAccountRepository.storeEvent(aggregateAndEvent2.second, aggregateAndEvent2.first.version)
                }
                val result = withTimeout(timeout) { userAccountRepository.findById(id) }

                if (result != null) {
                    assertEquals(result.id, aggregateAndEvent1.first.id)
                    assertEquals(result.name, "test-2")
                } else {
                    Assertions.fail<Any>("result is empty")
                }
            }
        }
    }
}
