package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.http.async.AsyncExecuteRequest;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpResponseHandler;
import software.amazon.awssdk.http.async.SdkHttpContentPublisher;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

class FaultAsyncHttpClientTest {
  @Test
  void callbackBeforeExecuteReturnsCannotPublishAnIncompleteTransmission() {
    Probe probe = new Probe();
    StubClient delegate =
        new StubClient(
            request -> {
              probe.success(request);
              assertFalse(probe.recorder.requestsFinished(probe.operation).isDone());
              assertEquals(1, probe.faults.pending(probe.operation));
              assertEquals(0, probe.record().transmissions);
              return CompletableFuture.completedFuture(null);
            });
    FaultAsyncHttpClient client = new FaultAsyncHttpClient(delegate, probe.recorder);
    client.execute(probe.request()).join();
    probe.assertTerminal(1, null);
    client.close();
    assertTrue(delegate.closed);
  }

  @Test
  void rejectionBeforeAcceptanceNeverBecomesATransmission() {
    Probe probe = new Probe();
    IllegalStateException rejected = new IllegalStateException("not accepted");
    StubClient delegate =
        new StubClient(
            request -> {
              throw rejected;
            });
    FaultAsyncHttpClient client = new FaultAsyncHttpClient(delegate, probe.recorder);
    CompletableFuture<Void> result = client.execute(probe.request());
    assertSame(rejected, assertThrows(CompletionException.class, result::join).getCause());
    probe.assertTerminal(0, rejected);
    client.close();
    assertTrue(delegate.closed);
  }

  @Test
  void failureCallbackBeforeReturnStillWaitsForAcceptanceAndKeepsItsCause() {
    Probe probe = new Probe();
    IOException failure = new IOException("accepted before failure");
    StubClient delegate =
        new StubClient(
            request -> {
              request.responseHandler().onError(failure);
              assertFalse(probe.recorder.requestsFinished(probe.operation).isDone());
              return CompletableFuture.failedFuture(failure);
            });
    FaultAsyncHttpClient client = new FaultAsyncHttpClient(delegate, probe.recorder);
    CompletableFuture<Void> result = client.execute(probe.request());
    assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
    probe.assertTerminal(1, failure);
    client.close();
    assertTrue(delegate.closed);
  }

  @Test
  void acceptedAsynchronousFailureWaitsForTheSdkTerminalAndKeepsItsCause() {
    Probe probe = new Probe();
    CompletableFuture<Void> accepted = new CompletableFuture<>();
    AtomicReference<AsyncExecuteRequest> sent = new AtomicReference<>();
    StubClient delegate =
        new StubClient(
            request -> {
              sent.set(request);
              return accepted;
            });
    FaultAsyncHttpClient client = new FaultAsyncHttpClient(delegate, probe.recorder);
    CompletableFuture<Void> result = client.execute(probe.request());
    assertEquals(1, probe.record().transmissions);
    assertFalse(probe.recorder.requestsFinished(probe.operation).isDone());
    IOException failure = new IOException("asynchronous failure");
    sent.get().responseHandler().onError(failure);
    accepted.completeExceptionally(failure);
    assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
    probe.assertTerminal(1, failure);
    client.close();
    assertTrue(delegate.closed);
  }

  @Test
  void ordinaryAcceptedSuccessRetainsTheExactRequestBody() {
    Probe probe = new Probe();
    CompletableFuture<Void> accepted = new CompletableFuture<>();
    AtomicReference<AsyncExecuteRequest> sent = new AtomicReference<>();
    StubClient delegate =
        new StubClient(
            request -> {
              sent.set(request);
              return accepted;
            });
    FaultAsyncHttpClient client = new FaultAsyncHttpClient(delegate, probe.recorder);
    CompletableFuture<Void> result = client.execute(probe.request());
    assertFalse(result.isDone());
    assertFalse(probe.recorder.requestsFinished(probe.operation).isDone());
    probe.success(sent.get());
    accepted.complete(null);
    result.join();
    probe.assertTerminal(1, null);
    client.close();
    assertTrue(delegate.closed);
  }

