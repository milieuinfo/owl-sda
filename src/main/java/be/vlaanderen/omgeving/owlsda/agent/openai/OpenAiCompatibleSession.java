package be.vlaanderen.omgeving.owlsda.agent.openai;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.handler.SessionHandler;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Session implementation backed by an OpenAI Chat Completions-compatible /chat/completions
 * endpoint, with tool-calling support.
 */
public class OpenAiCompatibleSession implements Session {
  private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatibleSession.class);
  private static final Gson GSON = new Gson();
  private static final Type STRING_OBJECT_MAP_TYPE = new TypeToken<Map<String, Object>>() {
  }.getType();

  private final be.vlaanderen.omgeving.owlsda.agent.SessionConfig config;
  private final HttpClient httpClient;
  private final URI chatUri;
  private final String apiKey;
  private final Map<String, SessionHandler> handlersByName;

  private final Set<Context> contexts = ConcurrentHashMap.newKeySet();
  private final List<JsonObject> messageHistory = new ArrayList<>();
  private final Object conversationLock = new Object();

  private final List<SessionMessageLogEntry> messageLog = new CopyOnWriteArrayList<>();
  private final AtomicLong totalTokensUsed = new AtomicLong(0L);
  private final AtomicLong inputTokensUsed = new AtomicLong(0L);
  private final AtomicLong outputTokensUsed = new AtomicLong(0L);
  private final AtomicLong lastAssistantActivityMs = new AtomicLong(System.currentTimeMillis());

  protected OpenAiCompatibleSession(be.vlaanderen.omgeving.owlsda.agent.SessionConfig config,
      String baseUrl,
      String apiKey,
      HttpClient httpClient) {
    this.config = config;
    this.httpClient = httpClient;
    this.apiKey = apiKey;
    this.chatUri = URI.create(baseUrl + "/chat/completions");
    this.handlersByName = new LinkedHashMap<>();

    if (config.getHandlers() != null) {
      for (SessionHandler handler : config.getHandlers()) {
        handlersByName.put(handler.getName(), handler);
      }
    }

    resetConversationState();
  }

  @Override
  public void addContext(Context context) {
    synchronized (contexts) {
      contexts.removeIf(context::equals);
      contexts.add(new Context(context));
    }
  }

  @Override
  public boolean addContextIfChanged(Context context) {
    synchronized (contexts) {
      Context existingContext = contexts.stream()
          .filter(context::equals)
          .findFirst()
          .orElse(null);

      if (existingContext == null) {
        contexts.add(new Context(context));
        return true;
      }

      String existingContent = existingContext.getContent();
      String newContent = context.getContent();
      if (newContent == null && existingContent == null) {
        return false;
      }

      if (newContent == null || !newContent.equals(existingContent)) {
        contexts.removeIf(context::equals);
        contexts.add(new Context(context));
        return true;
      }

      return false;
    }
  }

  @Override
  public List<Context> getContext() {
    synchronized (contexts) {
      return contexts.stream().map(Context::new).toList();
    }
  }

  @Override
  public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> contexts) {
    CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
    synchronized (conversationLock) {
      try {
        String prompt = input.getMessage();
        addMessageLogEntry("OUTBOUND", null, prompt);
        messageHistory.add(createMessage("user", prompt));

        markAssistantActivity();
        ResponseMessage responseMessage = executeConversation();
        future.complete(responseMessage);
      } catch (Exception e) {
        addMessageLogEntry("ERROR", null, e.getMessage());
        future.completeExceptionally(e);
      }
    }
    return future;
  }

  @Override
  public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
    return prompt(input, getContext());
  }

  @Override
  public List<SessionMessageLogEntry> getMessageLog() {
    return List.copyOf(messageLog);
  }

  @Override
  public void reset() {
    synchronized (conversationLock) {
      resetConversationState();
      markAssistantActivity();
    }
  }

  @Override
  public void close() {
    // No close action required for java.net.http.HttpClient.
  }

  @Override
  public long getTotalTokensUsed() {
    long directionalTotal = inputTokensUsed.get() + outputTokensUsed.get();
    return Math.max(totalTokensUsed.get(), directionalTotal);
  }

  @Override
  public long getInputTokensUsed() {
    return inputTokensUsed.get();
  }

  @Override
  public long getOutputTokensUsed() {
    return outputTokensUsed.get();
  }

  @Override
  public long getLastAssistantActivityMs() {
    return lastAssistantActivityMs.get();
  }

  @Override
  public boolean isIdleSince(long idleThresholdMs) {
    long elapsedMs = System.currentTimeMillis() - lastAssistantActivityMs.get();
    return elapsedMs >= idleThresholdMs;
  }

  private ResponseMessage executeConversation() throws Exception {
    while (true) {
      JsonObject payload = buildChatPayload();
      JsonObject response = sendChatRequest(payload);
      ParsedAssistantTurn turn = parseAssistantTurn(response);
      accumulateUsage(turn);
      markAssistantActivity();

      if (turn.assistantMessage() != null) {
        messageHistory.add(turn.assistantMessage());
      }

      if (turn.toolCalls().isEmpty()) {
        String messageId = UUID.randomUUID().toString();
        String content = turn.content() == null ? "" : turn.content();
        ResponseMessage responseMessage = new ResponseMessage(messageId);
        responseMessage.setMessage(content);
        addMessageLogEntry("INBOUND", messageId, content);
        return responseMessage;
      }

      for (ToolCall toolCall : turn.toolCalls()) {
        executeToolCall(toolCall);
      }
    }
  }

  private void executeToolCall(ToolCall toolCall) {
    SessionHandler handler = handlersByName.get(toolCall.name());
    Object result;

    if (handler == null) {
      result = Map.of("error", "Unknown tool: " + toolCall.name());
    } else {
      try {
        long timeoutMs = Math.max(1, config.getTimeoutMs());
        result = handler.handle(toolCall.arguments()).get(timeoutMs, TimeUnit.MILLISECONDS);
      } catch (Exception e) {
        result = Map.of("error", "Tool execution failed: " + e.getMessage());
      }
    }

    String resultJson = GSON.toJson(result);
    JsonObject toolMessage = createMessage("tool", resultJson);
    toolMessage.addProperty("name", toolCall.name());
    if (toolCall.id() != null) {
      toolMessage.addProperty("tool_call_id", toolCall.id());
    }
    messageHistory.add(toolMessage);

    addMessageLogEntry("TOOL_INVOCATION", null, toolCall.name() + "(" + GSON.toJson(toolCall.arguments()) + ")");
    addMessageLogEntry("TOOL_RESULT", null, resultJson);
    markAssistantActivity();
  }

  private JsonObject buildChatPayload() {
    JsonObject payload = new JsonObject();
    payload.addProperty("model", config.getModel());
    payload.addProperty("stream", false);

    JsonArray messages = new JsonArray();
    for (JsonObject message : messageHistory) {
      messages.add(message.deepCopy());
    }
    payload.add("messages", messages);

    if (!handlersByName.isEmpty()) {
      payload.add("tools", buildToolsPayload());
    }
    return payload;
  }

  private JsonArray buildToolsPayload() {
    JsonArray tools = new JsonArray();
    for (SessionHandler handler : handlersByName.values()) {
      JsonObject function = new JsonObject();
      function.addProperty("name", handler.getName());
      function.addProperty("description", handler.getDescription());
      function.add("parameters", GSON.toJsonTree(handler.getArguments()));

      JsonObject tool = new JsonObject();
      tool.addProperty("type", "function");
      tool.add("function", function);
      tools.add(tool);
    }
    return tools;
  }

  private JsonObject sendChatRequest(JsonObject payload) throws IOException, InterruptedException {
    long timeoutMs = Math.max(1000, config.getTimeoutMs());
    HttpRequest request = HttpRequest.newBuilder()
        .uri(chatUri)
        .timeout(Duration.ofMillis(timeoutMs))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
        .build();

    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException("OpenAI-compatible chat request failed: HTTP " + response.statusCode()
          + " - " + response.body());
    }

    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  static ParsedAssistantTurn parseAssistantTurn(JsonObject response) {
    JsonObject message = new JsonObject();
    if (response.has("choices") && response.get("choices").isJsonArray()
        && !response.getAsJsonArray("choices").isEmpty()) {
      JsonElement firstChoice = response.getAsJsonArray("choices").get(0);
      if (firstChoice.isJsonObject() && firstChoice.getAsJsonObject().has("message")
          && firstChoice.getAsJsonObject().get("message").isJsonObject()) {
        message = firstChoice.getAsJsonObject().getAsJsonObject("message");
      }
    }

    String content = readString(message, "content");
    List<ToolCall> toolCalls = parseToolCalls(message);

    JsonObject usage = response.has("usage") && response.get("usage").isJsonObject()
        ? response.getAsJsonObject("usage")
        : new JsonObject();
    long promptTokens = readLong(usage, "prompt_tokens");
    long completionTokens = readLong(usage, "completion_tokens");

    return new ParsedAssistantTurn(content, toolCalls, message.deepCopy(), promptTokens, completionTokens);
  }

  static List<ToolCall> parseToolCalls(JsonObject message) {
    if (message == null || !message.has("tool_calls") || !message.get("tool_calls").isJsonArray()) {
      return List.of();
    }

    List<ToolCall> calls = new ArrayList<>();
    JsonArray toolCalls = message.getAsJsonArray("tool_calls");
    for (JsonElement entry : toolCalls) {
      if (!entry.isJsonObject()) {
        continue;
      }

      JsonObject toolCallObject = entry.getAsJsonObject();
      String id = readString(toolCallObject, "id");
      JsonObject function = toolCallObject.has("function") && toolCallObject.get("function").isJsonObject()
          ? toolCallObject.getAsJsonObject("function")
          : null;
      if (function == null) {
        continue;
      }

      String name = readString(function, "name");
      if (name == null || name.isBlank()) {
        continue;
      }

      Map<String, Object> arguments = parseToolArguments(function.get("arguments"));
      calls.add(new ToolCall(id, name, arguments));
    }

    return calls;
  }

  static Map<String, Object> parseToolArguments(JsonElement argumentsElement) {
    if (argumentsElement == null || argumentsElement.isJsonNull()) {
      return Map.of();
    }

    if (argumentsElement.isJsonObject()) {
      return GSON.fromJson(argumentsElement, STRING_OBJECT_MAP_TYPE);
    }

    if (argumentsElement.isJsonPrimitive() && argumentsElement.getAsJsonPrimitive().isString()) {
      String argumentsJson = argumentsElement.getAsString();
      if (argumentsJson == null || argumentsJson.isBlank()) {
        return Map.of();
      }

      try {
        JsonElement parsed = JsonParser.parseString(argumentsJson);
        if (parsed.isJsonObject()) {
          return GSON.fromJson(parsed, STRING_OBJECT_MAP_TYPE);
        }
      } catch (Exception ignored) {
        logger.debug("Could not parse tool arguments string as JSON: {}", argumentsJson);
      }
    }

    return Map.of();
  }

  private static String readString(JsonObject source, String key) {
    if (source == null || !source.has(key) || source.get(key).isJsonNull()) {
      return null;
    }
    return source.get(key).getAsString();
  }

  private static long readLong(JsonObject source, String key) {
    if (source == null || !source.has(key) || source.get(key).isJsonNull()) {
      return 0L;
    }

    try {
      return source.get(key).getAsLong();
    } catch (Exception ignored) {
      return 0L;
    }
  }

  private void accumulateUsage(ParsedAssistantTurn turn) {
    inputTokensUsed.addAndGet(Math.max(0, turn.promptTokens()));
    outputTokensUsed.addAndGet(Math.max(0, turn.completionTokens()));
    totalTokensUsed.set(inputTokensUsed.get() + outputTokensUsed.get());
  }

  private JsonObject createMessage(String role, String content) {
    JsonObject message = new JsonObject();
    message.addProperty("role", role);
    message.addProperty("content", content == null ? "" : content);
    return message;
  }

  private void addMessageLogEntry(String direction, String messageId, String content) {
    messageLog.add(new SessionMessageLogEntry(
        Instant.now().toString(),
        direction,
        messageId,
        content == null ? "" : content
    ));
  }

  private void markAssistantActivity() {
    lastAssistantActivityMs.set(System.currentTimeMillis());
  }

  private void resetConversationState() {
    messageHistory.clear();
    if (config.getSystemContext() != null && config.getSystemContext().getContent() != null) {
      messageHistory.add(createMessage("system", config.getSystemContext().getContent()));
    }
  }

  record ToolCall(String id, String name, Map<String, Object> arguments) {
  }

  record ParsedAssistantTurn(
      String content,
      List<ToolCall> toolCalls,
      JsonObject assistantMessage,
      long promptTokens,
      long completionTokens
  ) {
  }
}
