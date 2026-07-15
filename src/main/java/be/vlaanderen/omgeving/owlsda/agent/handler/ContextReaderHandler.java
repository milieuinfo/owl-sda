package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler that provides access to context information without including it in every message. This
 * prevents issues with large context that doesn't work well in prompts. Supports chunked reading of
 * large context to avoid token limits. Tracks context changes to notify about updates since last
 * query.
 */
public class ContextReaderHandler implements SessionHandler {

  public static final String NAME = "context_reader";

  private final Logger logger = LoggerFactory.getLogger(ContextReaderHandler.class);
  private final Supplier<List<Context>> contextSupplier;

  // Track context hashes to detect changes
  private final Map<String, Integer> contextHashMap = new HashMap<>();

  public ContextReaderHandler(Supplier<List<Context>> contextSupplier) {
    this.contextSupplier = contextSupplier;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return """
        This handler provides access to context information available in the session.
        You can list available contexts, read specific context by name, or read context in chunks.
        This handler also tracks which contexts have changed since your last query.
        This prevents large context from being sent with every message.
        """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties",
            Map.of(
                "action",
                    Map.of(
                        "type",
                        "string",
                        "enum",
                        List.of("list", "read", "read_chunk", "get_changes"),
                        "description",
                        "Action: 'list' for contexts, 'read' entire context, 'read_chunk' for chunks, 'get_changes' for what changed since last query"),
                "name",
                    Map.of(
                        "type",
                        "string",
                        "description",
                        "Name of the context to read (required for 'read' and 'read_chunk' actions)"),
                "start",
                    Map.of(
                        "type", "integer",
                        "description", "Start character position for chunk reading (default: 0)"),
                "length",
                    Map.of(
                        "type", "integer",
                        "description", "Number of characters to read in chunk (default: 10000)")),
        "required", List.of("action"));
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String action = (String) arguments.get("action");

