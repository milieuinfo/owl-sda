package be.vlaanderen.omgeving.owlsda.validation;

import be.vlaanderen.omgeving.owlsda.exception.OntologyException;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.Getter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.shacl.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles validation of output data against SHACL shapes. Generates validation reports and checks
 * conformance.
 */
@Getter
public class OutputValidator {
  private static final Logger logger = LoggerFactory.getLogger(OutputValidator.class);

  private final String outputPath;
  private final Shacl shacl;

  public OutputValidator(String outputPath, Shacl shacl) {
    this.outputPath = outputPath;
    this.shacl = shacl;
  }

  /** Validate output data and return validation report (or null if valid). */
  public String validate() {
    try {
      Model data = readOutputData();
      return getValidationReport(data);
    } catch (Exception e) {
      logger.error("Failed to validate output data: {}", e.getMessage());
      return "An error occurred loading the output data: " + e.getMessage();
    }
  }

  /** Get output data as a string from file. */
  public String getOutputDataAsString() {
    if (outputPath == null) {
      throw new OntologyException("Output path is not configured");
    }
    try {
      return Files.readString(Path.of(outputPath));
    } catch (Exception e) {
      throw new OntologyException("Unable to read output data: " + e.getMessage(), e);
    }
  }

  /** Get output data as a Model. */
  public Model readOutputData() {
    String content = getOutputDataAsString();
    Model dataModel = ModelFactory.createDefaultModel();
    dataModel.read(new StringReader(content), null, "TURTLE");
    return dataModel;
  }

  /**
   * Generate validation report. Returns null if data conforms, otherwise returns report as string.
   */
  private String getValidationReport(Model data) {
    ValidationReport report = shacl.validate(data);

    if (report.conforms()) {
      logger.debug("Output data is valid");
      return null;
    }

    StringWriter reportString = new StringWriter();
    reportString.append("Data does NOT conform to SHACL shapes. Violations:\n");
    Model reportModel = report.getModel();
    reportModel.write(reportString, "TURTLE");

    logger.debug("Output data has {} validation violations", report.getEntries().size());
    return reportString.toString();
  }

  /** Check if output is currently valid. */
  public boolean isValid() {
    return validate() == null;
  }
}
