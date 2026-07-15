package be.vlaanderen.omgeving.owlsda.generation;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

/** Tests for {@link DataModelSnapshotResolver}. */
public class DataModelSnapshotResolverTest {

  private static final String EX = "http://example.org/";

  private Shacl shaclWithOneClass() {
    Model ontology = ModelFactory.createDefaultModel();
    ontology.setNsPrefix("ex", EX);
    ontology.add(ResourceFactory.createResource(EX + "Widget"), RDF.type, OWL.Class);
    Shacl shacl = new Shacl(ontology);
    shacl.generate();
    return shacl;
  }

  @Test
  public void resolve_NullShacl_ReturnsNull() {
    assertNull(DataModelSnapshotResolver.resolve(null, null, null));
  }

  @Test
  public void resolve_NoStoreAndNoOutputFile_ReturnsNull() {
    Shacl shacl = shaclWithOneClass();

    assertNull(DataModelSnapshotResolver.resolve(null, shacl, "/nonexistent/output.ttl"));
  }

  @Test
  public void resolve_SharedStoreHasData_UsesStoreSnapshot() {
    Shacl shacl = shaclWithOneClass();
    WorkerTripleStore store = new WorkerTripleStore(null);
    store.addTriples("<" + EX + "w1> a <" + EX + "Widget> .", "TEST");

    DataModelSnapshotResolver.DataModelValidation result =
        DataModelSnapshotResolver.resolve(store, shacl, null);

    assertTrue(
        result
            .model()
            .contains(
                ResourceFactory.createResource(EX + "w1"),
                RDF.type,
                ResourceFactory.createResource(EX + "Widget")));
  }

  @Test
  public void resolve_EmptyStoreFallsBackToOutputFile() throws Exception {
    Shacl shacl = shaclWithOneClass();
    WorkerTripleStore emptyStore = new WorkerTripleStore(null);

    Path outputFile = Files.createTempFile("data-model-snapshot-resolver-test", ".ttl");
    Files.writeString(outputFile, "<" + EX + "w1> a <" + EX + "Widget> .");

    DataModelSnapshotResolver.DataModelValidation result =
        DataModelSnapshotResolver.resolve(emptyStore, shacl, outputFile.toString());

    assertTrue(
        result
            .model()
            .contains(
                ResourceFactory.createResource(EX + "w1"),
                RDF.type,
                ResourceFactory.createResource(EX + "Widget")));
  }
}
