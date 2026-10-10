package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class MemoryCaseRunnerTest {
  @Test
  void unexpectedScenarioFailureKeepsPriorOperationsAndFollowingCaseInSavedReport()
      throws IOException {
    ConformanceCase original = find("core-snapshot-behind-head");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/fixtures/events/e2")).put("occurred_at", "not-an-instant");
    CaseResult failed = MemoryCaseRunner.run(copy(original, changed));
    assertEquals(ConformanceStatus.FAILED, failed.status());
    assertEquals(2, failed.failedOperation());
    assertEquals("success", failed.actual().at("/initialization/result").asText());
    assertEquals(1, failed.actual().path("steps").size());
    assertEquals("success", failed.actual().at("/steps/0/result").asText());
    assertEquals(
        "java.time.format.DateTimeParseException",
        failed.actual().at("/execution_failure/exception").asText());
    assertSavedFailureAndFollowingCase(failed, "scenario-runtime");
  }

  @Test
  void unexpectedTimeFailureKeepsCompletedWritesAndFollowingCaseInSavedReport() throws IOException {
    ConformanceCase original =
        ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
            .filter(c -> c.operation().map("validateOccurredAt"::equals).orElse(false))
            .filter(c -> !c.timePrecision().map("milliseconds"::equals).orElse(false))
            .findFirst()
            .orElseThrow();
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.path("input")).put("event_seq_nr", 3).put("iso8601", "not-an-instant");
    CaseResult failed = MemoryCaseRunner.run(copy(original, changed));
    assertEquals(ConformanceStatus.FAILED, failed.status());
    assertEquals(3, failed.failedOperation());
    assertEquals(2, failed.actual().path("steps").size());
    assertEquals("success", failed.actual().at("/steps/1/result").asText());
    assertEquals(
        "java.time.format.DateTimeParseException",
        failed.actual().at("/execution_failure/exception").asText());
    assertSavedFailureAndFollowingCase(failed, "time-runtime");
  }

  @Test
  void unexpectedAssertionInEitherExecutionBranchKeepsCauseAndSavedObservations()
      throws IOException {
    ConformanceCase scenario = find("core-snapshot-behind-head");
    ConformanceCase time =
        ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
            .filter(c -> c.operation().map("validateOccurredAt"::equals).orElse(false))
            .findFirst()
            .orElseThrow();
    for (ConformanceCase input : List.of(scenario, time)) {
      IllegalStateException cause = new IllegalStateException("failure cause");
      CaseResult failed =
          MemoryCaseRunner.run(
              input,
              actual -> {
                actual.put("current_operation", 2);
                actual.putArray("steps").addObject().put("result", "success");
                throw new AssertionError("unexpected execution failure", cause);
              });
      assertEquals(ConformanceStatus.FAILED, failed.status());
      assertEquals(2, failed.failedOperation());
      assertEquals(1, failed.actual().path("steps").size());
      assertEquals(
          AssertionError.class.getName(),
          failed.actual().at("/execution_failure/exception").asText());
      assertEquals(
          cause.getClass().getName(), failed.actual().at("/execution_failure/cause").asText());
      assertEquals(
          cause.getMessage(), failed.actual().at("/execution_failure/cause_message").asText());
      assertSavedFailureAndFollowingCase(
          failed, input.operation().isPresent() ? "time-assertion" : "scenario-assertion");
    }
  }

  @Test
  void unexpectedComparisonFailureKeepsResultAndComparisonPosition() throws IOException {
    ConformanceCase original = find("core-snapshot-behind-head");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/steps/1")).put("expect", "invalid-comparison-object");
    CaseResult failed = MemoryCaseRunner.run(copy(original, changed));
    assertEquals(ConformanceStatus.FAILED, failed.status());
    assertEquals(2, failed.failedOperation());
    assertEquals("/steps/1/expect", failed.actual().path("comparison_position").asText());
    assertTrue(failed.actual().path("steps").size() >= 2);
    assertSavedFailureAndFollowingCase(failed, "comparison-runtime");
  }

  private static void assertSavedFailureAndFollowingCase(CaseResult failed, String name)
      throws IOException {
    ConformanceCase next = find("core-replay-without-snapshot");
    CaseResult following = MemoryCaseRunner.run(next);
    assertEquals(ConformanceStatus.PASSED, following.status(), following.reason());
    ConformanceData data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
    ConformanceReport report =
        new ConformanceReport(
            data.dataVersion(),
            ManifestVerifier.verify(ConformanceTestFiles.REAL_ROOT),
            Files.readString(Path.of("version")).trim(),
            null,
            List.of(failed, following),
            data.coverageExclusions());
    Path directory = Path.of("build/reports/fix-memory-failures", name);
    report.write(directory);
    com.fasterxml.jackson.databind.JsonNode saved =
        ConformanceJson.readTree(Files.readAllBytes(directory.resolve("report.json")), name);
    assertEquals("failed", saved.at("/cases/0/status").asText());
    assertEquals(
        failed.failedOperation().intValue(), saved.at("/cases/0/failed_operation").intValue());
    assertEquals(failed.reason(), saved.at("/cases/0/reason").asText());
    assertEquals(
        ConformanceJson.readTree(ConformanceJson.mapper().writeValueAsBytes(failed.actual()), name),
        saved.at("/cases/0/actual"));
    assertEquals("passed", saved.at("/cases/1/status").asText());
    assertEquals(next.id(), saved.at("/cases/1/case_id").asText());
    assertTrue(saved.path("rules").size() >= failed.rules().size() + following.rules().size());
  }

  @TestFactory
  Stream<DynamicTest> commonMemoryScenariosExecuteIncludingFaultsHistoryAndNotifications()
      throws IOException {
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> c.file().startsWith("scenarios/core/"))
        .filter(c -> !c.timePrecision().map("milliseconds"::equals).orElse(false))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      CaseResult result = CaseClassifier.classify(c, Backend.MEMORY);
                      assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
                    }));
  }

  @Test
  void incorrectReadExpectationCannotBeUsedToGenerateStoredState() throws IOException {
    ConformanceCase original = find("core-replay-without-snapshot");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/steps/4/expect")).put("head_seq_nr", 7);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void incorrectHistoryExpectationIsRejected() throws IOException {
    ConformanceCase original = find("core-retention-delete-1");
    ObjectNode changed = original.materialized().deepCopy();
    ((ArrayNode) changed.at("/steps/1/observe/history/active")).removeAll().add(1);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void historyDeclaredAbsentMustNotRemainActive() throws IOException {
    ConformanceCase original = find("core-retention-failure-after-commit");
    ObjectNode changed = original.materialized().deepCopy();
    ((ArrayNode) changed.at("/steps/1/observe/history/absent")).add(1);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void emptyExpectedNotificationsCannotHideRetentionFailure() throws IOException {
    ConformanceCase original = find("core-retention-failure-after-commit");
    ObjectNode changed = original.materialized().deepCopy();
    ((ArrayNode) changed.at("/steps/1/observe/notifications")).removeAll();

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void registeredFaultThatNeverFiresCannotPass() throws IOException {
    ConformanceCase original = find("core-storage-commit-failure");
    ObjectNode changed = original.materialized().deepCopy();
    // The second operation is a read, so this commit fault cannot fire.
    ((ObjectNode) changed.at("/faults/0")).put("operation", 2);
    ObjectNode expectation = (ObjectNode) changed.at("/steps/0/expect");
    expectation.remove("error");
    expectation.put("result", "success");
    ((ObjectNode) changed.at("/steps/1/expect"))
        .put("result", "snapshot")
        .put("head_seq_nr", 1)
        .put("snapshot", "s1");
    ((ArrayNode) changed.at("/steps/2/expect/events")).add("e1");

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
    assertEquals(2, result.failedOperation());
  }

  @TestFactory
  Stream<DynamicTest> nanosecondTimeCasesExecuteOnMemoryAndRemainUnverifiedOnDynamoDb()
      throws IOException {
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> c.operation().map("validateOccurredAt"::equals).orElse(false))
        .filter(c -> !c.timePrecision().map("milliseconds"::equals).orElse(false))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      CaseResult memory = CaseClassifier.classify(c, Backend.MEMORY);
                      assertEquals(ConformanceStatus.PASSED, memory.status(), memory.reason());
                      assertEquals(
                          ConformanceStatus.UNVERIFIED,
                          CaseClassifier.classify(c, Backend.DYNAMODB).status());
                    }));
  }

  @Test
  void finiteFaultCountMustEqualActualApplications() throws IOException {
    ConformanceCase original = find("core-storage-commit-failure");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/faults/0/repeat")).put("count", 2);
    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);
    assertEquals(ConformanceStatus.FAILED, result.status());
    assertEquals(1, result.failedOperation());
    assertEquals(1, result.actual().at("/faults/0/applications").intValue());
  }

  @TestFactory
  Stream<DynamicTest> unconnectedObservationsAndFaultDetailsRemainUnverified() {
    return Stream.of("requests", "unknown-history", "unknown-fault-detail")
        .map(
            field ->
                dynamicTest(
                    field,
                    () -> {
                      ConformanceCase original = find("core-retention-failure-after-commit");
                      ObjectNode changed = original.materialized().deepCopy();
                      if (field.equals("requests"))
                        ((ObjectNode) changed.at("/steps/1/observe")).putArray(field);
                      else if (field.equals("unknown-history"))
                        ((ObjectNode) changed.at("/steps/1/observe/history")).putArray(field);
                      else ((ObjectNode) changed.at("/faults/0/details")).put(field, true);
                      CaseResult result =
                          CaseClassifier.classify(copy(original, changed), Backend.MEMORY);
                      assertEquals(ConformanceStatus.UNVERIFIED, result.status());
                      org.junit.jupiter.api.Assertions.assertFalse(
                          result.actual().has("initialization"));
                    }));
  }

  private static ConformanceCase find(String id) throws IOException {
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> c.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError(id));
  }

  private static ConformanceCase copy(ConformanceCase original, ObjectNode changed) {
    return new ConformanceCase(
        original.id(),
        original.file(),
        original.format(),
        original.rules(),
        changed.deepCopy(),
        changed);
  }
}
