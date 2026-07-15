package be.vlaanderen.omgeving.owlsda.generation;

import static org.junit.Assert.assertEquals;

import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.lang.reflect.Method;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.Test;

public class SupervisorReviewCoordinatorBenchmarkTest {

  @Test
  public void resolveShapesProcessed_CountsOnlyProcessedShapes() throws Exception {
    Model ontology = ModelFactory.createDefaultModel();
    Shacl shacl = new Shacl(ontology);

    Shacl.Shape firstShape =
        shacl.new Shape(ontology.createResource("http://example.org/ShapeA"), ontology);
    Shacl.Shape secondShape =
        shacl.new Shape(ontology.createResource("http://example.org/ShapeB"), ontology);
    Shacl.Shape thirdShape =
        shacl.new Shape(ontology.createResource("http://example.org/ShapeC"), ontology);

    firstShape.setProcessed(true);
    secondShape.setProcessed(false);
    thirdShape.setProcessed(true);

    shacl.getShapes().add(firstShape);
    shacl.getShapes().add(secondShape);
    shacl.getShapes().add(thirdShape);

    Supervisor supervisor = new Supervisor(null, null, null, shacl, null, null);
    SupervisorReviewCoordinator coordinator =
        new SupervisorReviewCoordinator(null, supervisor, null, null, null);

    Method method = SupervisorReviewCoordinator.class.getDeclaredMethod("resolveShapesProcessed");
    method.setAccessible(true);

    int processed = (int) method.invoke(coordinator);
    assertEquals(2, processed);
  }
}
