package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** DY-19: 操作の間にライブラリが送った要求が、許された API と操作の集約の aid だけに収まるかを判定する。 */
final class DynamoDbRequestScope {
  private DynamoDbRequestScope() {}

  /** 記録した 1 件の要求。body は実際に送信される本文。 */
  static final class Observed {
    final String api;
    final JsonNode body;

    Observed(String api, JsonNode body) {
      this.api = api;
      this.body = body;
    }
  }

  static List<String> violations(List<Observed> requests, Set<String> apis, String aid) {
    List<String> problems = new ArrayList<>();
    if (requests.isEmpty()) {
      problems.add("操作の間の要求が 0 件（記録の接続か操作番号の対応が壊れている）");
      return problems;
    }
    for (Observed request : requests) {
      if (!apis.contains(request.api)) {
        problems.add("許されない API: " + request.api);
        continue;
      }
      List<String> found = new ArrayList<>();
      try {
        collect(request, found, problems);
      } catch (IllegalArgumentException e) {
        problems.add(request.api + " の本文を解釈できない: " + e.getMessage());
        continue;
      }
      if (found.isEmpty()) {
        problems.add(request.api + " から aid を取れない");
      }
      for (String value : found) {
        if (!value.equals(aid)) {
          problems.add("別の集約の aid を含む: " + value + "（" + request.api + "）");
        }
      }
    }
    return problems;
  }

  private static void collect(Observed request, List<String> found, List<String> problems) {
    JsonNode body = request.body;
    switch (request.api) {
      case "GetItem":
      case "UpdateItem":
        add(body.path("Key"), found, problems, request.api);
        break;
      case "BatchGetItem":
        for (Map.Entry<String, JsonNode> table : iterable(body.path("RequestItems")))
          for (JsonNode key : table.getValue().path("Keys")) add(key, found, problems, request.api);
        break;
      case "TransactWriteItems":
        for (JsonNode action : body.path("TransactItems")) {
          if (action.size() != 1) throw new IllegalArgumentException("Invalid transaction action");
          Map.Entry<String, JsonNode> entry = action.fields().next();
          add(
              entry.getValue().path(entry.getKey().equals("Put") ? "Item" : "Key"),
              found,
              problems,
              request.api);
        }
        break;
      case "BatchWriteItem":
        for (Map.Entry<String, JsonNode> table : iterable(body.path("RequestItems")))
          for (JsonNode write : table.getValue()) {
            if (write.has("DeleteRequest"))
              add(write.at("/DeleteRequest/Key"), found, problems, request.api);
            if (write.has("PutRequest"))
              add(write.at("/PutRequest/Item"), found, problems, request.api);
          }
        break;
      case "Query":
        if (!body.has("KeyConditionExpression")) {
          problems.add("Query にキー条件がない");
          return;
        }
        for (JsonNode term : DynamoDbRequestStructure.parse(body).at("/key_condition/all")) {
          if (term.path("attribute").asText().equals("aid")
              && term.path("operator").asText().equals("eq")) {
            found.add(term.at("/argument/S").asText());
          }
        }
        break;
      default:
        throw new IllegalArgumentException("aid の取り出し方が未定義の API: " + request.api);
    }
  }

  /** 項目ごとに aid を取り出す。aid のない項目は、それ自体を違反にする（バッチの中の 1 件だけが欠けても見逃さない）。 */
  private static void add(JsonNode item, List<String> found, List<String> problems, String api) {
    JsonNode value = item.at("/aid/S");
    if (value.isTextual()) found.add(value.asText());
    else problems.add(api + " の項目に aid がない");
  }

  private static Iterable<Map.Entry<String, JsonNode>> iterable(JsonNode node) {
    return () -> node.fields();
  }
}
