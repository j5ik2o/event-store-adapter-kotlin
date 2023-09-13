package com.github.j5ik2o.event.store.adapter.kotlin.internal

import org.testcontainers.containers.localstack.LocalStackContainer
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest
import software.amazon.awssdk.services.dynamodb.model.CreateTableResponse
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement
import software.amazon.awssdk.services.dynamodb.model.ProjectionType
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest
import java.util.concurrent.CompletableFuture


object DynamoDBAsyncUtils {

    fun createDynamoDbAsyncClient(localstack: LocalStackContainer): DynamoDbAsyncClient {
        return DynamoDbAsyncClient.builder()
            .endpointOverride(localstack.endpoint)
            .credentialsProvider(
                software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey)
                )
            )
            .region(software.amazon.awssdk.regions.Region.of(localstack.region))
            .build()
    }

    fun createSnapshotTable(
        client: DynamoDbAsyncClient, tableName: String?, indexName: String?
    ): CompletableFuture<Void> {
        val pt: ProvisionedThroughput =
            ProvisionedThroughput.builder().readCapacityUnits(10L).writeCapacityUnits(5L).build()
        val response: CompletableFuture<CreateTableResponse> = client.createTable(
            CreateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(
                    software.amazon.awssdk.services.dynamodb.model.AttributeDefinition.builder()
                        .attributeName("pkey")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                    software.amazon.awssdk.services.dynamodb.model.AttributeDefinition.builder()
                        .attributeName("skey")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                    software.amazon.awssdk.services.dynamodb.model.AttributeDefinition.builder()
                        .attributeName("aid")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                    software.amazon.awssdk.services.dynamodb.model.AttributeDefinition.builder()
                        .attributeName("seq_nr")
                        .attributeType(ScalarAttributeType.N)
                        .build()
                )
                .keySchema(
                    KeySchemaElement.builder().attributeName("pkey")
                        .keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName("skey")
                        .keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.RANGE).build()
                )
                .globalSecondaryIndexes(
                    GlobalSecondaryIndex.builder()
                        .indexName(indexName)
                        .keySchema(
                            KeySchemaElement.builder()
                                .attributeName("aid")
                                .keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.HASH)
                                .build(),
                            KeySchemaElement.builder()
                                .attributeName("seq_nr")
                                .keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.RANGE)
                                .build()
                        )
                        .projection(
                            software.amazon.awssdk.services.dynamodb.model.Projection.builder()
                                .projectionType(ProjectionType.ALL).build()
                        )
                        .provisionedThroughput(pt)
                        .build()
                )
                .provisionedThroughput(pt)
                .build()
        )
        return response
            .thenCompose {
                client.updateTimeToLive(
                    UpdateTimeToLiveRequest.builder()
                        .tableName(tableName)
                        .timeToLiveSpecification(
                            TimeToLiveSpecification.builder()
                                .enabled(true)
                                .attributeName("ttl")
                                .build()
                        )
                        .build()
                )
            }
            .thenRun {}
    }

    fun createJournalTable(
        client: DynamoDbAsyncClient, tableName: String?, indexName: String?
    ): CompletableFuture<Void> {
        val pt: ProvisionedThroughput =
            ProvisionedThroughput.builder().readCapacityUnits(10L).writeCapacityUnits(5L).build()
        return client
            .createTable(
                CreateTableRequest.builder()
                    .tableName(tableName)
                    .attributeDefinitions(
                        software.amazon.awssdk.services.dynamodb.model.AttributeDefinition.builder()
                            .attributeName("pkey")
                            .attributeType(ScalarAttributeType.S)
                            .build(),
                        software.amazon.awssdk.services.dynamodb.model.AttributeDefinition.builder()
                            .attributeName("skey")
                            .attributeType(ScalarAttributeType.S)
                            .build(),
                        software.amazon.awssdk.services.dynamodb.model.AttributeDefinition.builder()
                            .attributeName("aid")
                            .attributeType(ScalarAttributeType.S)
                            .build(),
                        software.amazon.awssdk.services.dynamodb.model.AttributeDefinition.builder()
                            .attributeName("seq_nr")
                            .attributeType(ScalarAttributeType.N)
                            .build()
                    )
                    .keySchema(
                        KeySchemaElement.builder().attributeName("pkey")
                            .keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("skey")
                            .keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.RANGE).build()
                    )
                    .globalSecondaryIndexes(
                        GlobalSecondaryIndex.builder()
                            .indexName(indexName)
                            .keySchema(
                                KeySchemaElement.builder()
                                    .attributeName("aid")
                                    .keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.HASH)
                                    .build(),
                                KeySchemaElement.builder()
                                    .attributeName("seq_nr")
                                    .keyType(software.amazon.awssdk.services.dynamodb.model.KeyType.RANGE)
                                    .build()
                            )
                            .projection(
                                software.amazon.awssdk.services.dynamodb.model.Projection.builder()
                                    .projectionType(ProjectionType.ALL).build()
                            )
                            .provisionedThroughput(pt)
                            .build()
                    )
                    .provisionedThroughput(pt)
                    .build()
            )
            .thenRun {}
    }
}

