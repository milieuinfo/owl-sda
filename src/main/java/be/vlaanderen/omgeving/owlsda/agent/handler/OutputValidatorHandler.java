package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.agent.context.ValidationContext;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.shacl.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for validating TURTLE data against SHACL shapes.
 * Supports three modes:
 * - "data" mode: Validates data provided as arguments
 * - "file" mode: Validates output file contents (reviewer/supervisor)
 * - "store" mode: Validates worker's triple store (workers only)
 */
public class OutputValidatorHandler implements SessionHandler {
  private static final Logger logger = LoggerFactory.getLogger(OutputValidatorHandler.class);
  private final Shacl shacl;
  private final Config config;
  private final WorkerTripleStore tripleStore;
  private final Consumer<ValidationContext> validationContextPublisher;

  public OutputValidatorHandler(Shacl shacl, Config config) {
    this(shacl, config, null, null);
  }

  public OutputValidatorHandler(Shacl shacl, WorkerTripleStore tripleStore) {
    this(shacl, null, tripleStore, null);
  }

  public OutputValidatorHandler(Shacl shacl, Config config, WorkerTripleStore tripleStore) {
    this(shacl, config, tripleStore, null);
  }

  public OutputValidatorHandler(Shacl shacl, Config config, WorkerTripleStore tripleStore,
      Consumer<ValidationContext> validationContextPublisher) {
    this.shacl = shacl;
    this.config = config;
    this.tripleStore = tripleStore;
    this.validationContextPublisher = validationContextPublisher;
  }

  @Override
  public String getName() {
    return "shacl_validator";
  }

  @Override
  public String getDescription() {
    return """
      Validates TURTLE data against SHACL shapes.

      Supports three modes:
      - "data" mode: Validates data provided as argument
      - "file" mode: Validates output file (for reviewer/supervisor)
      - "store" mode: Validates your triple store (for workers)

      Returns validation report if data does NOT conform, null if valid.
    """;
  }

