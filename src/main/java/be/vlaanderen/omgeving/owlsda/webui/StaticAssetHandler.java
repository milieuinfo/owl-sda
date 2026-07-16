package be.vlaanderen.omgeving.owlsda.webui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Serves the dashboard's static assets from the classpath (packaged under {@code /webui/static/}).
 * Requests are matched against a fixed allowlist rather than the filesystem, so there's no
 * path-traversal surface.
 */
class StaticAssetHandler implements HttpHandler {

  private static final Map<String, String> ROUTES =
      Map.of(
          "/", "index.html",
          "/index.html", "index.html",
          "/app.js", "app.js",
          "/style.css", "style.css");

  private static final Map<String, String> CONTENT_TYPES =
      Map.of(
          "html", "text/html; charset=utf-8",
          "js", "application/javascript; charset=utf-8",
          "css", "text/css; charset=utf-8");

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String resourceName = ROUTES.get(path);

    if (resourceName == null) {
      WebUiHttp.sendText(exchange, 404, "text/plain; charset=utf-8", "Not found: " + path);
      return;
    }

    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("webui/static/" + resourceName)) {
      if (in == null) {
        WebUiHttp.sendText(
            exchange, 500, "text/plain; charset=utf-8", "Missing asset: " + resourceName);
        return;
      }
      String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      String extension = resourceName.substring(resourceName.lastIndexOf('.') + 1);
      String contentType = CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
      WebUiHttp.sendText(exchange, 200, contentType, content);
    }
  }
}
