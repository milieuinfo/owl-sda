package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler that replaces specific line ranges in the output TURTLE file. Preserves content outside
 * the specified range while replacing the target lines.
 */
public class OutputReplaceHandler implements SessionHandler {
  public static final String NAME = "output_data_replace";

  private static final Logger logger = LoggerFactory.getLogger(OutputReplaceHandler.class);
  private final Config config;

  public OutputReplaceHandler(Config config) {
    this.config = config;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return """
      Replaces a specific line range in the output TURTLE file.
      Content outside the specified range is preserved.
      Useful for correcting specific sections without rewriting the entire file.

      Line numbers are 1-based (first line is 1).
      If start_line/end_line are not provided, replaces the entire file.
    """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties",
            Map.of(
                "output",
                    Map.of(
                        "type", "string",
                        "description", "Replacement TURTLE data"),
                "start_line",
                    Map.of(
                        "type", "integer",
                        "description",
                            "Start line number to replace (1-based, inclusive). Default: 1"),
                "end_line",
                    Map.of(
                        "type", "integer",
                        "description",
                            "End line number to replace (1-based, inclusive). Default: last line")),
        "required", List.of("output"));
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String output = (String) arguments.get("output");
    String outputFilePath = config.getOutputPath();

    if (outputFilePath == null) {
      logger.error("Output path is not configured");
      return CompletableFuture.completedFuture(Map.of("error", "Output path is not configured"));
    }

    if (output == null) {
      logger.warn("No output provided to replace");
      return CompletableFuture.completedFuture(Map.of("error", "No output provided"));
    }

    try {
      Path path = Path.of(outputFilePath);

      // Read existing file content
      String existingData;
      if (Files.exists(path)) {
        existingData = Files.readString(path);
      } else {
        logger.warn("Output file does not exist, creating new file: {}", outputFilePath);
        existingData = "";
      }

      String[] lines = existingData.split("\n", -1); // -1 to preserve trailing empty lines
      int totalLines = lines.length;

      // Parse line range (convert to 0-based for array indexing)
      // Default: replace entire file if no range specified
      int startLine =
          arguments.containsKey("start_line")
              ? ((Number) arguments.get("start_line")).intValue() - 1
              : 0; // Convert to 0-based
      int endLine =
          arguments.containsKey("end_line")
              ? ((Number) arguments.get("end_line")).intValue() - 1
              : totalLines - 1; // Convert to 0-based, inclusive

      // Validate and bound line numbers
      startLine = Math.max(0, Math.min(startLine, totalLines - 1));
      endLine = Math.max(startLine, Math.min(endLine, totalLines - 1));

      // Build new content
      StringBuilder newData = new StringBuilder();

      // Add lines before replacement range
      for (int i = 0; i < startLine; i++) {
        newData.append(lines[i]);
        if (i < totalLines - 1) {
          newData.append("\n");
        }
      }

      // Add separator newline if we're inserting in the middle
      if (startLine > 0 && !newData.isEmpty() && !newData.toString().endsWith("\n")) {
        newData.append("\n");
      }

      // Add replacement content
      newData.append(output);

      // Ensure newline after replacement content
      if (!output.endsWith("\n") && endLine < totalLines - 1) {
        newData.append("\n");
      }

      // Add lines after replacement range
      for (int i = endLine + 1; i < totalLines; i++) {
        if (!newData.toString().endsWith("\n") && !newData.isEmpty()) {
          newData.append("\n");
        }
        newData.append(lines[i]);
        if (i < totalLines - 1) {
          newData.append("\n");
        }
      }

      String syntaxError = TurtleSyntaxValidator.validate(newData.toString());
      if (syntaxError != null) {
        logger.warn("Rejected output_data_replace with invalid Turtle: {}", syntaxError);
        return CompletableFuture.completedFuture(
            Map.of(
                "error",
                "Resulting output would not be valid Turtle and was not written: " + syntaxError));
      }

      // Write to file
      Files.writeString(
          path,
          newData.toString(),
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);

      int linesReplaced = endLine - startLine + 1;
      int newLines = output.split("\n", -1).length;
      long fileSize = Files.size(path);
      int finalTotalLines = newData.toString().split("\n", -1).length;

      logger.info(
          "Replaced lines {}-{} ({} lines) with new content ({} lines) in output file: {}",
          startLine + 1,
          endLine + 1,
          linesReplaced,
          newLines,
          outputFilePath);

      return CompletableFuture.completedFuture(
          Map.of(
              "status", "success",
              "message", "Lines replaced in output file",
              "file_path", outputFilePath,
              "start_line", startLine + 1, // Return 1-based line numbers
              "end_line", endLine + 1, // Return 1-based line numbers
              "lines_replaced", linesReplaced,
              "new_lines", newLines,
              "total_lines_before", totalLines,
              "total_lines_after", finalTotalLines,
              "file_size", fileSize));
    } catch (IOException e) {
      logger.error("Failed to replace output in file: {}", outputFilePath, e);
      return CompletableFuture.completedFuture(
          Map.of("error", "Failed to replace output: " + e.getMessage()));
    } catch (Exception e) {
      logger.error("Error processing output replacement: {}", e.getMessage(), e);
      return CompletableFuture.completedFuture(
          Map.of("error", "Error processing replacement: " + e.getMessage()));
    }
  }
}
