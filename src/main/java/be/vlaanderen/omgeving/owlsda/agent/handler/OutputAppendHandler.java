package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler that appends data to the output TURTLE file.
 * Useful for incrementally building output without rewriting the entire file.
 *
 * Automatically deduplicates @prefix and @base declarations by stripping them from the input
 * if the output file already contains prefixes, preventing duplicate declarations.
 */
public class OutputAppendHandler implements SessionHandler {
  public static final String NAME = "output_data_append";

  private final Logger logger = LoggerFactory.getLogger(OutputAppendHandler.class);
  private final Config config;

  public OutputAppendHandler(Config config) {
    this.config = config;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return """
      Appends or inserts data to the output TURTLE file.
      - Append: Add at the end (default)
      - Insert: Add at specific line number
      - Deduplicates @prefix/@base declarations: automatically removes them from input if file already has prefixes
    """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "output", Map.of(
                "type", "string",
                "description", "Output TURTLE data to append or insert"
            ),
            "insert-line", Map.of(
                "type", "integer",
                "description", "Optional: Insert at this line number (1-based). If not provided, appends at end."
            ),
            "newline", Map.of(
                "type", "boolean",
                "description", "Add newline before appending (default: true)"
            )
        ),
        "required", List.of("output")
    );
  }


  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String output = (String) arguments.get("output");
    boolean addNewline = (boolean) arguments.getOrDefault("newline", true);
    Integer insertLine = arguments.containsKey("insert-line") ? ((Number) arguments.get("insert-line")).intValue() : null;
    String outputFilePath = config.getOutputPath();

    if (outputFilePath == null) {
      logger.error("Output path is not configured");
      return CompletableFuture.completedFuture(Map.of(
          "error", "Output path is not configured"
      ));
    }

    if (output == null || output.isEmpty()) {
      logger.warn("No output provided");
      return CompletableFuture.completedFuture(Map.of(
          "error", "No output provided"
      ));
    }

    try {
      Path path = Path.of(outputFilePath);
      long sizeBefore = Files.exists(path) ? Files.size(path) : 0;
      String existingContent = Files.exists(path) ? Files.readString(path) : "";

      // Deduplicate prefixes: strip them from output if the file already contains prefixes
      String processedOutput = deduplicatePrefixes(outputFilePath, output);
      int prefixesRemoved = countPrefixLines(output) - countPrefixLines(processedOutput);

      if (insertLine != null && insertLine > 0) {
        // Insert at specific line
        String newFullContent = buildInsertedContent(existingContent, processedOutput, insertLine);
        String syntaxError = TurtleSyntaxValidator.validate(newFullContent);
        if (syntaxError != null) {
          logger.warn("Rejected output_data_append insert with invalid Turtle: {}", syntaxError);
          return CompletableFuture.completedFuture(Map.of(
              "error", "Resulting output would not be valid Turtle and was not written: " + syntaxError
          ));
        }
        Files.writeString(path, newFullContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Inserted {} characters at line {} ({} prefixes removed)",
            processedOutput.length(), insertLine, prefixesRemoved);
      } else {
        // Append at end
        String contentToAppend = processedOutput;
        if (addNewline && sizeBefore > 0) {
          contentToAppend = System.lineSeparator() + processedOutput;
        }
        String newFullContent = existingContent + contentToAppend;
        String syntaxError = TurtleSyntaxValidator.validate(newFullContent);
        if (syntaxError != null) {
          logger.warn("Rejected output_data_append with invalid Turtle: {}", syntaxError);
          return CompletableFuture.completedFuture(Map.of(
              "error", "Resulting output would not be valid Turtle and was not written: " + syntaxError
          ));
        }
        Files.writeString(path, contentToAppend, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        logger.info("Appended {} characters ({} prefixes removed)",
            processedOutput.length(), prefixesRemoved);
      }

      long sizeAfter = Files.exists(path) ? Files.size(path) : 0;
      long bytesAdded = sizeAfter - sizeBefore;
      int linesAdded = processedOutput.split("\n").length;

      String operation = (insertLine != null && insertLine > 0) ? "insert" : "append";
      return CompletableFuture.completedFuture(Map.of(
          "status", "success",
          "operation", operation,
          "message", "Output " + operation + "ed to file" + (prefixesRemoved > 0 ? " (" + prefixesRemoved + " duplicate prefixes removed)" : ""),
          "file_path", outputFilePath,
          "line_number", insertLine != null ? insertLine : -1,
          "characters_added", processedOutput.length(),
          "lines_added", linesAdded,
          "bytes_added", bytesAdded,
          "total_size", sizeAfter,
          "prefixes_deduplicated", prefixesRemoved
      ));
    } catch (IOException e) {
      logger.error("Failed to modify output file: {}", outputFilePath, e);
      return CompletableFuture.completedFuture(Map.of(
          "error", "Failed to modify output: " + e.getMessage()
      ));
    }
  }

  /**
   * Removes @prefix and @base declarations from output if the file already contains them.
   * This prevents duplicate prefix declarations when workers append data independently.
   */
  private String deduplicatePrefixes(String filePath, String output) throws IOException {
    try {
      Path path = Path.of(filePath);
      String fileContent = Files.exists(path) ? Files.readString(path) : "";

      // If file has prefixes, strip them from the output being appended
      if (hasPrefixes(fileContent)) {
        return stripPrefixes(output);
      }
    } catch (IOException e) {
      logger.warn("Could not read file for prefix deduplication: {}", e.getMessage());
      // Fall through and return output as-is if file doesn't exist yet
    }

    return output;
  }

  /**
   * Check if content contains any @prefix or @base declarations.
   */
  private boolean hasPrefixes(String content) {
    if (content == null || content.isEmpty()) {
      return false;
    }

    String[] lines = content.split("\n");
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.startsWith("@prefix") || trimmed.startsWith("@base")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Removes @prefix and @base declarations from content.
   */
  private String stripPrefixes(String content) {
    String[] lines = content.split("\n");
    List<String> dataLines = new ArrayList<>();
    boolean foundData = false;

    for (String line : lines) {
      String trimmed = line.trim();

      // Skip prefix/base declarations
      if (trimmed.startsWith("@prefix") || trimmed.startsWith("@base")) {
        continue;
      }

      // Skip empty lines before data
      if (!foundData && trimmed.isEmpty()) {
        continue;
      }

      if (!trimmed.isEmpty()) {
        foundData = true;
      }

      dataLines.add(line);
    }

    return String.join("\n", dataLines).trim();
  }

  /**
   * Counts @prefix and @base lines in content.
   */
  private int countPrefixLines(String content) {
    if (content == null || content.isEmpty()) {
      return 0;
    }

    String[] lines = content.split("\n");
    int count = 0;

    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.startsWith("@prefix") || trimmed.startsWith("@base")) {
        count++;
      }
    }

    return count;
  }

  /**
   * Build the file content that results from inserting output at a specific line.
   * Line numbers are 1-based (line 1 is the first line).
   */
  private String buildInsertedContent(String existingContent, String output, int lineNumber) throws IOException {
    String[] lines = existingContent.split("\n", -1);  // -1 keeps trailing empty strings

    // Validate line number
    if (lineNumber < 1 || lineNumber > lines.length + 1) {
      throw new IOException("Line number " + lineNumber + " is out of range (1-" + (lines.length + 1) + ")");
    }

    // Build new content
    StringBuilder newContent = new StringBuilder();
    for (int i = 0; i < lineNumber - 1 && i < lines.length; i++) {
      newContent.append(lines[i]).append("\n");
    }

    // Add the new output
    newContent.append(output);

    // Add remaining lines
    if (lineNumber - 1 < lines.length) {
      newContent.append("\n");
      for (int i = lineNumber - 1; i < lines.length; i++) {
        newContent.append(lines[i]);
        if (i < lines.length - 1) {
          newContent.append("\n");
        }
      }
    }

    return newContent.toString();
  }
}
