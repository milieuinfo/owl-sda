package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for CURIE (prefixed-name) and typed-literal resolution in {@link
 * WorkerTripleStore#removeTriplesWithBlankNodes} and {@link WorkerTripleStore#queryTriples}.
 * Workers frequently pass prefixed names (e.g. {@code dc:created}, {@code :LocalName}) or typed
 * literals (e.g. {@code "2023-01-01"^^xsd:date}) as removal/query patterns instead of bare full
 * URIs; previously these silently matched nothing because subject/predicate were treated as opaque
 * literal URI strings and objects were always parsed as untyped string literals.
 */
public class WorkerTripleStorePrefixResolutionTest {

  private WorkerTripleStore tripleStore;

  @Before
  public void setUp() throws Exception {
    Path tempFile = Files.createTempFile("test-triplestore-prefix", ".ttl");
    tripleStore = new WorkerTripleStore(tempFile.toString());
  }

  @Test
  public void removeTriples_ResolvesCuriePredicate() {
    tripleStore.addTriples(
        """
        @prefix dc: <http://purl.org/dc/terms/> .
        @prefix ex: <http://example.org/> .
        ex:Thing1 dc:created "2023-01-01T00:00:00Z" .
        """,
        "worker-0");
    assertEquals(1L, tripleStore.size());

    int removed =
        tripleStore.removeTriplesWithBlankNodes(
            "http://example.org/Thing1", "dc:created", null, "worker-0");

    assertEquals(1, removed);
    assertEquals(0L, tripleStore.size());
  }

  @Test
  public void removeTriples_ResolvesCurieSubject() {
    tripleStore.addTriples(
        """
        @prefix ex: <http://example.org/> .
        ex:Thing1 ex:name "Alice" .
        """,
        "worker-0");

    int removed = tripleStore.removeTriplesWithBlankNodes("ex:Thing1", null, null, "worker-0");

    assertEquals(1, removed);
    assertEquals(0L, tripleStore.size());
  }

  @Test
  public void removeTriples_ResolvesTypedLiteralObject() {
    tripleStore.addTriples(
        """
        @prefix dc: <http://purl.org/dc/terms/> .
        @prefix ex: <http://example.org/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        ex:Thing1 dc:valid "2030-12-31"^^xsd:date .
        """,
        "worker-0");
    assertEquals(1L, tripleStore.size());

    int removed =
        tripleStore.removeTriplesWithBlankNodes(
            null, "dc:valid", "\"2030-12-31\"^^xsd:date", "worker-0");

    assertEquals(1, removed);
    assertEquals(0L, tripleStore.size());
  }

  @Test
  public void removeTriples_ResolvesCurieResourceObject() {
    tripleStore.addTriples(
        """
        @prefix ex: <http://example.org/> .
        ex:Thing1 ex:unit ex:Celsius .
        """,
        "worker-0");

    int removed =
        tripleStore.removeTriplesWithBlankNodes(null, "ex:unit", "ex:Celsius", "worker-0");

    assertEquals(1, removed);
    assertEquals(0L, tripleStore.size());
  }

  @Test
  public void removeTriples_PlainLiteralWithoutMatchingPrefix_StillTreatedAsLiteral() {
    // Backward compatibility: values with no recognised prefix and no quote/URI syntax must keep
    // behaving as plain string literals, exactly as before this fix.
    tripleStore.addTriples(
        """
        @prefix ex: <http://example.org/> .
        ex:Thing1 ex:status "PENDING" .
        """,
        "worker-0");

    int removed =
        tripleStore.removeTriplesWithBlankNodes(
            "http://example.org/Thing1", "http://example.org/status", "PENDING", "worker-0");

    assertEquals(1, removed);
    assertEquals(0L, tripleStore.size());
  }

  @Test
  public void queryTriples_ResolvesCuriePredicate() {
    tripleStore.addTriples(
        """
        @prefix ex: <http://example.org/> .
        ex:Thing1 ex:name "Alice" .
        """,
        "worker-0");

    List<String> results = tripleStore.queryTriples(null, "ex:name", null, "worker-0");

    assertEquals(1, results.size());
    assertTrue(results.get(0).contains("Alice"));
  }
}
