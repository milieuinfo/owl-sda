package be.vlaanderen.omgeving.owlsda.agent.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HttpCallHandlerTest {

  private static final String ALLOWLISTED_HOST = "127.0.0.1";

  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger requestCount = new AtomicInteger(0);

  @Before
  public void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ok", exchange -> respond(exchange, 200, "hello"));
    server.createContext("/echo", this::echoBody);
    server.createContext("/flaky", this::flakyThenOk);
    server.createContext("/server-error", exchange -> respond(exchange, 500, "boom"));
    server.createContext("/client-error", exchange -> respond(exchange, 404, "not found"));
    server.start();

    int port = server.getAddress().getPort();
    baseUrl = "http://127.0.0.1:" + port;
  }

  @After
  public void stopServer() {
    server.stop(0);
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private void echoBody(HttpExchange exchange) throws IOException {
    byte[] requestBytes = exchange.getRequestBody().readAllBytes();
    exchange.sendResponseHeaders(200, requestBytes.length);
    exchange.getResponseBody().write(requestBytes);
    exchange.close();
  }

  private void flakyThenOk(HttpExchange exchange) throws IOException {
    if (requestCount.getAndIncrement() == 0) {
      respond(exchange, 500, "transient failure");
    } else {
      respond(exchange, 200, "recovered");
    }
  }

  private HttpCallHandler handlerFor(String allowedHost, boolean allowPost, int maxRetries) {
    HttpAllowlist allowlist = new HttpAllowlist(Set.of(allowedHost));
    HttpClient httpClient = HttpClient.newHttpClient();
    return new HttpCallHandler(allowlist, httpClient, 5000, 1_000_000, allowPost, maxRetries);
  }

  @Test
  public void handle_AllowlistedGet_ReturnsBody() {
    HttpCallHandler handler = handlerFor(ALLOWLISTED_HOST, true, 2);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(
        "url", baseUrl + "/ok"
    )).join();

    assertEquals(200, result.get("status"));
    assertEquals("hello", result.get("body"));
    assertEquals(false, result.get("truncated"));
  }

  @Test
  public void handle_AllowlistedPost_SendsBody() {
    HttpCallHandler handler = handlerFor(ALLOWLISTED_HOST, true, 2);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(
        "url", baseUrl + "/echo",
        "method", "POST",
        "body", "payload"
    )).join();

    assertEquals(200, result.get("status"));
    assertEquals("payload", result.get("body"));
  }

  @Test
  public void handle_DisallowedHost_ReturnsErrorWithoutCallingServer() {
    HttpCallHandler handler = handlerFor("other-host.example", true, 2);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(
        "url", baseUrl + "/ok"
    )).join();

    assertTrue(((String) result.get("error")).contains("not allowlisted"));
  }

  @Test
  public void handle_PostDisabled_ReturnsError() {
    HttpCallHandler handler = handlerFor(ALLOWLISTED_HOST, false, 2);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(
        "url", baseUrl + "/echo",
        "method", "POST",
        "body", "payload"
    )).join();

    assertTrue(((String) result.get("error")).contains("POST requests are disabled"));
  }

  @Test
  public void handle_TransientServerError_RetriesAndSucceeds() {
    HttpCallHandler handler = handlerFor(ALLOWLISTED_HOST, true, 2);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(
        "url", baseUrl + "/flaky"
    )).join();

    assertEquals(200, result.get("status"));
    assertEquals("recovered", result.get("body"));
  }

  @Test
  public void handle_PersistentServerError_FailsAfterRetriesExhausted() {
    HttpCallHandler handler = handlerFor(ALLOWLISTED_HOST, true, 1);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(
        "url", baseUrl + "/server-error"
    )).join();

    assertTrue(((String) result.get("error")).contains("Request failed after 2 attempt(s)"));
  }

  @Test
  public void handle_ClientError_DoesNotRetryAndReturnsStatus() {
    HttpCallHandler handler = handlerFor(ALLOWLISTED_HOST, true, 2);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(
        "url", baseUrl + "/client-error"
    )).join();

    assertEquals(404, result.get("status"));
  }
}
