package be.vlaanderen.omgeving.owlsda.generation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.util.List;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

public class ShapeProcessingTrackerTest {

  // Shacl.generate() names each generated shape "<ClassLocalName>Shape" (the NodeShape resource
  // is minted at <classUri>Shape and Shacl.Shape#getName() reads the *shape* resource's local
  // name after refresh() rebuilds the shapes list) -- not the bare class local name.
  @Test
  public void markCompleted_SingleShape_AddsToCompletedSet() {
    Shacl shacl = shaclWithClasses("Sensor", "Device");
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);

    tracker.markCompleted("SensorShape");

    ShapeProcessingTracker.ShapeProcessingStatus status = tracker.getStatus();
    assertEquals(2, status.getTotalShapes());
    assertEquals(1, status.getCompletedCount());
    assertTrue(status.getCompletedShapes().contains("SensorShape"));
  }

  @Test
  public void markCompleted_ListOfShapes_MarksAll() {
    Shacl shacl = shaclWithClasses("Sensor", "Device", "Gateway");
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);

    tracker.markCompleted(List.of("SensorShape", "DeviceShape"));

    ShapeProcessingTracker.ShapeProcessingStatus status = tracker.getStatus();
    assertEquals(3, status.getTotalShapes());
    assertEquals(2, status.getCompletedCount());
    assertTrue(status.getCompletedShapes().containsAll(List.of("SensorShape", "DeviceShape")));
  }

  @Test
  public void markCompleted_AlsoFlagsUnderlyingShaclShapeAsProcessed() {
    Shacl shacl = shaclWithClasses("Sensor");
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);

    tracker.markCompleted("SensorShape");

    Shacl.Shape shape =
        shacl.getShapes().stream().filter(s -> s.getName().equals("SensorShape")).findFirst().get();
    assertTrue(shape.isProcessed());
  }

  @Test
  public void markCompleted_SameShapeTwice_DoesNotDoubleCount() {
    Shacl shacl = shaclWithClasses("Sensor", "Device");
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);

    tracker.markCompleted("SensorShape");
    tracker.markCompleted("SensorShape");

    assertEquals(1, tracker.getStatus().getCompletedCount());
  }

  @Test
  public void getStatus_RemainingCountIsTotalMinusCompleted() {
    Shacl shacl = shaclWithClasses("Sensor", "Device", "Gateway");
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);

    tracker.markCompleted("SensorShape");

    assertEquals(2, tracker.getStatus().getRemainingCount());
  }

  @Test
  public void getStatus_RemainingCountNeverNegative() {
    Shacl shacl = shaclWithClasses("Sensor");
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);

    // Marking a name that isn't a real shape can push completedCount above totalShapes.
    tracker.markCompleted(List.of("SensorShape", "NotARealShape"));

    ShapeProcessingTracker.ShapeProcessingStatus status = tracker.getStatus();
    assertEquals(2, status.getCompletedCount());
    assertEquals(1, status.getTotalShapes());
    assertEquals(0, status.getRemainingCount());
  }

  @Test
  public void getStatusReport_ContainsCountsAndCompletedShapeNames() {
    Shacl shacl = shaclWithClasses("Sensor", "Device");
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);

    tracker.markCompleted("SensorShape");

    String report = tracker.getStatusReport();
    assertTrue(report.contains("Total Shapes: 2"));
    assertTrue(report.contains("Completed: 1"));
    assertTrue(report.contains("Remaining: 1"));
    assertTrue(report.contains("SensorShape"));
  }

  @Test
  public void getStatusReport_WithNoCompletedShapes_OmitsCompletedSection() {
    Shacl shacl = shaclWithClasses("Sensor");
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);

    String report = tracker.getStatusReport();

    assertTrue(report.contains("Completed: 0"));
    assertFalse(report.contains("COMPLETED SHAPES:"));
  }

  @Test
  public void allShapesCompleted_FalseWhenShapesRemain() {
    Shacl shacl = shaclWithClasses("Sensor", "Device");
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);

    tracker.markCompleted("SensorShape");

    assertFalse(tracker.allShapesCompleted());
  }

  @Test
  public void allShapesCompleted_TrueWhenAllMarked() {
    Shacl shacl = shaclWithClasses("Sensor", "Device");
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);

    tracker.markCompleted(List.of("SensorShape", "DeviceShape"));

    assertTrue(tracker.allShapesCompleted());
  }

  @Test
  public void allShapesCompleted_FalseWhenNoShapesExistAtAll() {
    Shacl shacl = shaclWithClasses(); // zero classes -> zero shapes
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);

    // totalShapes == 0, so this must not vacuously report "complete".
    assertFalse(tracker.allShapesCompleted());
  }

  @Test
  public void allShapesCompleted_FalseWhenShaclIsNull() {
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(null);

    assertFalse(tracker.allShapesCompleted());
  }

  @Test
  public void reset_ClearsCompletedShapesAndProcessedFlags() {
    Shacl shacl = shaclWithClasses("Sensor", "Device");
    ShapeProcessingTracker tracker = new ShapeProcessingTracker(shacl);
    tracker.markCompleted(List.of("SensorShape", "DeviceShape"));
    assertTrue(tracker.allShapesCompleted());

    tracker.reset();

    ShapeProcessingTracker.ShapeProcessingStatus status = tracker.getStatus();
    assertEquals(0, status.getCompletedCount());
    assertTrue(status.getCompletedShapes().isEmpty());
    assertFalse(tracker.allShapesCompleted());
  }

  private Shacl shaclWithClasses(String... localNames) {
    Model ontology = ModelFactory.createDefaultModel();
    String ns = "http://example.org/";
    ontology.setNsPrefix("ex", ns);
    for (String localName : localNames) {
      Resource cls = ontology.createResource(ns + localName);
      cls.addProperty(RDF.type, OWL.Class);
    }

    Shacl shacl = Shacl.fromOntology(ontology);
    shacl.generate();
    return shacl;
  }
}