  @Test
  void cancellationOfAnAcceptedRequestKeepsTheTransmissionAndIndependentTerminal() {
    Probe probe = new Probe();
    CompletableFuture<Void> accepted = new CompletableFuture<>();
    StubClient delegate =
        new StubClient(
            request -> {
              accepted.whenComplete(
                  (ignored, failure) -> {
                    if (failure != null) request.responseHandler().onError(failure);
                  });
              return accepted;
            });
    FaultAsyncHttpClient client = new FaultAsyncHttpClient(delegate, probe.recorder);
    CompletableFuture<Void> result = client.execute(probe.request());
    assertFalse(probe.recorder.requestsFinished(probe.operation).isDone());
    result.cancel(true);
    assertTrue(accepted.isCancelled());
    assertTrue(result.isCancelled());
    assertInstanceOf(java.util.concurrent.CancellationException.class, probe.failure.get());
    probe.assertTerminal(1, probe.failure.get());
    client.close();
    assertTrue(delegate.closed);
  }

  private static final class StubClient implements SdkAsyncHttpClient {
    private final Function<AsyncExecuteRequest, CompletableFuture<Void>> execute;
    boolean closed;

    StubClient(Function<AsyncExecuteRequest, CompletableFuture<Void>> execute) {
      this.execute = execute;
    }

    public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
      return execute.apply(request);
    }

    public void close() {
      closed = true;
    }
  }

  private static final class Probe {
    final FaultRegistry faults = new FaultRegistry();
    final FaultRegistry.Operation operation = faults.begin(1, false);
    final DynamoDbRequestRecorder recorder =
        new DynamoDbRequestRecorder(
            faults, new DynamoDbRequestTargets("journal", "snapshot", "head", "history"));
    final ExecutionAttributes attributes =
        new ExecutionAttributes().putAttribute(SdkExecutionAttribute.OPERATION_NAME, "Query");
    final QueryRequest sdk =
        QueryRequest.builder()
            .tableName("journal")
            .keyConditionExpression("aid = :aid")
            .expressionAttributeValues(Map.of(":aid", AttributeValue.fromS("Account-1")))
            .build();
    final byte[] body = DynamoDbJson.bytes(DynamoDbJson.sdk(sdk));
    final InterceptorContext context =
        InterceptorContext.builder()
            .request(sdk)
            .response(QueryResponse.builder().build())
            .requestBody(RequestBody.fromBytes(body))
            .build();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final SdkHttpRequest http;

    Probe() {
      recorder.beforeExecution(context, attributes);
      recorder.modifyRequest(context, attributes);
      recorder.afterMarshalling(context, attributes);
      SdkHttpRequest original =
          SdkHttpFullRequest.builder()
              .uri(URI.create("http://localhost"))
              .method(SdkHttpMethod.POST)
              .build();
      http =
          recorder.modifyHttpRequest(context.toBuilder().httpRequest(original).build(), attributes);
    }

    AsyncExecuteRequest request() {
      return AsyncExecuteRequest.builder()
          .request(http)
          .requestContentPublisher(
              new SdkHttpContentPublisher() {
                public Optional<Long> contentLength() {
                  return Optional.of((long) body.length);
                }

                public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
                  AsyncRequestBody.fromBytes(body).subscribe(subscriber);
                }
              })
          .responseHandler(
              new SdkAsyncHttpResponseHandler() {
                public void onHeaders(SdkHttpResponse response) {}

                public void onStream(Publisher<ByteBuffer> response) {
                  HttpBodies.collect(response).join();
                  recorder.modifyResponse(context, attributes);
                  recorder.afterExecution(context, attributes);
                }

                public void onError(Throwable error) {
                  failure.set(error);
                  recorder.onExecutionFailure(
                      new Context.FailedExecution() {
                        public Throwable exception() {
                          return error;
                        }

                        public SdkRequest request() {
                          return sdk;
                        }

                        public Optional<SdkHttpRequest> httpRequest() {
                          return Optional.of(http);
                        }

                        public Optional<SdkHttpResponse> httpResponse() {
                          return Optional.empty();
                        }

                        public Optional<SdkResponse> response() {
                          return Optional.empty();
                        }
                      },
                      attributes);
                }
              })
          .build();
    }

    void success(AsyncExecuteRequest request) {
      request.responseHandler().onHeaders(SdkHttpResponse.builder().statusCode(200).build());
      request
          .responseHandler()
          .onStream(
              AsyncRequestBody.fromBytes("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    DynamoDbRequestRecorder.Request record() {
      return recorder.requests().get(0);
    }

    void assertTerminal(int transmissions, Throwable expectedFailure) {
      recorder.requestsFinished(operation).join();
      assertEquals(0, faults.pending(operation));
      assertEquals(transmissions, record().transmissions);
      assertSame(expectedFailure, record().failure);
      if (transmissions == 0) assertNull(record().transmitted);
      else assertEquals(DynamoDbJson.read(body), record().transmitted);
      assertEquals("passed", faults.finish(operation).status);
    }
  }
}
