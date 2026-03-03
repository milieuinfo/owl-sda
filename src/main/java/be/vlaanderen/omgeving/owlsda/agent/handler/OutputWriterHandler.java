package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler that writes output data to a TURTLE file.
 * Supports overwriting the entire file with validation and size reporting.
 */
public class OutputWriterHandler implements SessionHandler {
  private final Logger logger = LoggerFactory.getLogger(OutputWriterHandler.class);
  private final Config config;

  public OutputWriterHandler(Config config) {
    this.config = config;
  }

  @Override
  public String getName() {
    return "output_data_writer";
  }

  @Override
  public String getDescription() {
    return """
      Writes output data to a TURTLE file, overwriting the entire file.
      Empty output will delete the file.
    """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "output", Map.of(
                "type", "string",
                "description", "Output TURTLE data (empty to delete file)"
            )
        ),
        "required", List.of("output")
    );
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String output = (String) arguments.get("output");
    String outputFilePath = config.getOutputPath();

    if (outputFilePath == null) {
      logger.error("Output path is not configured");
      return CompletableFuture.completedFuture(Map.of(
          "error", "Output path is not configured"
      ));
    }

    Path path = Path.of(outputFilePath);

    // Handle empty output - delete file
    if (output == null || output.isEmpty()) {
      try {
        if (Files.exists(path)) {
          Files.delete(path);
          logger.info("Deleted existing output file: {}", outputFilePath);
          return CompletableFuture.completedFuture(Map.of(
              "status", "deleted",
              "message", "Output file has been deleted"
          ));
        } else {
          logger.info("No output file to delete: {}", outputFilePath);
          return CompletableFuture.completedFuture(Map.of(
              "status", "no_file",
              "message", "No output file exists"
          ));
        }
      } catch (IOException e) {
        logger.error("Failed to delete output file: {}", outputFilePath, e);
        return CompletableFuture.completedFuture(Map.of(
            "error", "Failed to delete output file: " + e.getMessage()
        ));
      }
    }

    // Write output to file directly (single-writer supervisor flow)
    try {
      Files.writeString(path, output);
      long fileSize = Files.size(path);
      int lineCount = output.split("\n").length;

      logger.info("Successfully written output to file: {} ({} characters, {} lines, {} bytes)",
          outputFilePath, output.length(), lineCount, fileSize);

      return CompletableFuture.completedFuture(Map.of(
          "status", "success",
          "message", "Output written to file",
          "file_path", outputFilePath,
          "characters", output.length(),
          "lines", lineCount,
          "bytes", fileSize
      ));
    } catch (IOException e) {
      logger.error("Failed to write output to file: {}", outputFilePath, e);
      return CompletableFuture.completedFuture(Map.of(
          "error", "Failed to write output: " + e.getMessage()
      ));
    }
  }
}
