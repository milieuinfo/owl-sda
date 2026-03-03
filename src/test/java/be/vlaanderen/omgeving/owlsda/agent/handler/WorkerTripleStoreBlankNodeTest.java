package be.vlaanderen.omgeving.owlsda.agent.handler;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Tests for WorkerTripleStore blank node cleanup functionality.
 */
public class WorkerTripleStoreBlankNodeTest {

  private WorkerTripleStore tripleStore;
  private Path tempFile;

  @Before
  public void setUp() throws Exception {
    tempFile = Files.createTempFile("test-triplestore", ".ttl");
    tripleStore = new WorkerTripleStore(tempFile.toString());
  }

  @Test
  public void testRemoveTriples_WithoutBlankNodes() {
    // Add simple triples without blank nodes
    String turtleData = """
        @prefix ex: <http://example.org/> .
        
        ex:Person1 a ex:Person ;
            ex:name "Alice" ;
            ex:age 30 .
        """;

    tripleStore.addTriples(turtleData, "TEST");
    assertEquals(3, tripleStore.size());

    // Remove all triples about Person1
    int removed = tripleStore.removeTriplesWithBlankNodes("http://example.org/Person1", null, null, "TEST");
    assertEquals(3, removed);
    assertEquals(0, tripleStore.size());
  }

  @Test
  public void testRemoveTriples_WithBlankNodes_ShouldRemoveOrphans() {
    // Add triples with blank nodes representing an address
    String turtleData = """
        @prefix ex: <http://example.org/> .
        
        ex:Person1 a ex:Person ;
            ex:name "Alice" ;
            ex:hasAddress [
                a ex:Address ;
                ex:street "Main Street" ;
                ex:city "Springfield"
            ] .
        """;

    tripleStore.addTriples(turtleData, "TEST");
    long initialSize = tripleStore.size();
    assertTrue("Should have at least 6 triples", initialSize >= 6);

    // Remove the person - should also remove the orphaned blank node address
    int removed = tripleStore.removeTriplesWithBlankNodes("http://example.org/Person1", null, null, "TEST");

    assertEquals("All triples should be removed including blank nodes", initialSize, removed);
    assertEquals("Store should be empty after removing person with blank node address", 0, tripleStore.size());
  }

  @Test
  public void testRemoveTriples_WithNestedBlankNodes_ShouldRemoveAll() {
    // Add triples with nested blank nodes
    String turtleData = """
        @prefix ex: <http://example.org/> .
        
        ex:Person1 a ex:Person ;
            ex:name "Bob" ;
            ex:hasAddress [
                a ex:Address ;
                ex:street "Oak Avenue" ;
                ex:hasGeoLocation [
                    a ex:GeoLocation ;
                    ex:latitude "40.7128" ;
                    ex:longitude "-74.0060"
                ]
            ] .
        """;

    tripleStore.addTriples(turtleData, "TEST");
    long initialSize = tripleStore.size();
    assertTrue("Should have at least 8 triples", initialSize >= 8);

    // Remove the person - should cascade through nested blank nodes
    int removed = tripleStore.removeTriplesWithBlankNodes("http://example.org/Person1", null, null, "TEST");

    assertEquals("All nested blank nodes should be removed", initialSize, removed);
    assertEquals("Store should be empty", 0, tripleStore.size());
  }

  @Test
  public void testRemoveTriples_SharedBlankNode_ShouldNotRemoveIfStillReferenced() {
    // Add triples where multiple named resources reference the same blank node
    String turtleData = """
        @prefix ex: <http://example.org/> .
        
        ex:Person1 ex:name "Alice" ;
            ex:hasFriend ex:Person2 .
        
        ex:Person2 ex:name "Bob" .
        
        ex:Person1 ex:knows _:b1 .
        ex:Person2 ex:knows _:b1 .
        
        _:b1 ex:name "Charlie" .
        """;

    tripleStore.addTriples(turtleData, "TEST");
    long initialSize = tripleStore.size();

    // Remove Person1's triples
    int removed = tripleStore.removeTriplesWithBlankNodes("http://example.org/Person1", null, null, "TEST");

    // Should remove Person1's triples but NOT the blank node since Person2 still references it
    assertTrue("Should remove Person1's triples", removed > 0);
    assertTrue("Store should not be empty (Person2 and shared blank node remain)", tripleStore.size() > 0);

    // Verify blank node still exists
    String allTriples = tripleStore.getAllTriples();
    assertTrue("Blank node should still exist", allTriples.contains("Charlie"));
  }

  @Test
  public void testRemoveTriples_PartialRemoval_OnlyOrphanedBlanksRemoved() {
    // Add person with two addresses, remove one address
    String turtleData = """
        @prefix ex: <http://example.org/> .
        
        ex:Person1 a ex:Person ;
            ex:name "Alice" ;
            ex:hasAddress [
                a ex:Address ;
                ex:type "home" ;
                ex:street "Main Street"
            ] ;
            ex:hasAddress [
                a ex:Address ;
                ex:type "work" ;
                ex:street "Business Blvd"
            ] .
        """;

    tripleStore.addTriples(turtleData, "TEST");
    long initialSize = tripleStore.size();

    // Remove only the work address predicate - should remove one blank node
    int removed = tripleStore.removeTriplesWithBlankNodes(
        "http://example.org/Person1",
        "http://example.org/hasAddress",
        null,
        "TEST"
    );

    assertTrue("Should remove hasAddress triples and orphaned blank nodes", removed > 0);
    assertTrue("Person and name should remain", tripleStore.size() > 0);

    // Verify person still exists
    String allTriples = tripleStore.getAllTriples();
    assertTrue("Person should still exist", allTriples.contains("Person1"));
    assertTrue("Name should still exist", allTriples.contains("Alice"));
  }

  @Test
  public void testRemoveTriples_BlankNodeChain_ShouldRemoveEntireChain() {
    // Create a chain of blank nodes: Person -> Location (blank) -> GeoLocation (blank)
    String turtleData = """
        @prefix ex: <http://example.org/> .
        
        ex:Person1 ex:hasLocation _:loc1 .
        
        _:loc1 a ex:Location ;
            ex:street "Main St" ;
            ex:hasGeo _:geo1 .
        
        _:geo1 a ex:GeoLocation ;
            ex:lat "40.7128" ;
            ex:lon "-74.0060" .
        """;

    tripleStore.addTriples(turtleData, "TEST");
    long initialSize = tripleStore.size();
    assertTrue("Should have at least one triple, got: " + initialSize, initialSize > 0);

    // Remove the person's location - entire chain should be removed
    int removed = tripleStore.removeTriplesWithBlankNodes(
        "http://example.org/Person1",
        "http://example.org/hasLocation",
        null,
        "TEST"
    );

    assertTrue("Should remove at least some triples", removed > 0);
    assertEquals("Store should be empty after removing the entire chain", 0, tripleStore.size());
  }
}




