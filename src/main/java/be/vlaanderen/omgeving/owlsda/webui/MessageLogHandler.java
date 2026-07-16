package be.vlaanderen.omgeving.owlsda.webui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

/**
 * Serves {@code GET /api/messages?role=supervisor}: that role's message log, with {@code
 * TOOL_INVOCATION} entries enriched with a best-effort parsed tool name/arguments.
 */
class MessageLogHandler implements HttpHandler {

  private static final Gson GSON = new Gson();

  private final RunDataReader reader;

  MessageLogHandler(RunDataReader reader) {
    this.reader = reader;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Map<String, String> params = WebUiHttp.queryParams(exchange);
    String role = params.get("role");
    String rawJson = reader.readMessageLog(role);

    if (rawJson == null) {
      WebUiHttp.sendJson(exchange, 404, "{\"error\":\"unknown role\"}");
      return;
    }

    JsonArray entries;
    try {
      entries = JsonParser.parseString(rawJson).getAsJsonArray();
    } catch (RuntimeException e) {
      WebUiHttp.sendJson(exchange, 200, "[]");
      return;
    }

    for (JsonElement element : entries) {
      if (!element.isJsonObject()) {
        continue;
      }
      JsonObject entry = element.getAsJsonObject();
      String direction = entry.has("direction") ? entry.get("direction").getAsString() : null;
      String content = entry.has("content") ? entry.get("content").getAsString() : null;

      if ("TOOL_INVOCATION".equals(direction)) {
        ToolCallParser.ParsedToolCall parsed = ToolCallParser.parse(content);
        if (parsed != null) {
          JsonObject tool = new JsonObject();
          tool.addProperty("name", parsed.name());
          tool.addProperty("arguments", parsed.arguments());
          tool.addProperty("argumentsAreJson", parsed.argumentsAreJson());
          entry.add("tool", tool);
        }
      }
    }

    WebUiHttp.sendJson(exchange, 200, GSON.toJson(entries));
  }
}
