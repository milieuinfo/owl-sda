package be.vlaanderen.omgeving.owlsda.agent;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.handler.SessionHandler;
import be.vlaanderen.omgeving.owlsda.config.Config;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared infrastructure for {@link Session} implementations backed by an HTTP chat-completions
 * style endpoint with tool-calling support (Ollama's {@code /api/chat}, OpenAI-compatible {@code
 * /chat/completions}). Handles context tracking, message history, token accounting, request
 * retries, tool-call dispatch, and history compaction.
 *
 * <p>Subclasses supply the provider-specific request/response shape: parsing a raw response into a
 * {@code ParsedAssistantTurn} differs enough between providers (different JSON shapes, different
 * usage field names) that {@link #executeConversation()} and the parsing methods remain per
 * subclass. Everything that does not depend on that shape - context management, the HTTP send/retry
 * loop, payload/tool-schema building, history compaction, and message bookkeeping - lives here.
 */
public abstract class AbstractHttpChatSession implements Session {
  // HTML-escaping must stay disabled: tool results routinely carry IRIs as plain RDF/Turtle text,
  // and this Gson instance both stringifies those results into a message's `content` field AND
  // re-serializes the whole payload containing that field. With HTML-escaping on, angle brackets
  // get escaped on the first pass, then that escape sequence's backslash gets escaped again on the
  // second pass - so the model ends up reading corrupted angle-bracket text instead of real IRIs,
  // which explains a large share of the malformed-Turtle tool calls seen in practice.
  protected static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  protected static final Type STRING_OBJECT_MAP_TYPE =
      new TypeToken<Map<String, Object>>() {}.getType();

  private static final int CHAT_REQUEST_MAX_RETRIES = 2;
  private static final long CHAT_REQUEST_RETRY_BASE_DELAY_MS = 500L;

  private final Logger logger = LoggerFactory.getLogger(getClass());

  protected final SessionConfig config;
  private final HttpClient httpClient;
  private final URI chatUri;
  protected final Map<String, SessionHandler> handlersByName;
  protected final Config.CompactionProperties compactionProperties;

  private final Set<Context> contexts = ConcurrentHashMap.newKeySet();
  protected final List<JsonObject> messageHistory = new ArrayList<>();
  private final Object conversationLock = new Object();

  private final List<SessionMessageLogEntry> messageLog = new CopyOnWriteArrayList<>();
  private final AtomicLong totalTokensUsed = new AtomicLong(0L);
  protected final AtomicLong inputTokensUsed = new AtomicLong(0L);
  protected final AtomicLong outputTokensUsed = new AtomicLong(0L);
  private final AtomicLong lastAssistantActivityMs = new AtomicLong(System.currentTimeMillis());
  // Tokens consumed by the messages array on the most recently sent chat request. Used as the
  // primary compaction trigger; reset after a successful compaction since the next request's
  // size is not yet known.
  protected final AtomicLong lastPromptTokens = new AtomicLong(0L);

  protected AbstractHttpChatSession(
      SessionConfig config,
      URI chatUri,
      HttpClient httpClient,
      Config.CompactionProperties compactionProperties) {
    this.config = config;
    this.httpClient = httpClient;
    this.chatUri = chatUri;
    this.handlersByName = new LinkedHashMap<>();
    this.compactionProperties =
        compactionProperties != null ? compactionProperties : new Config.CompactionProperties();

    if (config.getHandlers() != null) {
      for (SessionHandler handler : config.getHandlers()) {
        handlersByName.put(handler.getName(), handler);
      }
    }

    resetConversationState();
  }

  // --- Session: context management ---

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
      Context existingContext = contexts.stream().filter(context::equals).findFirst().orElse(null);

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

  // --- Session: prompting ---

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

  // --- Session: token/activity accounting ---

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

  /** Recomputes {@code totalTokensUsed} from the current input/output counters. */
  protected void recordTotalTokens() {
    totalTokensUsed.set(inputTokensUsed.get() + outputTokensUsed.get());
  }

  // --- Provider-specific hooks ---

  /** Runs the full request/response tool-calling loop for one user prompt. */
  protected abstract ResponseMessage executeConversation() throws Exception;

  /**
   * Short label used in log/error messages, e.g. {@code "Ollama"} or {@code "OpenAI-compatible"}.
   */
  protected abstract String providerLabel();

  /** Whether history compaction is enabled for this provider. */
  protected abstract boolean isProviderCompactionEnabled();

  /** Extracts the assistant's plain-text content from a raw chat-completion response. */
  protected abstract String extractResponseContent(JsonObject response);

  /**
   * Adds provider-specific fields to an outbound chat payload (e.g. Ollama's {@code think} flag).
   */
  protected void contributeAdditionalPayloadFields(JsonObject payload) {
    // no-op by default
  }

  /** Adds provider-specific headers to an outbound HTTP request (e.g. OpenAI's Authorization). */
  protected void contributeRequestHeaders(HttpRequest.Builder builder) {
    // no-op by default
  }

  // --- Request payload building ---

  protected JsonObject buildChatPayload() {
    JsonObject payload = new JsonObject();
    payload.addProperty("model", config.getModel());
    payload.addProperty("stream", false);
    contributeAdditionalPayloadFields(payload);

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

  // --- HTTP send with retry ---

  protected JsonObject sendChatRequest(JsonObject payload)
      throws IOException, InterruptedException {
    return sendChatRequest(payload, Math.max(1000, config.getTimeoutMs()));
  }

  /**
   * Sends a chat request, retrying with exponential backoff on transient failures (connection
   * errors, 5xx) but not on 4xx, which indicate a real request problem rather than flakiness.
   */
  protected JsonObject sendChatRequest(JsonObject payload, long timeoutMs)
      throws IOException, InterruptedException {
    try {
      return HttpRetryExecutor.retry(
          CHAT_REQUEST_MAX_RETRIES,
          CHAT_REQUEST_RETRY_BASE_DELAY_MS,
          e -> e instanceof IOException && !(e instanceof NonRetryableHttpException),
          (attempt, maxRetries, cause) ->
              logger.warn(
                  "{} chat request failed (attempt {}/{}): {}; retrying",
                  providerLabel(),
                  attempt + 1,
                  maxRetries + 1,
                  cause.getMessage()),
          () -> sendChatRequestOnce(payload, timeoutMs));
    } catch (IOException | InterruptedException e) {
      throw e;
    } catch (Exception e) {
      // sendChatRequestOnce only declares IOException/InterruptedException, so this path is
      // unreachable; required only to satisfy HttpRetryExecutor's generic `throws Exception`.
      throw new IOException(e);
    }
  }

  private JsonObject sendChatRequestOnce(JsonObject payload, long timeoutMs)
      throws IOException, InterruptedException {
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder()
            .uri(chatUri)
            .timeout(Duration.ofMillis(timeoutMs))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)));
    contributeRequestHeaders(requestBuilder);

    HttpResponse<String> response =
        httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() >= 500) {
      throw new IOException(
          providerLabel()
              + " chat request failed: HTTP "
              + response.statusCode()
              + " - "
              + response.body());
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      // 4xx and other non-2xx, non-5xx statuses are not retried.
      throw new NonRetryableHttpException(
          providerLabel()
              + " chat request failed: HTTP "
              + response.statusCode()
              + " - "
              + response.body());
    }

    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  protected static final class NonRetryableHttpException extends IOException {
    NonRetryableHttpException(String message) {
      super(message);
    }
  }

  // --- Tool call execution (shared shape across providers) ---

  /**
   * A parsed tool call. {@code id} is the provider's call identifier and is null for providers
   * (like Ollama) whose protocol does not use one; {@link #executeToolCall} only echoes it back
   * when present, so providers that never populate it see no behavioral change.
   */
  public record ToolCall(String id, String name, Map<String, Object> arguments) {}

  protected void executeToolCall(ToolCall toolCall) {
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

    addMessageLogEntry(
        "TOOL_INVOCATION", null, toolCall.name() + "(" + GSON.toJson(toolCall.arguments()) + ")");
    addMessageLogEntry("TOOL_RESULT", null, resultJson);
    markAssistantActivity();
  }

  // --- JSON parsing helpers shared by provider-specific parseAssistantTurn/parseToolCalls ---

  public static String readString(JsonObject source, String key) {
    if (source == null || !source.has(key) || source.get(key).isJsonNull()) {
      return null;
    }
    return source.get(key).getAsString();
  }

  public static long readLong(JsonObject source, String key) {
    if (source == null || !source.has(key) || source.get(key).isJsonNull()) {
      return 0L;
    }

    try {
      return source.get(key).getAsLong();
    } catch (Exception ignored) {
      return 0L;
    }
  }

  public static Map<String, Object> parseToolArguments(JsonElement argumentsElement) {
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
        // fall through to empty map below
      }
    }

    return Map.of();
  }

  // --- History compaction ---

  /**
   * Compacts {@code messageHistory} when it has grown past the configured thresholds, by
   * summarizing older turns into a single synthetic message via a separate, non-tool-looped LLM
   * call. Fails open: any error here is logged and swallowed so a stalled/oversized history never
   * blocks forward progress of the actual conversation.
   */
  protected void maybeCompactHistory() {
    if (!compactionProperties.isEnabled() || !isProviderCompactionEnabled()) {
      return;
    }
    if (!exceedsCompactionThreshold()) {
      return;
    }

    try {
      compactHistory();
    } catch (Exception e) {
      logger.warn("History compaction failed, continuing with full history: {}", e.getMessage());
    }
  }

  private boolean exceedsCompactionThreshold() {
    int contextWindowTokens = config.getContextWindowTokens();
    if (contextWindowTokens > 0) {
      // Ratio-based trigger against the model's real, configured context window rather than a
      // guessed absolute token count - see SessionConfig#contextWindowTokens.
      double ratio = compactionProperties.getContextWindowThresholdRatio();
      if (ratio > 0 && lastPromptTokens.get() >= contextWindowTokens * ratio) {
        return true;
      }
    } else {
      int tokenThreshold = compactionProperties.getTokenThreshold();
      if (tokenThreshold > 0 && lastPromptTokens.get() >= tokenThreshold) {
        return true;
      }
    }

    int messageCountThreshold = compactionProperties.getMessageCountThreshold();
    return messageCountThreshold > 0 && messageHistory.size() >= messageCountThreshold;
  }

  private void compactHistory() throws IOException, InterruptedException {
    int startIndex =
        (!messageHistory.isEmpty() && "system".equals(readString(messageHistory.get(0), "role")))
            ? 1
            : 0;
    int keepRecent = Math.max(0, compactionProperties.getKeepRecentMessages());
    int endIndex = Math.max(startIndex, messageHistory.size() - keepRecent);

    if (endIndex - startIndex < 2) {
      // Not enough older messages to be worth summarizing.
      return;
    }

    List<JsonObject> toSummarize = new ArrayList<>(messageHistory.subList(startIndex, endIndex));
    String summary = requestSummary(toSummarize);

    JsonObject summaryMessage =
        createMessage(
            "user",
            "Summary of earlier conversation (compacted to manage context size):\n" + summary);
    summaryMessage.addProperty("__compaction_summary__", true);

    List<JsonObject> newHistory = new ArrayList<>(messageHistory.subList(0, startIndex));
    newHistory.add(summaryMessage);
    newHistory.addAll(messageHistory.subList(endIndex, messageHistory.size()));

    messageHistory.clear();
    messageHistory.addAll(newHistory);
    // Unknown until the next request; conservative reset so a stale large value doesn't
    // suppress the next legitimate compaction check.
    lastPromptTokens.set(0);

    addMessageLogEntry(
        "COMPACTION", null, "Compacted " + toSummarize.size() + " messages into a summary");
  }

  private String requestSummary(List<JsonObject> messagesToSummarize)
      throws IOException, InterruptedException {
    StringBuilder transcript = new StringBuilder();
    for (JsonObject message : messagesToSummarize) {
      String role = readString(message, "role");
      String content = readString(message, "content");
      transcript.append(role != null ? role : "unknown").append(": ");
      if (content != null && content.length() > 2000) {
        transcript.append(content, 0, 2000).append(" ...[truncated]");
      } else if (content != null) {
        transcript.append(content);
      }
      transcript.append('\n');
    }

    JsonObject summaryRequest =
        createMessage(
            "user",
            """
        Summarize the following conversation transcript into a concise factual note. Preserve:
        (a) any RDF/TURTLE triples already added or removed and their subjects,
        (b) outstanding TODOs/instructions still pending,
        (c) any tool results that are still relevant,
        (d) explicit decisions made.
        Do not include pleasantries. Target under 500 words.

        Transcript:
        """
                + transcript);

    JsonArray messages = new JsonArray();
    messages.add(summaryRequest);

    JsonObject payload = new JsonObject();
    payload.addProperty("model", config.getModel());
    payload.addProperty("stream", false);
    contributeAdditionalPayloadFields(payload);
    payload.add("messages", messages);

    long summaryTimeoutMs = Math.max(1000, config.getTimeoutMs() / 2);
    JsonObject response = sendChatRequest(payload, summaryTimeoutMs);
    String content = extractResponseContent(response);
    return content != null ? content : "";
  }

  // --- Message bookkeeping ---

  protected JsonObject createMessage(String role, String content) {
    JsonObject message = new JsonObject();
    message.addProperty("role", role);
    message.addProperty("content", content == null ? "" : content);
    return message;
  }

  protected void addMessageLogEntry(String direction, String messageId, String content) {
    messageLog.add(
        new SessionMessageLogEntry(
            Instant.now().toString(), direction, messageId, content == null ? "" : content));
  }

  protected void markAssistantActivity() {
    lastAssistantActivityMs.set(System.currentTimeMillis());
  }

  private void resetConversationState() {
    messageHistory.clear();
    if (config.getSystemContext() != null && config.getSystemContext().getContent() != null) {
      messageHistory.add(createMessage("system", config.getSystemContext().getContent()));
    }
  }
}
