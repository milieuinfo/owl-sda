package be.vlaanderen.omgeving.owlsda.webui;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.file.Files;

/** Serves {@code GET /api/state}: current stage, metadata, and which roles have messages. */
class RunStateHandler implements HttpHandler {

  private static final Gson GSON = new Gson();

  private final RunDataReader reader;

  RunStateHandler(RunDataReader reader) {
    this.reader = reader;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    JsonObject response = new JsonObject();
    response.addProperty("benchmarkEnabled", reader.benchmarkEnabled());
    response.addProperty(
        "benchmarkDirExists", reader.benchmarkDir() != null && Files.exists(reader.benchmarkDir()));

    JsonObject metadata = new JsonObject();
    reader.readMetadata().forEach(metadata::addProperty);
    response.add("metadata", metadata);

    JsonArray roles = new JsonArray();
    reader.listRoles().forEach(roles::add);
    response.add("roles", roles);

    WebUiHttp.sendJson(exchange, 200, GSON.toJson(response));
  }
}
