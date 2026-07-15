package be.vlaanderen.omgeving.owlsda.ontology;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import be.vlaanderen.omgeving.owlsda.exception.OntologyLoadException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link Ontology}. */
public class OntologyTest {

  private Path tempDir;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("ontology-test");
  }

  @Test
  public void load_ReadsValidTurtleFileIntoModel() throws Exception {
    Path ttl = tempDir.resolve("ontology.ttl");
    Files.writeString(
        ttl,
        """
        @prefix ex: <http://example.org/> .
        @prefix owl: <http://www.w3.org/2002/07/owl#> .

        ex:Person a owl:Class .
        """,
        StandardCharsets.UTF_8);

    Ontology ontology = new Ontology();
    ontology.setFilePath(ttl.toString());
    ontology.load();

    assertNotNull(ontology.getModel());
    assertTrue(
        ontology
            .getModel()
            .contains(
                ResourceFactory.createResource("http://example.org/Person"),
                RDF.type,
                ResourceFactory.createResource("http://www.w3.org/2002/07/owl#Class")));
  }

  @Test
  public void load_ThrowsOntologyLoadExceptionForMalformedFile() throws Exception {
    Path bad = tempDir.resolve("malformed.ttl");
    Files.writeString(bad, "this is { not valid turtle @@@ ][", StandardCharsets.UTF_8);

    Ontology ontology = new Ontology();
    ontology.setFilePath(bad.toString());

    try {
      ontology.load();
      fail("Expected OntologyLoadException for malformed RDF content");
    } catch (OntologyLoadException e) {
      assertNotNull(e.getCause());
    }
  }

  @Test
  public void load_ThrowsOntologyLoadExceptionForMissingFile() {
    Ontology ontology = new Ontology();
    ontology.setFilePath(tempDir.resolve("does-not-exist.ttl").toString());

    try {
      ontology.load();
      fail("Expected OntologyLoadException for missing file");
    } catch (OntologyLoadException e) {
      assertNotNull(e.getCause());
    }
  }

  @Test
  public void load_WithNullFilePath_LeavesModelEmpty() {
    Ontology ontology = new Ontology();
    assertNull(ontology.getFilePath());

    ontology.load();

    assertNotNull(ontology.getModel());
    assertTrue(ontology.getModel().isEmpty());
  }

  @Test
  public void defaultFields_AreInitializedAsExpected() {
    Ontology ontology = new Ontology();
    assertNotNull(ontology.getExternalModels());
    assertTrue(ontology.getExternalModels().isEmpty());
    assertNull(ontology.getModel());
    assertNull(ontology.getInferredModel());
    assertFalse(ontology.getExternalModels().containsKey("anything"));
    assertEquals(0, ontology.getExternalModels().size());
  }
}
