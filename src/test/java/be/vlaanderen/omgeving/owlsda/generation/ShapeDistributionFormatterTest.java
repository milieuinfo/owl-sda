package be.vlaanderen.omgeving.owlsda.generation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

/** Tests for {@link ShapeDistributionFormatter}. */
public class ShapeDistributionFormatterTest {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  private static final String NS = "http://example.org/";

  private static Shacl shaclWithClasses(String... localNames) {
    Model ontology = ModelFactory.createDefaultModel();
    ontology.setNsPrefix("ex", NS);
    for (String localName : localNames) {
      Resource cls = ontology.createResource(NS + localName);
      cls.addProperty(RDF.type, OWL.Class);
    }
    Shacl shacl = Shacl.fromOntology(ontology);
    shacl.generate();
    return shacl;
  }

  @Test
  public void format_ZeroWorkers_ReturnsEmptyString() {
    Shacl shacl = shaclWithClasses("Widget");
    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenReturn(0);

    assertEquals("", ShapeDistributionFormatter.format(shacl, batch, 1));
  }

  @Test
  public void format_NoUnprocessedShapes_ReturnsEmptyString() {
    Shacl shacl = shaclWithClasses("Widget");
    shacl.getShapes().get(0).setProcessed(true);
    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenReturn(1);

    assertEquals("", ShapeDistributionFormatter.format(shacl, batch, 1));
  }

  @Test
  public void format_OneUnprocessedShape_ListsItWithTargetClass() {
    Shacl shacl = shaclWithClasses("Widget");
    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenReturn(1);
    when(batch.config()).thenReturn(null);

    String result = ShapeDistributionFormatter.format(shacl, batch, 1);

    assertTrue(result.contains("OPTIMIZED SHAPE DISTRIBUTION"));
    assertTrue(result.contains("POOL-0"));
    assertTrue(result.contains("WidgetShape"));
    assertTrue(result.contains("class: Widget"));
  }

  @Test
  public void format_MoreShapesThanNamesLimit_AppendsOmittedCount() {
    Shacl shacl = shaclWithClasses("A", "B", "C", "D", "E", "F");
    ConcurrentWorkerBatch batch = mock(ConcurrentWorkerBatch.class);
    when(batch.getWorkerCount()).thenReturn(1);
    Config config = new Config();
    config.getClient().getWorker().setBatchSize(6);
    when(batch.config()).thenReturn(config);

    String result = ShapeDistributionFormatter.format(shacl, batch, 6);

    assertTrue(result.contains("more)"));
  }
}
