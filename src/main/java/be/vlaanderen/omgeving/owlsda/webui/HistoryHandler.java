package be.vlaanderen.omgeving.owlsda.webui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/** Serves {@code GET /api/history}: the run's {@code benchmark-summary.json} stage timeline. */
class HistoryHandler implements HttpHandler {

  private final RunDataReader reader;

  HistoryHandler(RunDataReader reader) {
    this.reader = reader;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    WebUiHttp.sendJson(exchange, 200, reader.readHistoryJson());
  }
}
