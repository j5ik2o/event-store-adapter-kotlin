package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.github.j5ik2o.event.store.adapter.java.dynamodbtest.DynamoDbConfigurationFixture;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;

class DynamoDbSizeBoundaryTest {
  @RegisterExtension
  static final DynamoDbConfigurationFixture dynamodb = new DynamoDbConfigurationFixture();

  @TestFactory
  Stream<DynamicTest> allFourSharedBoundaries() throws IOException {
    Set<String> ids =
        Set.of(
            "dynamodb-item-size-event-under-limit",
            "dynamodb-item-size-event-over-limit",
            "dynamodb-item-size-snapshot-under-limit",
            "dynamodb-item-size-snapshot-over-limit");
    var cases =
        ConformanceDataLoader.load(Path.of("conformance")).cases().stream()
            .filter(c -> ids.contains(c.id()))
            .collect(java.util.stream.Collectors.toList());
    assertEquals(4, cases.size());
    return cases.stream()
        .map(c ->
            dynamicTest(
                c.id(),
                () -> {
                  CaseResult result = DynamoDbCaseRunner.run(c, dynamodb);
                  assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
                }));
  }
}
