package be.vlaanderen.omgeving.owlsda.agent.openai;

import be.vlaanderen.omgeving.owlsda.agent.AbstractHttpChatSession;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.SessionConfig;
import be.vlaanderen.omgeving.owlsda.config.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session implementation backed by an OpenAI Chat Completions-compatible /chat/completions
 * endpoint, with tool-calling support.
 */
public class OpenAiCompatibleSession extends AbstractHttpChatSession {
  private final String apiKey;

  protected OpenAiCompatibleSession(
      SessionConfig config, String baseUrl, String apiKey, HttpClient httpClient) {
    this(config, baseUrl, apiKey, httpClient, new Config.CompactionProperties());
  }

  protected OpenAiCompatibleSession(
      SessionConfig config,
      String baseUrl,
      String apiKey,
      HttpClient httpClient,
      Config.CompactionProperties compactionProperties) {
    super(config, URI.create(baseUrl + "/chat/completions"), httpClient, compactionProperties);
    this.apiKey = apiKey;
  }

  @Override
  protected String providerLabel() {
    return "OpenAI-compatible";
  }

  @Override
  protected boolean isProviderCompactionEnabled() {
    return compactionProperties.isOpenaiCompatibleEnabled();
  }

  @Override
  protected void contributeRequestHeaders(HttpRequest.Builder builder) {
    builder.header("Authorization", "Bearer " + apiKey);
  }

  @Override
  protected String extractResponseContent(JsonObject response) {
    return parseAssistantTurn(response).content();
  }

  @Override
  protected ResponseMessage executeConversation() throws Exception {
    while (true) {
      maybeCompactHistory();
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

  static ParsedAssistantTurn parseAssistantTurn(JsonObject response) {
    JsonObject message = new JsonObject();
    if (response.has("choices")
        && response.get("choices").isJsonArray()
        && !response.getAsJsonArray("choices").isEmpty()) {
      JsonElement firstChoice = response.getAsJsonArray("choices").get(0);
      if (firstChoice.isJsonObject()
          && firstChoice.getAsJsonObject().has("message")
          && firstChoice.getAsJsonObject().get("message").isJsonObject()) {
        message = firstChoice.getAsJsonObject().getAsJsonObject("message");
      }
    }

    String content = readString(message, "content");
    List<ToolCall> toolCalls = parseToolCalls(message);

    JsonObject usage =
        response.has("usage") && response.get("usage").isJsonObject()
            ? response.getAsJsonObject("usage")
            : new JsonObject();
    long promptTokens = readLong(usage, "prompt_tokens");
    long completionTokens = readLong(usage, "completion_tokens");

    return new ParsedAssistantTurn(
        content, toolCalls, message.deepCopy(), promptTokens, completionTokens);
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
      JsonObject function =
          toolCallObject.has("function") && toolCallObject.get("function").isJsonObject()
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

  private void accumulateUsage(ParsedAssistantTurn turn) {
    inputTokensUsed.addAndGet(Math.max(0, turn.promptTokens()));
    outputTokensUsed.addAndGet(Math.max(0, turn.completionTokens()));
    recordTotalTokens();
    lastPromptTokens.set(Math.max(0, turn.promptTokens()));
  }

  record ParsedAssistantTurn(
      String content,
      List<ToolCall> toolCalls,
      JsonObject assistantMessage,
      long promptTokens,
      long completionTokens) {}
}
