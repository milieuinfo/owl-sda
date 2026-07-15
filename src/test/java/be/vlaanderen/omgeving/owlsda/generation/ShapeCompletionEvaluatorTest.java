package be.vlaanderen.omgeving.owlsda.generation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.util.List;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.Before;
import org.junit.Test;

public class ShapeCompletionEvaluatorTest {

  private WorkerTripleStore tripleStore;

  @Before
  public void setUp() throws Exception {
    java.nio.file.Path tempFile =
        java.nio.file.Files.createTempFile("shape-completion-evaluator", ".ttl");
    tripleStore = new WorkerTripleStore(tempFile.toString());
  }

  @Test
  public void evaluate_ShapeWithConformingInstance_IsComplete() {
    Model ontology = ModelFactory.createDefaultModel();
    String ns = "http://example.org/";
    ontology.setNsPrefix("ex", ns);
    Resource sensorClass = ontology.createResource(ns + "Sensor");
    sensorClass.addProperty(RDF.type, OWL.Class);

    Shacl shacl = Shacl.fromOntology(ontology);
    shacl.generate();

    tripleStore.addTriples("@prefix ex: <http://example.org/> .\nex:s1 a ex:Sensor .", "worker-0");

    ShapeCompletionEvaluator evaluator = new ShapeCompletionEvaluator(tripleStore, shacl);
    ShapeCompletionEvaluator.CompletionBatch batch = evaluator.evaluate(shacl.getShapes(), 1);

    assertEquals(1, batch.completedShapes().size());
    assertEquals(List.of("SensorShape"), batch.completedShapeNames());
    assertTrue(batch.skippedShapeNames().isEmpty());
    assertTrue(batch.skippedByValidationShapeNames().isEmpty());
  }

  @Test
  public void evaluate_ShapeWithNoInstances_IsSkippedNoInstances() {
    Model ontology = ModelFactory.createDefaultModel();
    String ns = "http://example.org/";
    ontology.setNsPrefix("ex", ns);
    Resource sensorClass = ontology.createResource(ns + "Sensor");
    sensorClass.addProperty(RDF.type, OWL.Class);

    Shacl shacl = Shacl.fromOntology(ontology);
    shacl.generate();

    // Store has data, but none of it is an instance of ex:Sensor.
    tripleStore.addTriples("@prefix ex: <http://example.org/> .\nex:d1 a ex:Device .", "worker-0");

    ShapeCompletionEvaluator evaluator = new ShapeCompletionEvaluator(tripleStore, shacl);
    ShapeCompletionEvaluator.CompletionBatch batch = evaluator.evaluate(shacl.getShapes(), 1);

    assertTrue(batch.completedShapes().isEmpty());
    assertEquals(List.of("SensorShape"), batch.skippedShapeNames());
    assertTrue(batch.skippedByValidationShapeNames().isEmpty());
  }

  @Test
  public void evaluate_EmptyTripleStore_IsSkippedNoInstances() {
    Model ontology = ModelFactory.createDefaultModel();
    String ns = "http://example.org/";
    ontology.setNsPrefix("ex", ns);
    Resource sensorClass = ontology.createResource(ns + "Sensor");
    sensorClass.addProperty(RDF.type, OWL.Class);

    Shacl shacl = Shacl.fromOntology(ontology);
    shacl.generate();

    ShapeCompletionEvaluator evaluator = new ShapeCompletionEvaluator(tripleStore, shacl);
    ShapeCompletionEvaluator.CompletionBatch batch = evaluator.evaluate(shacl.getShapes(), 1);

    assertTrue(batch.completedShapes().isEmpty());
    assertEquals(List.of("SensorShape"), batch.skippedShapeNames());
  }

  @Test
  public void evaluate_ShapeWithMinCountViolation_IsSkippedValidation() {
    // Build an ontology where Sensor requires ex:name (minCardinality 1) via an OWL restriction,
    // so Shacl.generate() derives a SHACL minCount constraint, and add a Sensor instance missing
    // that required property so the resulting SHACL report has a violation for it.
    Model ontology = ModelFactory.createDefaultModel();
    String ns = "http://example.org/";
    ontology.setNsPrefix("ex", ns);

    Resource sensorClass = ontology.createResource(ns + "Sensor");
    sensorClass.addProperty(RDF.type, OWL.Class);

    Property nameProp = ontology.createProperty(ns + "name");
    Resource restriction = ontology.createResource();
    restriction.addProperty(RDF.type, OWL.Restriction);
    restriction.addProperty(OWL.onProperty, nameProp);
    restriction.addProperty(OWL.someValuesFrom, org.apache.jena.vocabulary.XSD.xstring);
    restriction.addLiteral(OWL.minCardinality, 1);

    sensorClass.addProperty(org.apache.jena.vocabulary.RDFS.subClassOf, restriction);

    Shacl shacl = Shacl.fromOntology(ontology);
    shacl.generate();

    // Instance of Sensor without the required ex:name property -> SHACL violation.
    tripleStore.addTriples("@prefix ex: <http://example.org/> .\nex:s1 a ex:Sensor .", "worker-0");

    ShapeCompletionEvaluator evaluator = new ShapeCompletionEvaluator(tripleStore, shacl);
    ShapeCompletionEvaluator.CompletionBatch batch = evaluator.evaluate(shacl.getShapes(), 1);

    assertTrue(
        "Expected the shape to be skipped due to validation violations, got completed="
            + batch.completedShapeNames()
            + " skippedNoInstances="
            + batch.skippedShapeNames()
            + " skippedValidation="
            + batch.skippedByValidationShapeNames(),
        batch.skippedByValidationShapeNames().contains("SensorShape"));
    assertTrue(batch.completedShapes().isEmpty());
  }

  @Test
  public void evaluate_ShapesToEvaluateLimitsHowManyAreProcessed() {
    Model ontology = ModelFactory.createDefaultModel();
    String ns = "http://example.org/";
    ontology.setNsPrefix("ex", ns);
    Resource sensorClass = ontology.createResource(ns + "Sensor");
    sensorClass.addProperty(RDF.type, OWL.Class);
    Resource deviceClass = ontology.createResource(ns + "Device");
    deviceClass.addProperty(RDF.type, OWL.Class);

    Shacl shacl = Shacl.fromOntology(ontology);
    shacl.generate();

    tripleStore.addTriples(
        """
        @prefix ex: <http://example.org/> .
        ex:s1 a ex:Sensor .
        ex:d1 a ex:Device .
        """,
        "worker-0");

    ShapeCompletionEvaluator evaluator = new ShapeCompletionEvaluator(tripleStore, shacl);
    // Only evaluate the first shape in the (already sorted) list, not all of them.
    ShapeCompletionEvaluator.CompletionBatch batch = evaluator.evaluate(shacl.getShapes(), 1);

    int totalHandled =
        batch.completedShapes().size()
            + batch.skippedShapeNames().size()
            + batch.skippedByValidationShapeNames().size();
    assertEquals(1, totalHandled);
  }
}
