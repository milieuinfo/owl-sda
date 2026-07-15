package be.vlaanderen.omgeving.owlsda.agent.ollama;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.SessionConfig;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.config.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OllamaSessionCompactionTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger requestCount = new AtomicInteger(0);
  private volatile boolean summaryRequestsFail = false;

  @Before
  public void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/chat", this::handleChat);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @After
  public void stopServer() {
    server.stop(0);
  }

  private void handleChat(HttpExchange exchange) throws IOException {
    requestCount.incrementAndGet();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    JsonObject request = JsonParser.parseString(body).getAsJsonObject();
    JsonArray messages = request.getAsJsonArray("messages");

    boolean isSummaryRequest = messages.size() == 1;

    if (isSummaryRequest && summaryRequestsFail) {
      respond(exchange, 500, "{\"error\":\"boom\"}");
      return;
    }

    String content = isSummaryRequest ? "SUMMARY: earlier triples and decisions" : "ack";
    JsonObject response = new JsonObject();
    JsonObject message = new JsonObject();
    message.addProperty("role", "assistant");
    message.addProperty("content", content);
    response.add("message", message);
    response.addProperty("prompt_eval_count", 10);
    response.addProperty("eval_count", 5);

    respond(exchange, 200, response.toString());
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private OllamaSession newSession(Config.CompactionProperties compactionProperties) {
    Context systemContext = new Context();
    systemContext.setContent("You are a helpful assistant.");

    SessionConfig sessionConfig =
        SessionConfig.builder()
            .model("test-model")
            .systemContext(systemContext)
            .timeoutMs(5000)
            .build();

    return new OllamaSession(
        sessionConfig, baseUrl, HttpClient.newHttpClient(), compactionProperties);
  }

  private boolean hasCompactionLogEntry(OllamaSession session) {
    return session.getMessageLog().stream()
        .map(SessionMessageLogEntry::direction)
        .anyMatch("COMPACTION"::equals);
  }

  @Test
  public void prompt_PastMessageCountThreshold_CompactsOlderHistory() {
    Config.CompactionProperties compaction = new Config.CompactionProperties();
    compaction.setEnabled(true);
    compaction.setTokenThreshold(0); // disabled, force message-count trigger
    compaction.setMessageCountThreshold(5);
    compaction.setKeepRecentMessages(2);

    OllamaSession session = newSession(compaction);

    assertFalse(hasCompactionLogEntry(session));

    for (int i = 0; i < 3; i++) {
      ResponseMessage response = session.prompt(new RequestMessage("message " + i)).join();
      assertEquals("ack", response.getMessage());
    }

    assertTrue(
        "Expected a COMPACTION log entry once message-count threshold was crossed",
        hasCompactionLogEntry(session));
  }

  @Test
  public void prompt_BelowThresholds_NeverCompacts() {
    Config.CompactionProperties compaction = new Config.CompactionProperties();
    compaction.setEnabled(true);
    compaction.setTokenThreshold(100_000);
    compaction.setMessageCountThreshold(1000);

    OllamaSession session = newSession(compaction);

    for (int i = 0; i < 3; i++) {
      session.prompt(new RequestMessage("message " + i)).join();
    }

    assertFalse(hasCompactionLogEntry(session));
  }

  @Test
  public void prompt_CompactionDisabled_NeverCompactsEvenPastThreshold() {
    Config.CompactionProperties compaction = new Config.CompactionProperties();
    compaction.setEnabled(false);
    compaction.setMessageCountThreshold(5);

    OllamaSession session = newSession(compaction);

    for (int i = 0; i < 3; i++) {
      session.prompt(new RequestMessage("message " + i)).join();
    }

    assertFalse(hasCompactionLogEntry(session));
  }

  @Test
  public void prompt_SummarizationFails_FailsOpenAndConversationContinues() {
    Config.CompactionProperties compaction = new Config.CompactionProperties();
    compaction.setEnabled(true);
    compaction.setTokenThreshold(0);
    compaction.setMessageCountThreshold(5);
    compaction.setKeepRecentMessages(2);

    summaryRequestsFail = true;
    OllamaSession session = newSession(compaction);

    List<ResponseMessage> responses = new java.util.ArrayList<>();
    for (int i = 0; i < 4; i++) {
      responses.add(session.prompt(new RequestMessage("message " + i)).join());
    }

    for (ResponseMessage response : responses) {
      assertEquals("ack", response.getMessage());
    }
    assertFalse(
        "Compaction should not have succeeded while summarization fails",
        hasCompactionLogEntry(session));
  }
}
