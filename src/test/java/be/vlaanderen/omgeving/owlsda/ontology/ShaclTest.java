package be.vlaanderen.omgeving.owlsda.ontology;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.ontology.Shacl.Shape;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.shacl.ValidationReport;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link Shacl}. */
public class ShaclTest {

  private static final String ONTOLOGY_TTL =
      """
      @prefix ex: <http://example.org/> .
      @prefix owl: <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

      ex:Person a owl:Class ;
        rdfs:comment "A human being." ;
        rdfs:subClassOf [
          a owl:Restriction ;
          owl:onProperty ex:hasName ;
          owl:allValuesFrom xsd:string ;
          owl:minCardinality 1
        ] .
      """;

  private Path tempDir;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("shacl-test");
  }

  private Model readTurtle(String turtle) {
    Model model = ModelFactory.createDefaultModel();
    model.read(new StringReader(turtle), null, "TURTLE");
    return model;
  }

  private Model ontologyModel() {
    return readTurtle(ONTOLOGY_TTL);
  }

  @Test
  public void generate_CreatesOneShapePerOwlClass() {
    Shacl shacl = new Shacl(ontologyModel());

    shacl.generate();

    assertEquals(1, shacl.getShapes().size());
    Shape shape = shacl.getShapes().get(0);
    // Note: generate() ends by calling refresh(), which rebuilds Shape objects straight from the
    // model via the Shape(Model) constructor. That constructor derives the name from the
    // sh:NodeShape resource's local name (e.g. "PersonShape" for class ex:Person), not the
    // owl:Class local name used by the Shape(Resource, Model) constructor during generation.
    assertEquals("PersonShape", shape.getName());
    assertTrue(shape.getTurtle().contains("NodeShape"));
    assertTrue(shacl.getTurtle().contains("targetClass"));
  }

  @Test
  public void generate_ShapeRequiresHasNameProperty() {
    Shacl shacl = new Shacl(ontologyModel());
    shacl.generate();

    String turtle = shacl.getTurtle();
    assertTrue(turtle.contains("hasName"));
    assertTrue(turtle.contains("minCount"));
  }

  @Test
  public void validate_ConformingDataReportsConforms() {
    Shacl shacl = new Shacl(ontologyModel());
    shacl.generate();

    Model data =
        readTurtle(
            """
            @prefix ex: <http://example.org/> .

            ex:p1 a ex:Person ;
              ex:hasName "Alice" .
            """);

    ValidationReport report = shacl.validate(data);

    assertTrue(report.conforms());
  }

  @Test
  public void validate_NonConformingDataReportsViolations() {
    Shacl shacl = new Shacl(ontologyModel());
    shacl.generate();

    Model data =
        readTurtle(
            """
            @prefix ex: <http://example.org/> .

            ex:p2 a ex:Person .
            """);

    ValidationReport report = shacl.validate(data);

    assertFalse(report.conforms());
    assertFalse(report.getEntries().isEmpty());
  }

  @Test
  public void saveAndLoad_RoundTripsShapesFile() throws Exception {
    Shacl source = new Shacl(ontologyModel());
    source.generate();

    Path shapesFile = tempDir.resolve("nested/shapes.ttl");
    source.save(shapesFile.toString());

    assertTrue(Files.exists(shapesFile));

    Shacl loaded = new Shacl(ontologyModel());
    loaded.load(shapesFile.toString(), true);

    assertEquals(1, loaded.getShapes().size());
    assertEquals("PersonShape", loaded.getShapes().get(0).getName());

    // The reloaded shapes should validate data the same way as the original.
    Model conforming =
        readTurtle(
            """
            @prefix ex: <http://example.org/> .

            ex:p1 a ex:Person ;
              ex:hasName "Alice" .
            """);
    assertTrue(loaded.validate(conforming).conforms());
  }

  @Test
  public void shapeConstructor_FromExistingModelExtractsNameAndComment() {
    Shacl shacl = new Shacl(ontologyModel());
    shacl.generate();

    Shape original = shacl.getShapes().get(0);
    Shape rebuilt = shacl.new Shape(original.getModel());

    assertEquals(original.getName(), rebuilt.getName());
    assertEquals(original.getComment(), rebuilt.getComment());
  }

  @Test
  public void processedFlag_DefaultsToFalseAndIsSettable() {
    Shacl shacl = new Shacl(ontologyModel());
    shacl.generate();

    Shape shape = shacl.getShapes().get(0);
    assertFalse(shape.isProcessed());

    shape.setProcessed(true);

    assertTrue(shape.isProcessed());
  }

  @Test
  public void twoArgConstructor_UsesProvidedShaclModel() {
    Model shaclModel = ModelFactory.createDefaultModel();
    Shacl shacl = new Shacl(ontologyModel(), shaclModel);

    assertEquals(shaclModel, shacl.getModel());
  }

  @Test
  public void fromOntology_FactoryMethodCreatesUsableInstance() {
    Shacl shacl = Shacl.fromOntology(ontologyModel());

    assertNotNull(shacl.getModel());
    shacl.generate();
    assertEquals(1, shacl.getShapes().size());
  }
}
