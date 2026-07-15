package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.shacl.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves "what does the data look like right now, and does it conform" - preferring the shared
 * triple store's cached validation snapshot (the live source of truth mid-generation) and falling
 * back to re-validating the output file once generation has flushed to disk. Used wherever
 * delegation/finalization logic needs this without caring which source is currently authoritative.
 */
final class DataModelSnapshotResolver {

  private static final Logger logger = LoggerFactory.getLogger(DataModelSnapshotResolver.class);

  private DataModelSnapshotResolver() {}

  record DataModelValidation(Model model, ValidationReport report) {}

  /**
   * Resolves the current model/validation, or null if neither the shared store nor a readable
   * output file is available.
   */
  static DataModelValidation resolve(
      WorkerTripleStore sharedTripleStore, Shacl shacl, String outputPath) {
    if (shacl == null) {
      return null;
    }

    if (sharedTripleStore != null && sharedTripleStore.size() > 0) {
      try {
        WorkerTripleStore.ValidationSnapshot snapshot =
            sharedTripleStore.getValidationSnapshot(shacl);
        return new DataModelValidation(snapshot.model(), snapshot.report());
      } catch (Exception e) {
        logger.debug("Could not get shared triple store validation snapshot: {}", e.getMessage());
      }
    }

    Model dataModel = readOutputFileModel(outputPath);
    if (dataModel == null) {
      return null;
    }
    return new DataModelValidation(dataModel, shacl.validate(dataModel));
  }

  private static Model readOutputFileModel(String outputPath) {
    if (outputPath == null) {
      return null;
    }
    Path outputFile = Path.of(outputPath);
    if (!Files.exists(outputFile)) {
      return null;
    }
    try {
      String turtleData = Files.readString(outputFile);
      Model dataModel = ModelFactory.createDefaultModel();
      dataModel.read(new StringReader(turtleData), null, "TURTLE");
      return dataModel;
    } catch (Exception e) {
      logger.debug("Could not read output file model: {}", e.getMessage());
      return null;
    }
  }
}
