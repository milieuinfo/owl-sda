package be.vlaanderen.omgeving.owlsda.ontology;

import be.vlaanderen.omgeving.owlsda.exception.OntologyLoadException;
import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

/**
 * Represents a loaded OWL ontology. Holds the base model, the optional inferred model, and any
 * externally resolved ontology models (e.g., imported namespaces).
 */
@Getter
@Setter
public class Ontology {
  private String filePath;
  private Model model;
  private Model inferredModel;
  private final Map<String, Model> externalModels = new HashMap<>();

  /**
   * Load the ontology from the specified file path and initialize the model.
   *
   * @throws OntologyLoadException if the file cannot be read or parsed as RDF
   */
  public void load() {
    model = ModelFactory.createDefaultModel();
    if (getFilePath() != null) {
      try {
        model.read(getFilePath());
      } catch (RuntimeException e) {
        throw new OntologyLoadException("Failed to load ontology from " + getFilePath(), e);
      }
    }
  }
}
