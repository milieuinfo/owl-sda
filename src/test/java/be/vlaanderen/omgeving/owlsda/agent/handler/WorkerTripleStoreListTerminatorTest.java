package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore.AddResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for {@link WorkerTripleStore#sanitizeStrayListTerminators}: smaller worker
 * models routinely close the last predicate-object pair of a blank-node property list with a stray
 * full stop (e.g. {@code [ ... ; qudt:hasUnit "g/s" . ]}), which Jena rejects with "Triples not
 * terminated properly in []-list". Since that '.' can never legally appear there, it is stripped
 * before parsing instead of rejecting the whole submission.
 */
public class WorkerTripleStoreListTerminatorTest {

  private WorkerTripleStore tripleStore;

  @Before
  public void setUp() throws Exception {
    Path tempFile = Files.createTempFile("test-triplestore-list-terminator", ".ttl");
    tripleStore = new WorkerTripleStore(tempFile.toString());
  }

  @Test
  public void addTriples_StrayPeriodBeforeClosingBracket_IsRepaired() {
    String turtle =
        "@prefix : <http://example.org/> .\n"
            + "@prefix qudt: <http://qudt.org/schema/qudt/> .\n"
            + ":obs :hasResult [ a :ObservationResult ;\n"
            + "                  qudt:numericValue \"15.8\" ;\n"
            + "                  qudt:hasUnit \"grams per second\" .\n"
            + "        ] .\n";

    AddResult result = tripleStore.addTriples(turtle, "worker-0");

    assertFalse("stray '.' before ']' must be repaired, not rejected", result.hasError());
    assertEquals(4, result.getTriplesAdded());
  }

  @Test
  public void addTriples_StrayPeriodBeforeClosingParen_IsRepaired() {
    String turtle = "@prefix : <http://example.org/> .\n" + ":list :items ( :a :b . ) .\n";

    AddResult result = tripleStore.addTriples(turtle, "worker-0");

    assertFalse("stray '.' before ')' must be repaired, not rejected", result.hasError());
  }

  @Test
  public void addTriples_LegitimatePeriodInsideStringBeforeBracket_IsPreserved() {
    // The '.' here is inside a string literal's content, immediately followed by ']' in the raw
    // text purely by coincidence - it must be left untouched since it isn't a stray terminator.
    String turtle = "@prefix : <http://example.org/> .\n" + ":thing :note \"end.\"^^:MyType .\n";

    AddResult result = tripleStore.addTriples(turtle, "worker-0");

    assertFalse(result.hasError());
    assertTrue(
        tripleStore.getAllTriples().contains("end.")
            || tripleStore.queryTriples(null, "http://example.org/note", null, "worker-0").stream()
                .anyMatch(s -> s.contains("end.")));
  }

  @Test
  public void sanitizeStrayListTerminators_ValidDocumentIsUnchanged() {
    String turtle =
        "@prefix : <http://example.org/> .\n:obs :hasResult [ a :ObservationResult ] .\n";

    assertEquals(turtle, WorkerTripleStore.sanitizeStrayListTerminators(turtle));
  }
}
