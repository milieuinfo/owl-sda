package be.vlaanderen.omgeving.owlsda.generation;

import java.util.Optional;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;

/**
 * Resolves a class's {@code hydra:search}/{@code hydra:IriTemplate} declaration (an RFC 6570
 * template like {@code https://example.org/id/installation/{uuid}/{issued}/{created}}) directly
 * from the ontology model, so the exact literal template can be embedded in delegation text
 * deterministically. Relying on the worker/supervisor LLM to locate this itself inside a hundred-KB
 * ontology dump proved unreliable in practice - models routinely missed it and fell back to
 * inventing their own {@code ClassName_001}-style URIs instead.
 */
final class HydraIriTemplateResolver {

  private static final String HYDRA = "http://www.w3.org/ns/hydra/core#";

  private HydraIriTemplateResolver() {}

  static Optional<String> resolveTemplate(Model ontologyModel, String classUri) {
    if (ontologyModel == null || classUri == null || classUri.isBlank()) {
      return Optional.empty();
    }

    Resource classResource = ontologyModel.getResource(classUri);
    Property hydraSearch = ontologyModel.createProperty(HYDRA + "search");
    Statement searchStmt = classResource.getProperty(hydraSearch);
    if (searchStmt == null || !searchStmt.getObject().isResource()) {
      return Optional.empty();
    }

    Resource iriTemplate = searchStmt.getResource();
    Property hydraTemplate = ontologyModel.createProperty(HYDRA + "template");
    Statement templateStmt = iriTemplate.getProperty(hydraTemplate);
    if (templateStmt == null || !templateStmt.getObject().isLiteral()) {
      return Optional.empty();
    }

    String template = templateStmt.getLiteral().getLexicalForm();
    return (template == null || template.isBlank()) ? Optional.empty() : Optional.of(template);
  }
}
