package be.vlaanderen.omgeving.owlsda.ontology;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

@Getter
@Setter
public class Ontology {
  private String filePath;
  private Model model;
  private Model inferredModel;
  private final Map<String, Model> externalModels = new HashMap<>();

  /**
   * Load the ontology from the specified file path and initialize the model.
   */
  public void load() {
    model = ModelFactory.createDefaultModel();
    if (getFilePath() != null) {
      model.read(getFilePath());
      setModel(model);
    }
  }
}
