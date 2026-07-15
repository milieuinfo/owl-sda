package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link OutputAppendHandler}. */
public class OutputAppendHandlerTest {

  private OutputAppendHandler handler;
  private Path tempFile;
  private Config config;

  @Before
  public void setUp() throws Exception {
    tempFile = Files.createTempFile("test-output-append", ".ttl");
    Files.delete(tempFile); // start from a non-existent file, like a fresh output path
    config = new Config();
    config.setOutputPath(tempFile.toString());
    handler = new OutputAppendHandler(config);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_AppendsToEmptyFile() throws Exception {
    String output = "@prefix ex: <http://example.org/> .\nex:Person1 a ex:Person .";

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("output", output)).join();

    assertEquals("success", response.get("status"));
    assertEquals("append", response.get("operation"));
    assertEquals(output, Files.readString(tempFile));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_AppendsWithNewlineSeparatorWhenFileNonEmpty() throws Exception {
    Files.writeString(tempFile, "@prefix ex: <http://example.org/> .\nex:Person1 a ex:Person .");

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("output", "ex:Person2 a ex:Person .")).join();

    assertEquals("success", response.get("status"));
    String content = Files.readString(tempFile);
    assertTrue(content.contains("ex:Person1 a ex:Person ."));
    assertTrue(content.contains("ex:Person2 a ex:Person ."));
    // A newline should separate the two appended chunks.
    assertTrue(content.contains("ex:Person1 a ex:Person .\nex:Person2 a ex:Person ."));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_DeduplicatesPrefixesAlreadyPresentInFile() throws Exception {
    Files.writeString(tempFile, "@prefix ex: <http://example.org/> .\n\nex:Person1 a ex:Person .");

    String duplicatePrefixOutput =
        "@prefix ex: <http://example.org/> .\n\nex:Person2 a ex:Person .";

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("output", duplicatePrefixOutput)).join();

    assertEquals("success", response.get("status"));
    String content = Files.readString(tempFile);

    long prefixCount = content.lines().filter(line -> line.trim().startsWith("@prefix")).count();
    assertEquals(1, prefixCount);
    assertTrue(content.contains("ex:Person2 a ex:Person ."));
    assertEquals(1, response.get("prefixes_deduplicated"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_InsertsAtSpecificLine() throws Exception {
    // Use comment-only lines (valid, empty-graph Turtle) so the resulting content passes the
    // handler's Turtle syntax validation while still letting us assert on exact line placement.
    Files.writeString(tempFile, "# Line 1\n# Line 2\n# Line 3");

    Map<String, Object> response =
        (Map<String, Object>)
            handler.handle(Map.of("output", "# Inserted Line", "insert-line", 2)).join();

    assertEquals("success", response.get("status"));
    assertEquals("insert", response.get("operation"));
    String[] lines = Files.readString(tempFile).split("\n");
    assertEquals("# Line 1", lines[0]);
    assertEquals("# Inserted Line", lines[1]);
    assertEquals("# Line 2", lines[2]);
    assertEquals("# Line 3", lines[3]);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_MissingOutputPath_ReturnsError() {
    Config emptyConfig = new Config();
    emptyConfig.setOutputPath(null);
    OutputAppendHandler invalidHandler = new OutputAppendHandler(emptyConfig);

    Map<String, Object> response =
        (Map<String, Object>) invalidHandler.handle(Map.of("output", "data")).join();

    assertTrue(response.containsKey("error"));
    assertTrue(response.get("error").toString().contains("not configured"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_MissingOutput_ReturnsError() {
    Map<String, Object> response = (Map<String, Object>) handler.handle(Map.of()).join();

    assertTrue(response.containsKey("error"));
    assertTrue(response.get("error").toString().contains("No output provided"));
    assertFalse(Files.exists(tempFile));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_InvalidTurtleResult_IsRejectedAndFileUnchanged() throws Exception {
    Files.writeString(tempFile, "@prefix ex: <http://example.org/> .\nex:Person1 a ex:Person .");
    String contentBefore = Files.readString(tempFile);

    Map<String, Object> response =
        (Map<String, Object>)
            handler
                .handle(Map.of("output", "ex:Person2 ex:name \"unterminated"))
                .join(); // unterminated string literal is a genuine Turtle syntax error

    assertTrue(response.containsKey("error"));
    assertEquals(contentBefore, Files.readString(tempFile));
  }
}
