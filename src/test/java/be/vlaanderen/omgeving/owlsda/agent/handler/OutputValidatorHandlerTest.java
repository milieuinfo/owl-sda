package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.context.ValidationContext;
import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link OutputValidatorHandler} against a small real SHACL fixture (a shape requiring
 * ex:name on ex:Person), covering the "data" and "file" sources with both conforming and
 * non-conforming input, plus the {@code ValidationContext}-publishing callback.
 */
public class OutputValidatorHandlerTest {

  private static final String CONFORMING_DATA =
      """
      @prefix ex: <http://example.org/> .
      ex:Person1 a ex:Person ;
        ex:name "Alice" .
      """;

  private static final String NON_CONFORMING_DATA =
      """
      @prefix ex: <http://example.org/> .
      ex:Person1 a ex:Person .
      """;

  private Shacl shacl;

  @Before
  public void setUp() throws Exception {
    Path shapesFile = Files.createTempFile("output-validator-test", ".ttl");
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
        """);

    Model ontology = ModelFactory.createDefaultModel();
    shacl = new Shacl(ontology);
    shacl.load(shapesFile.toString(), true);
  }

  @Test
  public void getName_ReturnsShaclValidator() {
    OutputValidatorHandler handler = new OutputValidatorHandler(shacl, (Config) null);
    assertEquals("shacl_validator", handler.getName());
    assertEquals(OutputValidatorHandler.NAME, handler.getName());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_DataSource_ConformingData_ReturnsValidStatus() {
    OutputValidatorHandler handler = new OutputValidatorHandler(shacl, (Config) null);

    Map<String, Object> response =
        (Map<String, Object>)
            handler.handle(Map.of("source", "data", "data", CONFORMING_DATA)).join();

    assertEquals("valid", response.get("status"));
    assertEquals(0, response.get("violations"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_DataSource_NonConformingData_ReturnsInvalidWithReport() {
    OutputValidatorHandler handler = new OutputValidatorHandler(shacl, (Config) null);

    Map<String, Object> response =
        (Map<String, Object>)
            handler.handle(Map.of("source", "data", "data", NON_CONFORMING_DATA)).join();

    assertEquals("invalid", response.get("status"));
    assertTrue(((Number) response.get("violations")).intValue() > 0);
    assertNotNull(response.get("report"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_DataSource_NonConformingData_FormatOnErrorFalse_OmitsReport() {
    OutputValidatorHandler handler = new OutputValidatorHandler(shacl, (Config) null);

    Map<String, Object> response =
        (Map<String, Object>)
            handler
                .handle(
                    Map.of("source", "data", "data", NON_CONFORMING_DATA, "format-on-error", false))
                .join();

    assertEquals("invalid", response.get("status"));
    assertTrue(!response.containsKey("report"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_DataSource_MissingData_ReturnsError() {
    OutputValidatorHandler handler = new OutputValidatorHandler(shacl, (Config) null);

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("source", "data")).join();

    assertTrue(response.containsKey("error"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_FileSource_ConformingFile_ReturnsValidStatus() throws Exception {
    Path outputFile = Files.createTempFile("output-validator-file-test", ".ttl");
    Files.writeString(outputFile, CONFORMING_DATA);
    Config config = new Config();
    config.setOutputPath(outputFile.toString());
    OutputValidatorHandler handler = new OutputValidatorHandler(shacl, config);

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("source", "file")).join();

    assertEquals("valid", response.get("status"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_FileSource_MissingOutputPath_ReturnsError() {
    Config config = new Config();
    config.setOutputPath(null);
    OutputValidatorHandler handler = new OutputValidatorHandler(shacl, config);

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("source", "file")).join();

    assertTrue(response.containsKey("error"));
    assertTrue(response.get("error").toString().contains("not configured"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_UnknownSource_ReturnsError() {
    OutputValidatorHandler handler = new OutputValidatorHandler(shacl, (Config) null);

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("source", "bogus")).join();

    assertTrue(response.containsKey("error"));
    assertTrue(response.get("error").toString().contains("Unknown source"));
  }

  @Test
  public void handle_ConformingData_PublishesPassingValidationContext() {
    AtomicReference<ValidationContext> published = new AtomicReference<>();
    OutputValidatorHandler handler = new OutputValidatorHandler(shacl, null, null, published::set);

    handler.handle(Map.of("source", "data", "data", CONFORMING_DATA)).join();

    assertNotNull(published.get());
    assertTrue(published.get().getContent().contains("SHACL validation passed"));
  }

  @Test
  public void handle_NonConformingData_PublishesValidationContextWithViolationReport() {
    AtomicReference<ValidationContext> published = new AtomicReference<>();
    OutputValidatorHandler handler = new OutputValidatorHandler(shacl, null, null, published::set);

    handler.handle(Map.of("source", "data", "data", NON_CONFORMING_DATA)).join();

    assertNotNull(published.get());
    assertTrue(published.get().getContent().contains("Data does NOT conform to SHACL shapes"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_StoreSource_WithoutTripleStore_ReturnsError() {
    OutputValidatorHandler handler = new OutputValidatorHandler(shacl, (Config) null);

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("source", "store")).join();

    assertTrue(response.containsKey("error"));
  }
}
