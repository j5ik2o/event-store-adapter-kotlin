package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** One Local per JUnit execution, with SDK readiness and root-store shutdown. */
final class DynamoDbLocalExtension implements BeforeAllCallback {
  static final String IMAGE =
      "amazon/dynamodb-local@sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab";
  private Server server;

  @Override
  public void beforeAll(ExtensionContext context) {
    server =
        context
            .getRoot()
            .getStore(ExtensionContext.Namespace.create(DynamoDbLocalExtension.class))
            .getOrComputeIfAbsent(Server.class, key -> new Server(), Server.class);
  }

  URI endpoint() {
    return server.endpoint;
  }

  private static final class Server implements ExtensionContext.Store.CloseableResource {
    private final GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory");
    private final URI endpoint;

    private Server() {
      try {
        container.start();
        endpoint =
            URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8000));
        try (DynamoDbClient client = DynamoDbTestClients.admin(endpoint)) {
          long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
          while (true) {
            try {
              client.listTables();
              break;
            } catch (SdkException error) {
              if (System.nanoTime() >= deadline) throw error;
              try {
                Thread.sleep(50);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Local readiness interrupted", interrupted);
              }
            }
          }
        }
      } catch (Throwable error) {
        try {
          container.stop();
        } catch (Throwable cleanup) {
          error.addSuppressed(cleanup);
        }
        throw error;
      }
    }

    @Override
    public void close() throws Exception {
      String containerId = container.getContainerId();
      container.stop();
      boolean running = container.isRunning();
      Path directory = Path.of("build/reports/dynamodb-local-resources");
      Files.createDirectories(directory);
      DynamoDbJson.write(
          directory.resolve("termination.json"),
          DynamoDbJson.object()
              .put("image", IMAGE)
              .put("container_id", containerId)
              .put("endpoint", endpoint.toString())
              .put("owned_local_running_after_stop", running ? 1 : 0)
              .put("resources_closed", !running));
      if (running) throw new AssertionError("DynamoDB Local did not stop");
    }
  }
}
