package be.vlaanderen.omgeving.owlsda.webui;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small response/query-string helpers shared by the web UI's HTTP handlers. */
final class WebUiHttp {

  private WebUiHttp() {}

  static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
    send(exchange, status, "application/json; charset=utf-8", json);
  }

  static void sendText(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    send(exchange, status, contentType, body);
  }

  private static void send(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  static Map<String, String> queryParams(HttpExchange exchange) {
    URI uri = exchange.getRequestURI();
    String query = uri.getRawQuery();
    Map<String, String> params = new LinkedHashMap<>();
    if (query == null || query.isBlank()) {
      return params;
    }
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq >= 0 ? pair.substring(0, eq) : pair;
      String value = eq >= 0 ? pair.substring(eq + 1) : "";
      params.put(
          java.net.URLDecoder.decode(key, StandardCharsets.UTF_8),
          java.net.URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return params;
  }
}
