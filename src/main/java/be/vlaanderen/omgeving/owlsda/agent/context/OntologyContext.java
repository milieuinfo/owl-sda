package be.vlaanderen.omgeving.owlsda.agent.context;

import be.vlaanderen.omgeving.owlsda.ontology.Ontology;
import java.io.StringWriter;
import org.apache.jena.rdf.model.Model;

/**
 * Context that provides the ontology model to agents.
 * Contains the complete ontology in TURTLE format, including classes, properties, and relationships.
 */
public class OntologyContext extends Context {
  private final Ontology ontology;

  /**
   * Create an ontology context from the given ontology.
   * The ontology model is serialized to TURTLE format for agent consumption.
   *
   * @param ontology The ontology to provide as context
   */
  public OntologyContext(Ontology ontology) {
    super();
    this.ontology = ontology;
    this.setType("text/turtle");
    this.setName("Ontology");
    this.setContent(getOntologyContent());
  }

  /**
   * Get the ontology model serialized as TURTLE.
   *
   * @return TURTLE representation of the ontology
   */
  private String getOntologyContent() {
    if (ontology == null || ontology.getModel() == null) {
      return "# No ontology available";
    }

    Model model = ontology.getModel();
    if (model.isEmpty()) {
      return "# Empty ontology";
    }

    StringWriter writer = new StringWriter();
    model.write(writer, "TURTLE");
    return writer.toString();
  }
}

