package be.vlaanderen.omgeving.owlsda.generation;

import java.io.StringReader;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShapeValidationMatcherTest {

  @Test
  public void hasInstancesForTargetClass_ReturnsTrueForExactTypeMatch() {
    Model dataModel = readTurtle("""
        @prefix ex: <http://example.org/> .

        ex:v1 a ex:ProcessVariable .
        """);

    Resource targetClass = dataModel.createResource("http://example.org/ProcessVariable");

    boolean result = ShapeValidationMatcher.hasInstancesForTargetClass(
        dataModel,
        null,
        RDF.type,
        targetClass
    );

    assertTrue(result);
  }

  @Test
  public void hasInstancesForTargetClass_ReturnsTrueWhenTargetIsSubclassOfActualType() {
    Model ontologyModel = readTurtle("""
        @prefix ex: <http://example.org/> .
        @prefix pplan: <http://purl.org/net/p-plan#> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

        ex:ProcessVariable rdfs:subClassOf pplan:Variable .
        """);

    Model dataModel = readTurtle("""
        @prefix ex: <http://example.org/> .
        @prefix pplan: <http://purl.org/net/p-plan#> .

        ex:V-1 a pplan:Variable .
        """);

    Resource targetClass = dataModel.createResource("http://example.org/ProcessVariable");

    boolean result = ShapeValidationMatcher.hasInstancesForTargetClass(
        dataModel,
        ontologyModel,
        RDF.type,
        targetClass
    );

    assertTrue(result);
  }

  @Test
  public void hasInstancesForTargetClass_ReturnsFalseWithoutOntologyBridge() {
    Model dataModel = readTurtle("""
        @prefix ex: <http://example.org/> .
        @prefix pplan: <http://purl.org/net/p-plan#> .

        ex:V-1 a pplan:Variable .
        """);

    Resource targetClass = dataModel.createResource("http://example.org/ProcessVariable");

    boolean result = ShapeValidationMatcher.hasInstancesForTargetClass(
        dataModel,
        null,
        RDF.type,
        targetClass
    );

    assertFalse(result);
  }

  @Test
  public void hasInstancesForTargetClass_ReturnsFalseWhenClassesAreUnrelated() {
    Model ontologyModel = readTurtle("""
        @prefix ex: <http://example.org/> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

        ex:Sensor rdfs:subClassOf ex:Device .
        """);

    Model dataModel = readTurtle("""
        @prefix ex: <http://example.org/> .

        ex:s1 a ex:Sensor .
        """);

    Resource targetClass = dataModel.createResource("http://example.org/ProcessVariable");

    boolean result = ShapeValidationMatcher.hasInstancesForTargetClass(
        dataModel,
        ontologyModel,
        RDF.type,
        targetClass
    );

    assertFalse(result);
  }

  private Model readTurtle(String turtle) {
    Model model = ModelFactory.createDefaultModel();
    model.read(new StringReader(turtle), null, "TURTLE");
    return model;
  }
}