  @Override
  public Map<String, Object> getArguments() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            "source", Map.of(
                "type", "string",
                "enum", List.of("data", "file", "store"),
                "description", "Validation source: 'data' validates provided data, 'file' validates output file, 'store' validates your triple store (default: data)"
            ),
            "data", Map.of(
                "type", "string",
                "description", "TURTLE data to validate (required for 'data' source)"
            ),
            "format-on-error", Map.of(
                "type", "boolean",
                "description", "Include formatted violation details (default: true)"
            )
        ),
        "required", List.of()
    );
  }

  @Override
  public CompletableFuture<Object> handle(Map<String, Object> arguments) {
    String source = (String) arguments.getOrDefault("source", "data");
    Object formatOnErrorArg = arguments.getOrDefault("format-on-error", true);
    boolean formatOnError = !(Boolean.FALSE.equals(formatOnErrorArg));

    return switch (source) {
      case "file" -> validateFile(formatOnError);
      case "data" -> validateData(arguments, formatOnError);
      case "store" -> validateStore(formatOnError);
      default -> CompletableFuture.completedFuture(Map.of(
          "error", "Unknown source: " + source + ". Use 'data', 'file', or 'store'."
      ));
    };
  }

  /**
   * Validate data provided as argument.
   */
  private CompletableFuture<Object> validateData(Map<String, Object> arguments, boolean formatOnError) {
    String data = (String) arguments.get("data");

    if (data == null || data.isEmpty()) {
      logger.warn("No data provided for validation");
      return CompletableFuture.completedFuture(Map.of(
          "error", "No data provided for validation"
      ));
    }

    try {
      ValidationReport report = validateTurtleData(data);
      return buildResponse(report, formatOnError, "data");
    } catch (Exception e) {
      logger.error("Failed to validate data: {}", e.getMessage());
      return CompletableFuture.completedFuture(Map.of(
          "status", "error",
          "error", "Failed to validate data: " + e.getMessage()
      ));
    }
  }

  /**
   * Validate worker's triple store.
   */
  private CompletableFuture<Object> validateStore(boolean formatOnError) {
    if (tripleStore == null) {
      logger.error("Triple store is not available for this session");
      return CompletableFuture.completedFuture(Map.of(
          "error", "Triple store validation is not available for this session type"
      ));
    }

    try {
      Model dataModel = tripleStore.getModel();
      ValidationReport report = shacl.validate(dataModel);
      logger.info("Validated triple store with {} violations", report.getEntries().size());
      return buildResponse(report, formatOnError, "store");
    } catch (Exception e) {
      logger.error("Failed to validate triple store: {}", e.getMessage());
      return CompletableFuture.completedFuture(Map.of(
          "status", "error",
          "error", "Failed to validate triple store: " + e.getMessage()
      ));
    }
  }

  /**
   * Validate output file.
   */
  private CompletableFuture<Object> validateFile(boolean formatOnError) {
    if (config == null || config.getOutputPath() == null) {
      logger.error("Output path is not configured");
      return CompletableFuture.completedFuture(Map.of(
          "error", "Output path is not configured"
      ));
    }

    try {
      String fileContent = readOutputFile(config.getOutputPath());
      ValidationReport report = validateTurtleData(fileContent);
      return buildResponse(report, formatOnError, "file");
    } catch (IOException e) {
      logger.error("Failed to read output file: {}", e.getMessage(), e);
      return CompletableFuture.completedFuture(Map.of(
          "status", "error",
          "error", "Failed to read output file: " + e.getMessage()
      ));
    } catch (Exception e) {
      logger.error("Failed to validate file: {}", e.getMessage(), e);
      return CompletableFuture.completedFuture(Map.of(
          "status", "error",
          "error", "Failed to validate file: " + e.getMessage()
      ));
    }
  }

  /**
   * Validate TURTLE string and return validation report.
   */
  private ValidationReport validateTurtleData(String turtleData) {
    Model dataModel = ModelFactory.createDefaultModel();
    dataModel.read(new StringReader(turtleData), null, "TURTLE");
    return shacl.validate(dataModel);
  }

  /**
   * Read output file contents.
   */
  private String readOutputFile(String filePath) throws IOException {
    return Files.readString(Path.of(filePath));
  }

  /**
   * Build response from validation report and publish ValidationContext to all sessions.
   */
  private CompletableFuture<Object> buildResponse(ValidationReport report, boolean formatOnError,
      String source) {
    publishValidationContext(report, source);

    if (report.conforms()) {
      logger.debug("Data is valid against SHACL shapes");
      return CompletableFuture.completedFuture(Map.of(
          "status", "valid",
          "message", "Data conforms to SHACL shapes",
          "violations", 0
      ));
    }

    logger.debug("Data has {} validation violations", report.getEntries().size());

    if (formatOnError) {
      String reportString = formatValidationReport(report);
      Map<String, Object> response = new HashMap<>();
      response.put("status", "invalid");
      response.put("message", "Data does NOT conform to SHACL shapes");
      response.put("violations", report.getEntries().size());
      response.put("report", reportString);
      return CompletableFuture.completedFuture(response);
    }

    return CompletableFuture.completedFuture(Map.of(
        "status", "invalid",
        "message", "Data does NOT conform to SHACL shapes",
        "violations", report.getEntries().size()
    ));
  }

  private void publishValidationContext(ValidationReport report, String source) {
    if (validationContextPublisher == null) {
      return;
    }

    String content;
    if (report.conforms()) {
      content = "SHACL validation passed (source='" + source + "').";
    } else {
      content = formatValidationReport(report);
    }

    validationContextPublisher.accept(new ValidationContext(content));
  }

  /**
   * Format validation report as a readable string.
   */
  private String formatValidationReport(ValidationReport report) {
    StringWriter writer = new StringWriter();
    writer.append("Data does NOT conform to SHACL shapes. Violations:\n\n");

    Model reportModel = report.getModel();
    reportModel.write(writer, "TURTLE");

    return writer.toString();
  }
}

