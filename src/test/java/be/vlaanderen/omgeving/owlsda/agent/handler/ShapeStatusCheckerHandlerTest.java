package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.generation.ShapeProcessingTracker;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link ShapeStatusCheckerHandler} against a {@link ShapeProcessingTracker} fixture with
 * two known shapes, one marked completed via {@code markCompleted(...)}.
 */
public class ShapeStatusCheckerHandlerTest {

  private ShapeProcessingTracker tracker;
  private ShapeStatusCheckerHandler handler;

  @Before
  public void setUp() throws Exception {
    Path shapesFile = Files.createTempFile("shape-status-checker-test", ".ttl");
    Files.writeString(
        shapesFile,
        """
        @prefix sh: <http://www.w3.org/ns/shacl#> .
        @prefix ex: <http://example.org/> .

        ex:PersonShape a sh:NodeShape ;
          sh:targetClass ex:Person ;
          sh:property [
            sh:path ex:name ;
            sh:minCount 1 ;
          ] .

        ex:OrganizationShape a sh:NodeShape ;
          sh:targetClass ex:Organization ;
          sh:property [
            sh:path ex:legalName ;
            sh:minCount 1 ;
          ] .
        """);

    Model ontology = ModelFactory.createDefaultModel();
    Shacl shacl = new Shacl(ontology);
    shacl.load(shapesFile.toString(), true);

    tracker = new ShapeProcessingTracker(shacl);
    tracker.markCompleted("PersonShape");

    handler = new ShapeStatusCheckerHandler(tracker);
  }

  @Test
  public void getName_ReturnsCheckShapeStatus() {
    assertEquals("check_shape_status", handler.getName());
    assertEquals(ShapeStatusCheckerHandler.NAME, handler.getName());
  }

  @Test
  public void handle_SummaryFormat_ReportsTotalCompletedAndRemainingCounts() {
    Object result = handler.handle(Map.of("format", "summary")).join();

    assertTrue(result instanceof String);
    String report = (String) result;
    assertTrue(report.contains("Total: 2 shapes"));
    assertTrue(report.contains("Completed: 1"));
    assertTrue(report.contains("Remaining: 1"));
  }

  @Test
  public void handle_DetailedFormat_ReportsFullShapeList() {
    Object result = handler.handle(Map.of("format", "detailed")).join();

    assertTrue(result instanceof String);
    String report = (String) result;
    assertTrue(report.contains("Total Shapes: 2"));
    assertTrue(report.contains("Completed: 1"));
    assertTrue(report.contains("Remaining: 1"));
    assertTrue(report.contains("PersonShape"));
    // Only completed shapes are listed by name.
    assertTrue(!report.contains("OrganizationShape"));
  }

  @Test
  public void handle_DefaultFormat_IsDetailed() {
    Object withoutFormat = handler.handle(Map.of()).join();
    Object explicitlyDetailed = handler.handle(Map.of("format", "detailed")).join();

    assertEquals(explicitlyDetailed, withoutFormat);
  }

  @Test
  public void handle_AllShapesCompleted_SummaryReportsNoRemainingWarning() {
    tracker.markCompleted("OrganizationShape");

    String report = (String) handler.handle(Map.of("format", "summary")).join();

    assertTrue(report.contains("Completed: 2"));
    assertTrue(report.contains("Remaining: 0"));
    assertTrue(report.contains("All shapes have been completed by workers."));
  }
}
