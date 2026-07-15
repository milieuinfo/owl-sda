package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler that reads output data from a TURTLE file. Supports both line-based and character-based
 * chunked reading for efficient handling of large files.
 */
public class OutputReaderHandler implements SessionHandler {
  public static final String NAME = "output_data_reader";

  private final Logger logger = LoggerFactory.getLogger(OutputReaderHandler.class);
  private final Config config;

  public OutputReaderHandler(Config config) {
    this.config = config;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return """
      This handler reads the output data from a TURTLE file.
      Supports reading entire file, by line range, or by character range for large files.
      Use 'read_chunk' mode for very large files to avoid token limits.
    """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type",
        "object",
        "properties",
        Map.of(
            "mode",
                Map.of(
                    "type", "string",
                    "enum", List.of("full", "lines", "chunk"),
                    "description",
                        "Reading mode: 'full' reads entire file, 'lines' reads line range, 'chunk' reads character range (default: full)"),
            "start_line",
                Map.of(
                    "type", "integer",
                    "description", "Start line number for 'lines' mode (default: 0)"),
            "end_line",
                Map.of(
                    "type", "integer",
                    "description", "End line number for 'lines' mode (default: end of file)"),
            "start",
                Map.of(
                    "type", "integer",
                    "description", "Start character position for 'chunk' mode (default: 0)"),
            "length",
                Map.of(
                    "type", "integer",
                    "description",
                        "Number of characters to read for 'chunk' mode (default: 10000)")));
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String mode = (String) arguments.getOrDefault("mode", "full");
    String outputFilePath = config.getOutputPath();

    if (outputFilePath == null) {
      logger.error("Output path is not configured");
      return errorResult("Output path is not configured");
    }

    Path path = Path.of(outputFilePath);
    if (!Files.exists(path)) {
      logger.warn("Output file does not exist: {}", outputFilePath);
      return CompletableFuture.completedFuture(
          Map.of(
              "error", "Output file does not exist",
              "content", ""));
    }

    try {
      switch (mode) {
        case "lines" -> {
          return handleLinesMode(path, arguments);
        }
        case "chunk" -> {
          return handleChunkMode(path, arguments);
        }
        default -> {
          return handleFullMode(path);
        }
      }
    } catch (IOException e) {
      logger.error("Failed to read output from file: {}", outputFilePath, e);
      return errorResult("Failed to read output: " + e.getMessage());
    }
  }

  private CompletableFuture<Object> handleFullMode(Path path) throws IOException {
    String content = Files.readString(path);
    logger.info("Read full output file ({} characters)", content.length());
    return CompletableFuture.completedFuture(
        Map.of("content", content, "length", content.length(), "mode", "full"));
  }

  private CompletableFuture<Object> handleLinesMode(Path path, Map<String, Object> arguments)
      throws IOException {
    int startLine =
        arguments.containsKey("start_line") ? ((Number) arguments.get("start_line")).intValue() : 0;
    int endLine =
        arguments.containsKey("end_line")
            ? ((Number) arguments.get("end_line")).intValue()
            : Integer.MAX_VALUE;

    StringBuilder outputContent = new StringBuilder();
    int totalLines = 0;
    int readLines = 0;

    try (BufferedReader reader = Files.newBufferedReader(path)) {
      String line;
      int currentLine = 0;
      while ((line = reader.readLine()) != null) {
        totalLines++;
        if (currentLine >= startLine && currentLine < endLine) {
          outputContent.append(line).append(System.lineSeparator());
          readLines++;
        }
        currentLine++;
      }
    }

    logger.info(
        "Read {} lines from output file (lines {}-{} of {})",
        readLines,
        startLine,
        Math.min(endLine, totalLines),
        totalLines);

    return CompletableFuture.completedFuture(
        Map.of(
            "content", outputContent.toString(),
            "mode", "lines",
            "start_line", startLine,
            "lines_read", readLines,
            "total_lines", totalLines));
  }

  private CompletableFuture<Object> handleChunkMode(Path path, Map<String, Object> arguments)
      throws IOException {
    String fullContent = Files.readString(path);
    int totalLength = fullContent.length();

    int start = arguments.containsKey("start") ? ((Number) arguments.get("start")).intValue() : 0;
    int length =
        arguments.containsKey("length") ? ((Number) arguments.get("length")).intValue() : 10000;

    if (start >= totalLength) {
      logger.warn("Start position {} is beyond file length {}", start, totalLength);
      return CompletableFuture.completedFuture(
          Map.of("error", "Start position is beyond file length", "total_length", totalLength));
    }

    int end = Math.min(start + length, totalLength);
    String chunk = fullContent.substring(start, end);
    boolean hasMore = end < totalLength;

    logger.info(
        "Read chunk from output file ({} chars from position {}/{})",
        chunk.length(),
        start,
        totalLength);

    return CompletableFuture.completedFuture(
        Map.of(
            "content", chunk,
            "mode", "chunk",
            "chunk_start", start,
            "chunk_end", end,
            "total_length", totalLength,
            "has_more", hasMore));
  }
}
