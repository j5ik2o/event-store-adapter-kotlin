package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DynamoDbRequestScopeTest {
  private static final Set<String> APIS =
      Set.of(
          "GetItem", "BatchGetItem", "Query", "TransactWriteItems", "BatchWriteItem", "UpdateItem");
  private static final String AID = "Order-9";

  private static DynamoDbRequestScope.Observed observed(String api, String body) {
    return new DynamoDbRequestScope.Observed(api, DynamoDbRequestStructureTest.json(body));
  }

  private static DynamoDbRequestScope.Observed query(String aid) {
    return observed(
        "Query",
        "{\"TableName\":\"journal\",\"KeyConditionExpression\":\"#a = :aid\","
            + "\"ExpressionAttributeNames\":{\"#a\":\"aid\"},"
            + "\"ExpressionAttributeValues\":{\":aid\":{\"S\":\""
            + aid
            + "\"}}}");
  }

  private static List<DynamoDbRequestScope.Observed> allSixApis(String aid) {
    String key = "{\"aid\":{\"S\":\"" + aid + "\"},\"seq_nr\":{\"N\":\"1\"}}";
    return List.of(
        observed("GetItem", "{\"TableName\":\"snapshot\",\"Key\":" + key + "}"),
        observed("BatchGetItem", "{\"RequestItems\":{\"snapshot\":{\"Keys\":[" + key + "]}}}"),
        query(aid),
        observed(
            "TransactWriteItems",
            "{\"TransactItems\":[{\"Put\":{\"TableName\":\"journal\",\"Item\":"
                + key
                + "}},{\"Delete\":{\"TableName\":\"journal\",\"Key\":"
                + key
                + "}}]}"),
        observed(
            "BatchWriteItem",
            "{\"RequestItems\":{\"journal\":[{\"DeleteRequest\":{\"Key\":"
                + key
                + "}},{\"PutRequest\":{\"Item\":"
                + key
                + "}}]}}"),
        observed("UpdateItem", "{\"TableName\":\"snapshot\",\"Key\":" + key + "}"));
  }

  @Test
  void requestsOfAllSixApisOnTheOperationAggregateHaveNoViolation() {
    assertEquals(List.of(), DynamoDbRequestScope.violations(allSixApis(AID), APIS, AID));
  }

  @Test
  void apiOutsideTheAllowedListIsAViolation() {
    List<String> problems =
        DynamoDbRequestScope.violations(
            List.of(observed("Scan", "{\"TableName\":\"journal\"}")), APIS, AID);

    assertFalse(problems.isEmpty());
    assertTrue(problems.stream().anyMatch(p -> p.contains("Scan")), problems.toString());
  }

  @Test
  void batchGetItemWithAnotherAggregateKeyIsAViolation() {
    List<String> problems =
        DynamoDbRequestScope.violations(
            List.of(
                observed(
                    "BatchGetItem",
                    "{\"RequestItems\":{\"snapshot\":{\"Keys\":["
                        + "{\"aid\":{\"S\":\"Order-9\"},\"seq_nr\":{\"N\":\"1\"}},"
                        + "{\"aid\":{\"S\":\"Customer-5\"},\"seq_nr\":{\"N\":\"1\"}}]}}}")),
            APIS,
            AID);

    assertTrue(problems.stream().anyMatch(p -> p.contains("Customer-5")), problems.toString());
  }

  @Test
  void queryWithoutKeyConditionIsAViolation() {
    List<String> problems =
        DynamoDbRequestScope.violations(
            List.of(observed("Query", "{\"TableName\":\"journal\"}")), APIS, AID);

    assertFalse(problems.isEmpty());
  }

  @Test
  void batchEntryWithoutAidIsAViolationEvenWhenAnotherEntryHasTheAid() {
    String own = "{\"aid\":{\"S\":\"" + AID + "\"},\"skey\":{\"N\":\"0\"}}";
    String noAid = "{\"skey\":{\"N\":\"0\"}}";
    for (DynamoDbRequestScope.Observed request :
        List.of(
            observed(
                "BatchGetItem",
                "{\"RequestItems\":{\"snapshot\":{\"Keys\":[" + own + "," + noAid + "]}}}"),
            observed(
                "BatchWriteItem",
                "{\"RequestItems\":{\"snapshot\":[{\"DeleteRequest\":{\"Key\":"
                    + own
                    + "}},{\"DeleteRequest\":{\"Key\":"
                    + noAid
                    + "}}]}}"),
            observed(
                "TransactWriteItems",
                "{\"TransactItems\":[{\"Put\":{\"Item\":"
                    + own
                    + "}},{\"Update\":{\"Key\":"
                    + noAid
                    + "}}]}"))) {
      List<String> problems = DynamoDbRequestScope.violations(List.of(request), APIS, AID);

      assertTrue(
          problems.stream().anyMatch(p -> p.contains("aid がない")), request.api + ": " + problems);
    }
  }

  @Test
  void zeroRequestsBetweenOperationsIsAViolation() {
    List<String> problems = DynamoDbRequestScope.violations(List.of(), APIS, AID);

    assertFalse(problems.isEmpty());
  }

  @Test
  void queryWhoseAidEqualityIsAnotherValueIsAViolation() {
    List<String> problems =
        DynamoDbRequestScope.violations(List.of(query("Customer-5")), APIS, AID);

    assertTrue(problems.stream().anyMatch(p -> p.contains("Customer-5")), problems.toString());
  }
}
