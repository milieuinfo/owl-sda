package be.vlaanderen.omgeving.owlsda.agent.ollama;

import be.vlaanderen.omgeving.owlsda.agent.AbstractHttpChatSession;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.SessionConfig;
import be.vlaanderen.omgeving.owlsda.config.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Session implementation backed by Ollama's /api/chat endpoint with tool-calling support. */
public class OllamaSession extends AbstractHttpChatSession {
  private final boolean think;

  protected OllamaSession(SessionConfig config, String baseUrl, HttpClient httpClient) {
    this(config, baseUrl, httpClient, new Config.CompactionProperties(), true);
  }

  protected OllamaSession(
      SessionConfig config,
      String baseUrl,
      HttpClient httpClient,
      Config.CompactionProperties compactionProperties) {
    this(config, baseUrl, httpClient, compactionProperties, true);
  }

  protected OllamaSession(
      SessionConfig config,
      String baseUrl,
      HttpClient httpClient,
      Config.CompactionProperties compactionProperties,
      boolean think) {
    super(config, URI.create(baseUrl + "/api/chat"), httpClient, compactionProperties);
    this.think = think;
  }

  @Override
  protected String providerLabel() {
    return "Ollama";
  }

  @Override
  protected boolean isProviderCompactionEnabled() {
    return compactionProperties.isOllamaEnabled();
  }

  @Override
  protected void contributeAdditionalPayloadFields(JsonObject payload) {
    if (!think) {
      payload.addProperty("think", false);
    }
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

      // Some models narrate their reasoning alongside tool calls in the same turn; without this,
      // that commentary is silently dropped since only the final tool-free turn gets logged above.
      if (turn.content() != null && !turn.content().isBlank()) {
        addMessageLogEntry("INBOUND", UUID.randomUUID().toString(), turn.content());
      }

      for (ToolCall toolCall : turn.toolCalls()) {
        executeToolCall(toolCall);
      }
    }
  }

  static ParsedAssistantTurn parseAssistantTurn(JsonObject response) {
    JsonObject message =
        response.has("message") && response.get("message").isJsonObject()
            ? response.getAsJsonObject("message")
            : new JsonObject();

    String content = readString(message, "content");
    List<ToolCall> toolCalls = parseToolCalls(message);

    long promptEvalCount = readLong(response, "prompt_eval_count");
    long evalCount = readLong(response, "eval_count");

    return new ParsedAssistantTurn(
        content, toolCalls, message.deepCopy(), promptEvalCount, evalCount);
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
      // Ollama's protocol has no call-id concept; leave it null so the shared executeToolCall
      // never echoes a tool_call_id back, matching this provider's expected message shape.
      calls.add(new ToolCall(null, name, arguments));
    }

    return calls;
  }

  private void accumulateUsage(ParsedAssistantTurn turn) {
    inputTokensUsed.addAndGet(Math.max(0, turn.promptEvalCount()));
    outputTokensUsed.addAndGet(Math.max(0, turn.evalCount()));
    recordTotalTokens();
    lastPromptTokens.set(Math.max(0, turn.promptEvalCount()));
  }

  record ParsedAssistantTurn(
      String content,
      List<ToolCall> toolCalls,
      JsonObject assistantMessage,
      long promptEvalCount,
      long evalCount) {}
}
