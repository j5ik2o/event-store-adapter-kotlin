package com.github.j5ik2o.event.store.adapter.kotlin.internal

import com.github.j5ik2o.event.store.adapter.kotlin.EventStoreAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals
import kotlin.test.junit5.JUnit5Asserter.fail
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@Testcontainers
class EventStoreAsyncForDynamoDBTest {

    companion object {
        val LOGGER: Logger = LoggerFactory.getLogger(EventStoreAsyncForDynamoDBTest::class.java)
        const val JOURNAL_TABLE_NAME = "journal"
        const val SNAPSHOT_TABLE_NAME = "snapshot"
        const val JOURNAL_AID_INDEX_NAME: String = "journal-aid-index"
        const val SNAPSHOT_AID_INDEX_NAME: String = "snapshot-aid-index"
    }

    private val localstackImage: DockerImageName = DockerImageName.parse("localstack/localstack:2.1.0")

    @Container
    private val localstack: LocalStackContainer = LocalStackContainer(localstackImage).withServices(LocalStackContainer.Service.DYNAMODB)

    private fun testTimeFactor(): Float = (System.getenv("TEST_TIME_FACTOR") ?: "1").toFloat()
    private fun timeout() = (10 * testTimeFactor()).toInt().seconds

    @Test
    fun persistAndGet() = runTest(UnconfinedTestDispatcher()) {
        val timeout = timeout()
        LOGGER.info("timeout = {}", timeout)
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

            val eventStore = EventStoreAsync.ofDynamoDB<UserAccountId, UserAccount, UserAccountEvent>(
                client,
                JOURNAL_TABLE_NAME,
                SNAPSHOT_TABLE_NAME,
                JOURNAL_AID_INDEX_NAME,
                SNAPSHOT_AID_INDEX_NAME,
                32,
            )

            val id = UserAccountId(IdGenerator.generate().toString())
            val aggregateAndEvent = UserAccount.create(id, "test-1")

            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(timeout) {
                    eventStore
                        .persistEventAndSnapshot(aggregateAndEvent.second, aggregateAndEvent.first)
                }

                val result = withTimeout(timeout) {
                    eventStore.getLatestSnapshotById(UserAccount::class.java, id)
                }

                if (result != null) {
                    assertEquals(result.first.id, aggregateAndEvent.first.id)
                    LOGGER.info("result = {}", result)
                } else {
                    fail("result is null")
                }
            }
        }
    }
}
