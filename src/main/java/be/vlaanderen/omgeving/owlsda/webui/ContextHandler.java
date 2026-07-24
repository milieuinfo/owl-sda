package be.vlaanderen.omgeving.owlsda.webui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.Map;

/**
 * Serves {@code GET /api/contexts?role=worker_0}: the role's current named context files (e.g.
 * {@code Delegation Instructions}, {@code Validation Report}, {@code Ontology Summary}) - the same
 * context the session itself sees for the round in progress.
 */
class ContextHandler implements HttpHandler {

  private static final Gson GSON = new Gson();

  private final RunDataReader reader;

  ContextHandler(RunDataReader reader) {
    this.reader = reader;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    Map<String, String> params = WebUiHttp.queryParams(exchange);
    String role = params.get("role");

    JsonArray response = new JsonArray();
    for (RunDataReader.ContextFile context : reader.readContexts(role)) {
      JsonObject entry = new JsonObject();
      entry.addProperty("name", context.name());
      entry.addProperty("content", context.content());
      response.add(entry);
    }

    WebUiHttp.sendJson(exchange, 200, GSON.toJson(response));
  }
}
