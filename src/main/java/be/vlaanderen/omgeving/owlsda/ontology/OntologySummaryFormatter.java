package be.vlaanderen.omgeving.owlsda.ontology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

/**
 * Produces a short, bounded plain-text summary of an ontology - triple/class/property counts plus a
 * capped sample of names - for agents that only need orientation rather than the full serialized
 * model. Meant to accompany or stand in for {@link
 * be.vlaanderen.omgeving.owlsda.agent.context.OntologyContext}'s complete Turtle dump, which can
 * run to hundreds of KB for real-world ontologies.
 */
public final class OntologySummaryFormatter {

  private static final int MAX_SAMPLE_NAMES = 20;

  private OntologySummaryFormatter() {}

  public static String summarize(Ontology ontology) {
    if (ontology == null || ontology.getModel() == null || ontology.getModel().isEmpty()) {
      return "# No ontology available";
    }

    Model model = ontology.getModel();

    List<String> classUris = new ArrayList<>();
    model.listSubjectsWithProperty(RDF.type, OWL.Class).forEachRemaining(r -> addUri(classUris, r));
    model
        .listSubjectsWithProperty(RDF.type, RDFS.Class)
        .forEachRemaining(r -> addUri(classUris, r));
    List<String> classNames = distinctSorted(classUris);

    List<String> objectPropertyUris = new ArrayList<>();
    model
        .listSubjectsWithProperty(RDF.type, OWL.ObjectProperty)
        .forEachRemaining(r -> addUri(objectPropertyUris, r));
    List<String> objectProperties = distinctSorted(objectPropertyUris);

    List<String> datatypePropertyUris = new ArrayList<>();
    model
        .listSubjectsWithProperty(RDF.type, OWL.DatatypeProperty)
        .forEachRemaining(r -> addUri(datatypePropertyUris, r));
    List<String> datatypeProperties = distinctSorted(datatypePropertyUris);

    StringBuilder summary = new StringBuilder();
    summary.append(
        "ONTOLOGY SUMMARY (quick orientation - read the full 'Ontology' context via context_reader"
            + " for exact definitions)\n");
    summary.append("- Triples: ").append(model.size()).append("\n");
    summary
        .append("- Classes (")
        .append(classNames.size())
        .append("): ")
        .append(sample(classNames))
        .append("\n");
    summary
        .append("- Object properties (")
        .append(objectProperties.size())
        .append("): ")
        .append(sample(objectProperties))
        .append("\n");
    summary
        .append("- Datatype properties (")
        .append(datatypeProperties.size())
        .append("): ")
        .append(sample(datatypeProperties))
        .append("\n");

    Map<String, String> prefixes = new TreeMap<>(model.getNsPrefixMap());
    if (!prefixes.isEmpty()) {
      List<String> prefixEntries = new ArrayList<>();
      for (Map.Entry<String, String> entry : prefixes.entrySet()) {
        String prefix = entry.getKey().isBlank() ? "(default)" : entry.getKey();
        prefixEntries.add(prefix + "=" + entry.getValue());
      }
      summary.append("- Namespaces: ").append(String.join(", ", prefixEntries)).append("\n");
    }

    return summary.toString();
  }

  private static void addUri(List<String> names, Resource resource) {
    if (resource.isURIResource()) {
      names.add(localNameOf(resource.getURI()));
    }
  }

  private static List<String> distinctSorted(List<String> names) {
    return names.stream().distinct().sorted().toList();
  }

  private static String sample(List<String> names) {
    if (names.isEmpty()) {
      return "(none)";
    }
    int limit = Math.min(names.size(), MAX_SAMPLE_NAMES);
    String joined = String.join(", ", names.subList(0, limit));
    int omitted = names.size() - limit;
    return omitted > 0 ? joined + " ... (+" + omitted + " more)" : joined;
  }

  private static String localNameOf(String uri) {
    int hashIndex = uri.lastIndexOf('#');
    int slashIndex = uri.lastIndexOf('/');
    int splitIndex = Math.max(hashIndex, slashIndex);
    return splitIndex >= 0 && splitIndex < uri.length() - 1 ? uri.substring(splitIndex + 1) : uri;
  }
}
