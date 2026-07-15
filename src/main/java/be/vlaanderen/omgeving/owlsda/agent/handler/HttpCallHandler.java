package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.agent.HttpRetryExecutor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Allows agents to make an HTTP GET or POST request to an allowlisted host. The allowlist is seeded
 * from already-trusted project configuration (external ontology mirrors, user-context URLs) plus
 * any explicit {@code tools.http.allowed-hosts} entries - see {@link HttpAllowlistFactory}.
 */
public record HttpCallHandler(
    HttpAllowlist allowlist,
    HttpClient httpClient,
    int readTimeoutMs,
    int maxResponseBodyBytes,
    boolean allowPost,
    int maxRetries)
    implements SessionHandler {

  public static final String NAME = "http_call";

  private static final Logger logger = LoggerFactory.getLogger(HttpCallHandler.class);
  private static final long RETRY_BASE_DELAY_MS = 500L;

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return """
        Make an HTTP GET or POST request to an allowlisted host.
        Only hosts already trusted by this project's configuration
        (extract.mirrors, user-context URLs, or tools.http.allowed-hosts) can be reached.

        Use this to fetch reference vocabularies, look up external identifiers,
        or call read-only APIs needed to complete your task.
        """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties",
            Map.of(
                "url",
                    Map.of(
                        "type", "string",
                        "description", "Absolute URL to request; host must be allowlisted"),
                "method",
                    Map.of(
                        "type", "string",
                        "enum", List.of("GET", "POST"),
                        "description", "HTTP method (default: GET)"),
                "body",
                    Map.of(
                        "type", "string",
                        "description", "Request body for POST (optional)")),
        "required", List.of("url"));
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String rawUrl = (String) arguments.get("url");
    String method = ((String) arguments.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
    String body = (String) arguments.get("body");

    if (rawUrl == null || rawUrl.isBlank()) {
      return errorResult("url is required");
    }

    URI uri;
    try {
      uri = new URI(rawUrl.trim());
    } catch (Exception e) {
      return errorResult("Invalid url: " + e.getMessage());
    }

    if (!allowlist.isAllowed(uri)) {
      return errorResult("Host not allowlisted: " + uri.getHost());
    }

    if (!"GET".equals(method) && !"POST".equals(method)) {
      return errorResult("Unsupported method: " + method + ". Use GET or POST.");
    }

    if ("POST".equals(method) && !allowPost) {
      return errorResult("POST requests are disabled for this tool (tools.http.allow-post=false)");
    }

    return CompletableFuture.supplyAsync(() -> executeWithRetry(uri, method, body));
  }

  private Map<String, Object> executeWithRetry(URI uri, String method, String body) {
    try {
      return HttpRetryExecutor.retry(
          maxRetries,
          RETRY_BASE_DELAY_MS,
          e -> e instanceof RetryableHttpException,
          (attempt, retries, cause) ->
              logger.warn(
                  "http_call to {} failed (attempt {}/{}): {}; retrying",
                  uri.getHost(),
                  attempt + 1,
                  retries + 1,
                  cause.getMessage()),
          () -> execute(uri, method, body));
    } catch (RetryableHttpException e) {
      return Map.of(
          "error",
          "Request failed after " + (maxRetries + 1) + " attempt(s): " + e.getMessage());
    } catch (Exception e) {
      return Map.of("error", "Request failed: " + e.getMessage());
    }
  }

  private Map<String, Object> execute(URI uri, String method, String body) throws Exception {
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(readTimeoutMs));

    if ("POST".equals(method)) {
      requestBuilder.POST(BodyPublishers.ofString(body != null ? body : ""));
    } else {
      requestBuilder.GET();
    }

    HttpResponse<String> response;
    try {
      response = httpClient.send(requestBuilder.build(), BodyHandlers.ofString());
    } catch (java.io.IOException e) {
      throw new RetryableHttpException(e.getMessage(), e);
    }

    if (response.statusCode() >= 500) {
      throw new RetryableHttpException("HTTP " + response.statusCode(), null);
    }

    String responseBody = response.body() != null ? response.body() : "";
    boolean truncated = responseBody.length() > maxResponseBodyBytes;
    if (truncated) {
      responseBody = responseBody.substring(0, maxResponseBodyBytes);
    }

    return Map.of(
        "status", response.statusCode(),
        "body", responseBody,
        "truncated", truncated);
  }

  private static final class RetryableHttpException extends Exception {
    RetryableHttpException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