    if ("list".equals(action)) {
      return handleList();
    } else if ("read".equals(action) || "read_chunk".equals(action)) {
      return handleRead(arguments, "read_chunk".equals(action));
    } else if ("get_changes".equals(action)) {
      return handleGetChanges();
    } else {
      logger.warn("Unknown action: {}", action);
      return CompletableFuture.completedFuture(
          Map.of(
              "error",
              "Unknown action: "
                  + action
                  + ". Use 'list', 'read', 'read_chunk', or 'get_changes'."));
    }
  }

  /** Handle the 'list' action to return all available contexts with change indicators. */
  private CompletableFuture<Object> handleList() {
    List<Context> contexts = contextSupplier.get();
    List<Map<String, Object>> contextList =
        contexts.stream()
            .map(
                ctx -> {
                  String content = ctx.getContent();
                  int contentLength = content != null ? content.length() : 0;
                  int currentHash = content != null ? content.hashCode() : 0;
                  String contextName = ctx.getName() != null ? ctx.getName() : "unknown";

                  // Check if this context changed since last query
                  Integer previousHash = contextHashMap.get(contextName);
                  boolean changed = previousHash == null || !previousHash.equals(currentHash);

                  // Update the hash for next comparison
                  contextHashMap.put(contextName, currentHash);

                  return Map.of(
                      "name",
                      (Object) contextName,
                      "type",
                      ctx.getType() != null ? ctx.getType() : "unknown",
                      "size",
                      contentLength,
                      "changed",
                      changed);
                })
            .toList();

    logger.info("Retrieved list of {} available contexts", contextList.size());

    // Count how many contexts changed
    long changedCount = contextList.stream().filter(ctx -> (boolean) ctx.get("changed")).count();

    return CompletableFuture.completedFuture(
        Map.of(
            "contexts",
            contextList,
            "count",
            contextList.size(),
            "changed_count",
            changedCount,
            "message",
            changedCount > 0
                ? "There are " + changedCount + " changed context(s) since your last query"
                : "No changes since last query"));
  }

  /** Handle the 'read' or 'read_chunk' actions to read context content. */
  private CompletableFuture<Object> handleRead(Map<String, Object> arguments, boolean isChunk) {
    String contextName = (String) arguments.get("name");
    if (contextName == null || contextName.isEmpty()) {
      logger.warn("Context name not provided for read action");
      return errorResult("Context name is required for read action");
    }

    // Find context by name
    List<Context> contexts = contextSupplier.get();
    Context foundContext =
        contexts.stream().filter(ctx -> contextName.equals(ctx.getName())).findFirst().orElse(null);

    if (foundContext == null) {
      logger.warn("Context with name '{}' not found", contextName);
      return errorResult("Context with name '" + contextName + "' not found");
    }

    String content = foundContext.getContent();
    if (content == null) {
      logger.warn("Context '{}' has no content", contextName);
      return errorResult("Context has no content");
    }

    // Update hash for this context
    int currentHash = content.hashCode();
    contextHashMap.put(contextName, currentHash);

    // Handle chunked reading
    if (isChunk) {
      int start = arguments.containsKey("start") ? ((Number) arguments.get("start")).intValue() : 0;
      int length =
          arguments.containsKey("length") ? ((Number) arguments.get("length")).intValue() : 10000;

      int totalLength = content.length();
      int end = Math.min(start + length, totalLength);

      if (start >= totalLength) {
        logger.warn("Start position {} is beyond content length {}", start, totalLength);
        return errorResult("Start position is beyond content length");
      }

      String chunk = content.substring(start, end);
      boolean hasMore = end < totalLength;

      logger.info(
          "Read chunk from context '{}' ({} chars from position {}/{})",
          contextName,
          chunk.length(),
          start,
          totalLength);
      return CompletableFuture.completedFuture(
          Map.of(
              "name", contextName,
              "type", foundContext.getType() != null ? foundContext.getType() : "unknown",
              "content", chunk,
              "chunk_start", start,
              "chunk_end", end,
              "total_length", totalLength,
              "has_more", hasMore));
    } else {
      // Read entire context
      logger.info("Read context '{}' ({} characters)", contextName, content.length());
      return CompletableFuture.completedFuture(
          Map.of(
              "name",
              contextName,
              "type",
              foundContext.getType() != null ? foundContext.getType() : "unknown",
              "content",
              content,
              "length",
              content.length()));
    }
  }

  /** Handle the 'get_changes' action to report what contexts have changed. */
  private CompletableFuture<Object> handleGetChanges() {
    List<Context> contexts = contextSupplier.get();
    List<Map<String, Object>> changes = new java.util.ArrayList<>();
    List<Map<String, Object>> unchanged = new java.util.ArrayList<>();

    for (Context ctx : contexts) {
      String content = ctx.getContent();
      int currentHash = content != null ? content.hashCode() : 0;
      String contextName = ctx.getName() != null ? ctx.getName() : "unknown";
      Integer previousHash = contextHashMap.get(contextName);

      boolean changed = previousHash == null || !previousHash.equals(currentHash);

      Map<String, Object> contextInfo = new java.util.HashMap<>();
      contextInfo.put("name", contextName);
      contextInfo.put("type", ctx.getType() != null ? ctx.getType() : "unknown");
      int contentLength = content != null ? content.length() : 0;
      contextInfo.put("size", contentLength);

      if (changed) {
        contextInfo.put("status", "changed");
        if (previousHash != null) {
          contextInfo.put("message", "Content was updated");
        } else {
          contextInfo.put("message", "New context added");
        }
        changes.add(contextInfo);
      } else {
        contextInfo.put("status", "unchanged");
        unchanged.add(contextInfo);
      }

      // Update hash for next comparison
      contextHashMap.put(contextName, currentHash);
    }

    logger.info("Reported {} changed and {} unchanged contexts", changes.size(), unchanged.size());

    return CompletableFuture.completedFuture(
        Map.of(
            "changed",
            changes,
            "unchanged",
            unchanged,
            "total_changed",
            changes.size(),
            "total_unchanged",
            unchanged.size(),
            "summary",
            changes.isEmpty()
                ? "No changes detected in any contexts"
                : changes.size() + " context(s) have changed"));
  }
}
