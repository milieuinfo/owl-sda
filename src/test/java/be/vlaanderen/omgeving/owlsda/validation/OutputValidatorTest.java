package be.vlaanderen.omgeving.owlsda.validation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import be.vlaanderen.omgeving.owlsda.exception.OntologyException;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link OutputValidator}. */
public class OutputValidatorTest {

  private static final String ONTOLOGY_TTL =
      """
      @prefix ex: <http://example.org/> .
      @prefix owl: <http://www.w3.org/2002/07/owl#> .
      @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
      @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .

      ex:Person a owl:Class ;
        rdfs:subClassOf [
          a owl:Restriction ;
          owl:onProperty ex:hasName ;
          owl:allValuesFrom xsd:string ;
          owl:minCardinality 1
        ] .
      """;

  private static final String CONFORMING_DATA_TTL =
      """
      @prefix ex: <http://example.org/> .

      ex:p1 a ex:Person ;
        ex:hasName "Alice" .
      """;

  private static final String NON_CONFORMING_DATA_TTL =
      """
      @prefix ex: <http://example.org/> .

      ex:p2 a ex:Person .
      """;

  private Path tempDir;
  private Shacl shacl;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("output-validator-test");

    Model ontologyModel = ModelFactory.createDefaultModel();
    ontologyModel.read(new StringReader(ONTOLOGY_TTL), null, "TURTLE");
    shacl = new Shacl(ontologyModel);
    shacl.generate();
  }

  private Path writeOutputFile(String content) throws Exception {
    Path file = tempDir.resolve("output.ttl");
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  @Test
  public void validate_ConformingData_ReturnsNull() throws Exception {
    Path outputFile = writeOutputFile(CONFORMING_DATA_TTL);
    OutputValidator validator = new OutputValidator(outputFile.toString(), shacl);

    assertNull(validator.validate());
    assertTrue(validator.isValid());
  }

  @Test
  public void validate_NonConformingData_ReturnsNonEmptyViolationReport() throws Exception {
    Path outputFile = writeOutputFile(NON_CONFORMING_DATA_TTL);
    OutputValidator validator = new OutputValidator(outputFile.toString(), shacl);

    String report = validator.validate();

    assertNotNull(report);
    assertFalse(report.isEmpty());
    assertTrue(report.contains("Violations"));
    assertFalse(validator.isValid());
  }

  @Test
  public void readOutputData_ConformingFile_ParsesIntoModel() throws Exception {
    Path outputFile = writeOutputFile(CONFORMING_DATA_TTL);
    OutputValidator validator = new OutputValidator(outputFile.toString(), shacl);

    Model data = validator.readOutputData();

    assertFalse(data.isEmpty());
  }

  @Test
  public void getOutputDataAsString_NullOutputPath_ThrowsOntologyException() {
    OutputValidator validator = new OutputValidator(null, shacl);

    try {
      validator.getOutputDataAsString();
      fail("Expected OntologyException when outputPath is null");
    } catch (OntologyException e) {
      assertTrue(e.getMessage().contains("not configured"));
    }
  }

  @Test
  public void getOutputDataAsString_UnreadableFile_ThrowsOntologyException() {
    Path missingFile = tempDir.resolve("does-not-exist.ttl");
    OutputValidator validator = new OutputValidator(missingFile.toString(), shacl);

    try {
      validator.getOutputDataAsString();
      fail("Expected OntologyException when the output file cannot be read");
    } catch (OntologyException e) {
      assertNotNull(e.getCause());
    }
  }

  @Test
  public void validate_UnreadableOutputPath_ReturnsErrorMessageInsteadOfThrowing() {
    Path missingFile = tempDir.resolve("does-not-exist.ttl");
    OutputValidator validator = new OutputValidator(missingFile.toString(), shacl);

    String result = validator.validate();

    assertNotNull(result);
    assertFalse(validator.isValid());
  }

  @Test
  public void getters_ExposeConstructorArguments() {
    OutputValidator validator = new OutputValidator("some/path.ttl", shacl);

    assertTrue(validator.getOutputPath().equals("some/path.ttl"));
    assertNotNull(validator.getShacl());
  }
}
