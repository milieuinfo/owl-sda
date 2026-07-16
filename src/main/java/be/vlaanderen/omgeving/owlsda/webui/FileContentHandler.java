package be.vlaanderen.omgeving.owlsda.webui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.function.Supplier;

/** Serves a {@link RunDataReader.TextFile} as JSON, truncating very large files for the browser. */
class FileContentHandler implements HttpHandler {

  private static final int MAX_CONTENT_CHARS = 2_000_000;
  private static final Gson GSON = new Gson();

  private final Supplier<RunDataReader.TextFile> fileSupplier;

  FileContentHandler(Supplier<RunDataReader.TextFile> fileSupplier) {
    this.fileSupplier = fileSupplier;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    RunDataReader.TextFile file = fileSupplier.get();

    boolean truncated = file.content().length() > MAX_CONTENT_CHARS;
    String content = truncated ? file.content().substring(0, MAX_CONTENT_CHARS) : file.content();

    JsonObject response = new JsonObject();
    response.addProperty("path", file.path());
    response.addProperty("exists", file.exists());
    response.addProperty("sizeBytes", file.sizeBytes());
    response.addProperty("truncated", truncated);
    response.addProperty("content", content);

    WebUiHttp.sendJson(exchange, 200, GSON.toJson(response));
  }
}
