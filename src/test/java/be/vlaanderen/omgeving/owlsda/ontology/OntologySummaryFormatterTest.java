package be.vlaanderen.omgeving.owlsda.ontology;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

/** Tests for {@link OntologySummaryFormatter}. */
public class OntologySummaryFormatterTest {

  private static final String NS = "http://example.org/";

  @Test
  public void summarize_NullOntology_ReturnsPlaceholder() {
    assertEquals("# No ontology available", OntologySummaryFormatter.summarize(null));
  }

  @Test
  public void summarize_EmptyModel_ReturnsPlaceholder() {
    Ontology ontology = new Ontology();
    ontology.setModel(ModelFactory.createDefaultModel());

    assertEquals("# No ontology available", OntologySummaryFormatter.summarize(ontology));
  }

  @Test
  public void summarize_ClassesAndProperties_ReportsCountsAndNames() {
    Model model = ModelFactory.createDefaultModel();
    model.setNsPrefix("ex", NS);
    Resource widget = model.createResource(NS + "Widget");
    widget.addProperty(RDF.type, OWL.Class);
    Resource gadget = model.createResource(NS + "Gadget");
    gadget.addProperty(RDF.type, OWL.Class);
    Resource hasPart = model.createResource(NS + "hasPart");
    hasPart.addProperty(RDF.type, OWL.ObjectProperty);
    Resource hasWeight = model.createResource(NS + "hasWeight");
    hasWeight.addProperty(RDF.type, OWL.DatatypeProperty);

    Ontology ontology = new Ontology();
    ontology.setModel(model);

    String summary = OntologySummaryFormatter.summarize(ontology);

    assertTrue(summary.contains("Classes (2): Gadget, Widget"));
    assertTrue(summary.contains("Object properties (1): hasPart"));
    assertTrue(summary.contains("Datatype properties (1): hasWeight"));
    assertTrue(summary.contains("Namespaces: ex=" + NS));
  }

  @Test
  public void summarize_ManyClasses_CapsSampleAndReportsOmittedCount() {
    Model model = ModelFactory.createDefaultModel();
    for (int i = 0; i < 25; i++) {
      model
          .createResource(NS + "Class" + String.format("%02d", i))
          .addProperty(RDF.type, OWL.Class);
    }

    Ontology ontology = new Ontology();
    ontology.setModel(model);

    String summary = OntologySummaryFormatter.summarize(ontology);

    assertTrue(summary.contains("Classes (25):"));
    assertTrue(summary.contains("+5 more"));
  }
}
