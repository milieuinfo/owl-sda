package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore.AddResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for {@link WorkerTripleStore#setKnownPrefixes}: since each {@code
 * triplestore_add} call is parsed as an independent Turtle document, a worker that forgets to
 * declare a well-known ontology prefix (rdf, prov, skos, ...) previously got an "Undefined prefix"
 * parse failure. Known prefixes are now auto-injected when a submission doesn't declare them
 * itself.
 */
public class WorkerTripleStoreKnownPrefixTest {

  private WorkerTripleStore tripleStore;

  @Before
  public void setUp() throws Exception {
    Path tempFile = Files.createTempFile("test-triplestore-known-prefix", ".ttl");
    tripleStore = new WorkerTripleStore(tempFile.toString());
  }

  @Test
  public void addTriples_MissingKnownPrefix_IsAutoInjectedAndParses() {
    tripleStore.setKnownPrefixes(Map.of("prov", "http://www.w3.org/ns/prov#"));

    AddResult result =
        tripleStore.addTriples(
            "@prefix ex: <http://example.org/> .\nex:Thing1 prov:wasDerivedFrom ex:Thing0 .",
            "worker-0");

    assertTrue(
        "submission using an undeclared but known prefix must not error", !result.hasError());
    assertEquals(1, result.getTriplesAdded());
  }

  @Test
  public void addTriples_WithoutKnownPrefixes_UndeclaredPrefixStillErrors() {
    // No setKnownPrefixes call: behavior for genuinely unknown/invented prefixes is unchanged.
    AddResult result =
        tripleStore.addTriples(
            "@prefix ex: <http://example.org/> .\nex:Thing1 prov:wasDerivedFrom ex:Thing0 .",
            "worker-0");

    assertTrue(result.hasError());
  }

  @Test
  public void addTriples_SubmissionDeclaresItsOwnPrefix_KnownPrefixDoesNotOverride() {
    tripleStore.setKnownPrefixes(Map.of("ex", "http://known.example.org/"));

    // The submission's own @prefix for "ex" must win over the known-prefix default.
    AddResult result =
        tripleStore.addTriples(
            "@prefix ex: <http://own.example.org/> .\nex:Thing1 ex:name \"Alice\" .", "worker-0");

    assertTrue(!result.hasError());
    // The stored triple's subject must resolve to the submission's own namespace, not the
    // known-prefix default - query by the exact full URI to avoid depending on how Turtle
    // serialization happens to abbreviate it.
    List<String> matches =
        tripleStore.queryTriples("http://own.example.org/Thing1", null, null, "worker-0");
    assertEquals(1, matches.size());
  }

  @Test
  public void setKnownPrefixes_SeedsStoreMappingForImmediateCurieResolution() {
    tripleStore.setKnownPrefixes(Map.of("ex", "http://example.org/"));
    tripleStore.addTriples(
        "@prefix ex: <http://example.org/> .\nex:Thing1 ex:name \"Alice\" .", "worker-0");

    // CURIE resolution in query must work even though this call never itself declared @prefix ex.
    List<String> results = tripleStore.queryTriples(null, "ex:name", null, "worker-0");

    assertEquals(1, results.size());
    assertTrue(results.get(0).contains("Alice"));
  }
}
