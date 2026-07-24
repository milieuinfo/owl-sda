package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore.AddResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for {@link WorkerTripleStore#addTriples}'s handling of relative IRIs: a worker
 * occasionally submits a bare, scheme-less IRI (e.g. {@code <Operator-002>}) instead of a prefixed
 * name ({@code :Operator-002}) or full IRI. Parsed with no base URI, Jena resolves that against the
 * JVM's current working directory (a {@code file://...} IRI), silently splitting one logical
 * resource into two different subjects. {@link WorkerTripleStore#rewriteRelativeIrisAsCuries}
 * rewrites the IRI into the CURIE it was almost certainly meant to be before parsing, so it
 * resolves to the exact same URI a correctly-prefixed reference would have used; {@link
 * WorkerTripleStore#baseUri} is the fallback for whatever that rewrite doesn't catch.
 */
public class WorkerTripleStoreBaseUriTest {

  private WorkerTripleStore tripleStore;

  @Before
  public void setUp() throws Exception {
    Path tempFile = Files.createTempFile("test-triplestore-base-uri", ".ttl");
    tripleStore = new WorkerTripleStore(tempFile.toString());
  }

  @Test
  public void addTriples_RelativeIri_ResolvesAgainstDefaultNamespaceNotWorkingDirectory() {
    tripleStore.setKnownPrefixes(Map.of("", "https://example.org/ns/riepr#"));

    AddResult result =
        tripleStore.addTriples(
            "<Operator-operator-002> <http://xmlns.com/foaf/0.1/name> \"MyGlass\" .", "worker-0");

    assertFalse(result.hasError());
    List<String> matches =
        tripleStore.queryTriples(
            "https://example.org/ns/riepr#Operator-operator-002", null, null, "worker-0");
    assertTrue(
        "relative IRI must resolve under the ontology's default namespace, matching what "
            + ":Operator-operator-002 would have resolved to",
        matches.size() == 1);
  }

  @Test
  public void addTriples_RelativeIriMatchesPrefixedNameForSameLocalPart() {
    tripleStore.setKnownPrefixes(Map.of("", "https://example.org/ns/riepr#"));

    // One worker call uses the bare relative form, another (correctly) uses the prefixed form -
    // both must land on the exact same subject rather than creating two different resources.
    tripleStore.addTriples(
        "<Operator-operator-002> <http://xmlns.com/foaf/0.1/name> \"MyGlass\" .", "worker-0");
    tripleStore.addTriples(
        "@prefix : <https://example.org/ns/riepr#> .\n"
            + ":Operator-operator-002 <http://purl.org/dc/terms/type> \"industrial\" .",
        "worker-1");

    List<String> matches =
        tripleStore.queryTriples(
            "https://example.org/ns/riepr#Operator-operator-002", null, null, "worker-0");
    assertTrue("both calls must have merged onto the same subject URI", matches.size() == 2);
  }

  @Test
  public void addTriples_RelativeIriWithNoConfiguredNamespace_FailsClearlyInsteadOfLeaking() {
    // setKnownPrefixes is never called, so there is no default namespace to rewrite against or to
    // fall back to as a base URI. The submission is rewritten to a ":LocalName" CURIE regardless
    // (see rewriteRelativeIrisAsCuries), which with no "@prefix :" available anywhere now fails
    // with an explicit, actionable parse error - a clear improvement over the old behavior of
    // silently resolving into a bogus file://<local machine path> URI.
    AddResult result =
        tripleStore.addTriples(
            "<Operator-operator-002> <http://xmlns.com/foaf/0.1/name> \"MyGlass\" .", "worker-0");

    assertTrue(result.hasError());
    assertFalse(
        "must never resolve a relative IRI against the local filesystem",
        result.getError().contains("file:"));
  }
}
