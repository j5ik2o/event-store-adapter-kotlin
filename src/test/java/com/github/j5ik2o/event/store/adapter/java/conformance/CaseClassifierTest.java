package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CaseClassifierTest {

  private static ConformanceData data;

  @BeforeAll
  static void load() throws IOException {
    data = ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT);
  }

  private static final Set<String> EXECUTABLE_CASE_IDS =
      Set.of(
          "aid-library-format",
          "aid-hyphen-value",
          "aid-hyphen-type",
          "aid-ascii-1023",
          "aid-ascii-1024",
          "aid-ascii-1025",
          "aid-multibyte-1023",
          "aid-multibyte-1024",
          "aid-multibyte-1025",
          "seq-zero-value",
          "seq-max-value",
          "seq-one-event",
          "seq-zero-event",
          "seq-negative-value",
          "seq-above-max-value");

  private static ConformanceCase find(String id) {
    return data.cases().stream()
        .filter(c -> c.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("case not found: " + id));
  }

  @Test
  void implementedMemoryCasesAndExistingCoreCasesPassWithoutPromotingDynamoDbCases() {
    for (ConformanceCase c : data.cases()) {
      for (Backend b : Backend.values()) {
        CaseResult r = CaseClassifier.classify(c, b);
        if (EXECUTABLE_CASE_IDS.contains(c.id()) || (b == Backend.MEMORY && memoryExecutable(c))) {
          assertEquals(ConformanceStatus.PASSED, r.status(), c.id() + "/" + b);
        } else if (b == Backend.MEMORY && c.changeFeed() && !isUnprovidedBackendCase(c)) {
          assertEquals(ConformanceStatus.UNREPRESENTABLE, r.status(), c.id() + "/" + b);
          assertFalse(r.reason() == null || r.reason().isBlank(), c.id() + "/" + b);
        } else {
          assertTrue(
              r.status() == ConformanceStatus.UNVERIFIED
                  || r.status() == ConformanceStatus.NOT_APPLICABLE,
              c.id() + "/" + b + ": " + r.status());
          assertFalse(r.reason() == null || r.reason().isBlank(), c.id() + "/" + b);
        }
      }
    }
  }

  @Test
  void fnv1a64CasesAreNotApplicableWithAHashReasonOnBothBackends() {
    for (int i = 1; i <= 4; i++) {
      for (Backend b : Backend.values()) {
        CaseResult r = CaseClassifier.classify(find("hash-fnv1a64-" + i), b);
        assertEquals(ConformanceStatus.NOT_APPLICABLE, r.status());
        assertTrue(r.reason().contains("ハッシュ"), r.reason());
      }
    }
  }

  private static boolean isUnprovidedBackendCase(ConformanceCase c) {
    return c.file().startsWith("sqlite/")
        || c.file().startsWith("spanner/")
        || c.file().startsWith("bigtable/")
        || c.file().equals("scenarios/stores/shared.json");
  }

  private static boolean isBigtableEnvelopeCase(ConformanceCase c) {
    return c.file().equals("values/bigtable-envelope.json");
  }

  @Test
  void bigtableEnvelopeValueCasesAreNotApplicableWithAReasonOnBothBackends() {
    List<ConformanceCase> envelope =
        data.cases().stream()
            .filter(CaseClassifierTest::isBigtableEnvelopeCase)
            .collect(Collectors.toList());
    assertEquals(10, envelope.size());
    for (ConformanceCase c : envelope) {
      for (Backend b : Backend.values()) {
        CaseResult r = CaseClassifier.classify(c, b);
        assertEquals(ConformanceStatus.NOT_APPLICABLE, r.status(), c.id() + "/" + b);
        assertTrue(r.reason().contains("Bigtable"), c.id() + "/" + b + ": " + r.reason());
        assertTrue(r.reason().contains("提供しない"), c.id() + "/" + b + ": " + r.reason());
      }
    }
  }

  @Test
  void casesOfUnprovidedBackendsAreNotApplicableWithAReasonAndNeverRequired() throws IOException {
    List<ConformanceCase> unprovided =
        data.cases().stream()
            .filter(CaseClassifierTest::isUnprovidedBackendCase)
            .collect(Collectors.toList());
    assertFalse(unprovided.isEmpty());
    Map<Backend, Set<String>> required = RequiredCases.load();
    for (ConformanceCase c : unprovided) {
      for (Backend b : Backend.values()) {
        CaseResult r = CaseClassifier.classify(c, b);
        assertEquals(ConformanceStatus.NOT_APPLICABLE, r.status(), c.id() + "/" + b);
        assertTrue(r.reason().contains("提供しない"), c.id() + "/" + b + ": " + r.reason());
        assertFalse(required.get(b).contains(c.id()), c.id() + "/" + b);
        assertFalse(CaseClassifier.violatesRequirement(r, required), c.id() + "/" + b);
      }
    }
  }

  @Test
  void sharedConfigurationCaseIsPassedOnMemoryAndRequiredOnlyThere() throws IOException {
    ConformanceCase c = find("memory-store-shared-configuration");
    Map<Backend, Set<String>> required = RequiredCases.load();

    assertEquals(ConformanceStatus.PASSED, CaseClassifier.classify(c, Backend.MEMORY).status());
    assertEquals(
        ConformanceStatus.NOT_APPLICABLE, CaseClassifier.classify(c, Backend.DYNAMODB).status());
    assertTrue(required.get(Backend.MEMORY).contains(c.id()));
    assertFalse(required.get(Backend.DYNAMODB).contains(c.id()));
  }

  @Test
  void reportShowsUnprovidedBackendRulesAsNotApplicableAndMem2WithTheSharedConfigurationCase()
      throws IOException {
    List<CaseResult> results = new java.util.ArrayList<>();
    for (ConformanceCase c : data.cases()) {
      if (isUnprovidedBackendCase(c)
          || isBigtableEnvelopeCase(c)
          || c.id().equals("memory-store-shared-configuration")) {
        for (Backend b : Backend.values()) {
          results.add(CaseClassifier.classify(c, b));
        }
      }
    }
    ConformanceReport report =
        new ConformanceReport(
            data.dataVersion(),
            ManifestVerifier.verify(ConformanceTestFiles.REAL_ROOT),
            "test",
            null,
            results,
            data.coverageExclusions());

    com.fasterxml.jackson.databind.JsonNode rules = report.toJson().path("rules");
    Set<String> unprovidedPrefixes = new java.util.TreeSet<>();
    boolean sawBtD6a = false;
    boolean sawMem2Passed = false;
    for (com.fasterxml.jackson.databind.JsonNode row : rules) {
      String rule = row.path("rule").asText();
      String status = row.path("status").asText();
      for (String prefix : List.of("SQ-", "SPN-", "BT-")) {
        if (rule.startsWith(prefix)) {
          unprovidedPrefixes.add(prefix);
          assertEquals("not-applicable", status, rule + "/" + row.path("case_id").asText());
        }
      }
      if (rule.equals("BT-D6a")) {
        sawBtD6a = true;
      }
      if (rule.equals("MEM-2")
          && row.path("case_id").asText().equals("memory-store-shared-configuration")
          && row.path("backend").asText().equals(Backend.MEMORY.reportName())) {
        assertEquals("passed", status);
        sawMem2Passed = true;
      }
    }
    assertEquals(Set.of("SQ-", "SPN-", "BT-"), unprovidedPrefixes);
    assertTrue(sawBtD6a);
    assertTrue(sawMem2Passed);
  }

  @Test
  void dynamodbOnlyCaseIsNotApplicableOnMemory() {
    ConformanceCase c =
        data.cases().stream()
            .filter(x -> x.backends().map(l -> l.equals(List.of("dynamodb"))).orElse(false))
            .filter(x -> !x.file().equals("dynamodb/layout.json"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("dynamodb-only case not found"));

    assertEquals(
        ConformanceStatus.NOT_APPLICABLE, CaseClassifier.classify(c, Backend.MEMORY).status());
    assertEquals(
        ConformanceStatus.UNVERIFIED, CaseClassifier.classify(c, Backend.DYNAMODB).status());
  }

  @Test
  void requiredListKeepsExistingCasesAndIncludesSuccessfulConnections() throws IOException {
    Map<Backend, Set<String>> required = RequiredCases.load();

    Set<String> memory =
        data.cases().stream()
            .filter(c -> EXECUTABLE_CASE_IDS.contains(c.id()) || memoryExecutable(c))
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());
    assertEquals(66, memory.size());
    assertEquals(memory, required.get(Backend.MEMORY));
    Set<String> dynamodb = new java.util.HashSet<>(EXECUTABLE_CASE_IDS);
    Set<String> configuration =
        data.cases().stream()
            .filter(c -> c.file().equals("dynamodb/configuration.json"))
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());
    assertEquals(14, configuration.size());
    dynamodb.addAll(configuration);
    dynamodb.addAll(
        Set.of("core-time-roundtrip-min", "core-time-roundtrip-max", "core-json-root-values"));
    dynamodb.addAll(DynamoDbCaseRunner.SNAPSHOT_CASE_IDS);
    dynamodb.addAll(DynamoDbCaseRunner.RETENTION_CASE_IDS);
    assertTrue(required.get(Backend.DYNAMODB).containsAll(dynamodb));
    Set<String> targets =
        data.cases().stream()
            .filter(c -> CaseClassifier.exclusion(c, Backend.DYNAMODB).isEmpty())
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());
    assertEquals(106, targets.size());
    assertEquals(targets, required.get(Backend.DYNAMODB));
    Set<String> connections =
        data.cases().stream()
            .filter(c -> CaseClassifier.exclusion(c, Backend.DYNAMODB).isEmpty())
            .filter(c -> ValueCaseRunner.supports(c) || DynamoDbCaseRunner.supports(c))
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());
    assertEquals(targets, connections);
    assertTrue(required.get(Backend.DYNAMODB).contains("dynamodb-layout-v1"));
  }

  @Test
  void unverifiedCaseViolatesRequirementOnlyWhenListed() {
    CaseResult r = CaseClassifier.classify(find("occurred-at-min"), Backend.DYNAMODB);
    assertEquals(ConformanceStatus.UNVERIFIED, r.status());

    assertFalse(CaseClassifier.violatesRequirement(r, Map.of(Backend.DYNAMODB, Set.of())));
    assertTrue(
        CaseClassifier.violatesRequirement(r, Map.of(Backend.DYNAMODB, Set.of("occurred-at-min"))));
    assertFalse(
        CaseClassifier.violatesRequirement(r, Map.of(Backend.DYNAMODB, Set.of("other-case"))));
  }

  @Test
  void unknownRequiredIdsAreReturned() {
    List<String> unknown =
        CaseClassifier.unknownRequiredIds(Set.of("occurred-at-min", "no-such-case"), data.cases());

    assertEquals(List.of("no-such-case"), unknown);
  }

  @Test
  void requirementIsDecidedPerCaseIdAndBackendPair() {
    Map<Backend, Set<String>> required =
        Map.of(Backend.MEMORY, Set.of(), Backend.DYNAMODB, Set.of("occurred-at-min"));
    CaseResult memory = CaseClassifier.classify(find("occurred-at-min"), Backend.MEMORY);
    CaseResult dynamo = CaseClassifier.classify(find("occurred-at-min"), Backend.DYNAMODB);

    assertFalse(CaseClassifier.violatesRequirement(memory, required));
    assertTrue(CaseClassifier.violatesRequirement(dynamo, required));
  }

  @Test
  void changeFeedCaseIsUnrepresentableOnMemoryAndNotRequired() throws IOException {
    ConformanceCase c = find("memory-change-feed-unsupported");
    assertTrue(c.changeFeed());

    CaseResult r = CaseClassifier.classify(c, Backend.MEMORY);
    assertEquals(ConformanceStatus.UNREPRESENTABLE, r.status(), r.reason());
    assertTrue(r.reason().contains("変更フィード"), r.reason());
    assertTrue(CaseClassifier.exclusion(c, Backend.MEMORY).isPresent());
    assertFalse(RequiredCases.load().get(Backend.MEMORY).contains(c.id()));
    assertFalse(RequiredCases.load().get(Backend.DYNAMODB).contains(c.id()));
    assertEquals(
        ConformanceStatus.NOT_APPLICABLE, CaseClassifier.classify(c, Backend.DYNAMODB).status());
    Map<Backend, Set<String>> listed =
        Map.of(Backend.MEMORY, Set.of(c.id()), Backend.DYNAMODB, Set.of());
    assertTrue(CaseClassifier.violatesRequirement(r, listed));
  }

  @Test
  void changeFeedDefaultsToFalseWhenOmitted() {
    assertFalse(find("memory-ttl-unsupported").changeFeed());
    assertFalse(find("core-replay-without-snapshot").changeFeed());
  }

  @Test
  void ttlCaseIsRequiredOnMemoryAndSingleAggregateCasesOnDynamoDb() throws IOException {
    Map<Backend, Set<String>> required = RequiredCases.load();
    assertTrue(required.get(Backend.MEMORY).contains("memory-ttl-unsupported"));
    assertTrue(required.get(Backend.DYNAMODB).contains("dynamodb-single-aggregate-delete"));
    assertTrue(required.get(Backend.DYNAMODB).contains("dynamodb-single-aggregate-ttl"));
  }

  private static boolean memoryExecutable(ConformanceCase c) {
    return !c.timePrecision().map("milliseconds"::equals).orElse(false)
        && (c.file().startsWith("scenarios/core/")
            || (c.file().startsWith("memory/") && !c.changeFeed())
            || c.operation().map("validateOccurredAt"::equals).orElse(false));
  }
}
