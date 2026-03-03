package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.config.Config;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;

/**
 * Tests for OutputReplaceHandler.
 */
public class OutputReplaceHandlerTest {

  private OutputReplaceHandler handler;
  private Path tempFile;
  private Config config;

  @Before
  public void setUp() throws Exception {
    tempFile = Files.createTempFile("test-output-replace", ".ttl");
    config = new Config();
    config.setOutputPath(tempFile.toString());
    handler = new OutputReplaceHandler(config);
  }

  @Test
  public void testReplaceEntireFile() throws Exception {
    // Write initial content
    String initialContent = """
        @prefix ex: <http://example.org/> .
        
        ex:Person1 a ex:Person .
        ex:Person2 a ex:Person .
        """;
    Files.writeString(tempFile, initialContent);

    // Replace entire file (no line range specified)
    String replacement = """
        @prefix ex: <http://example.org/> .
        
        ex:NewPerson a ex:Person .
        """;

    Map<String, Object> arguments = Map.of("output", replacement);
    CompletableFuture<Object> result = handler.handle(arguments);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) result.get();
    assertEquals("success", response.get("status"));

    String newContent = Files.readString(tempFile);
    assertEquals(replacement, newContent);
  }

  @Test
  public void testReplaceMiddleLines() throws Exception {
    // Write initial content with line numbers
    String initialContent = """
        Line 1
        Line 2
        Line 3
        Line 4
        Line 5
        """;
    Files.writeString(tempFile, initialContent);

    // Replace lines 2-4
    String replacement = "Replaced Lines";

    Map<String, Object> arguments = Map.of(
        "output", replacement,
        "start_line", 2,
        "end_line", 4
    );

    CompletableFuture<Object> result = handler.handle(arguments);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) result.get();
    assertEquals("success", response.get("status"));
    assertEquals(2, response.get("start_line"));
    assertEquals(4, response.get("end_line"));
    assertEquals(3, response.get("lines_replaced"));

    String newContent = Files.readString(tempFile);
    String[] lines = newContent.split("\n");
    assertEquals("Line 1", lines[0]);
    assertEquals("Replaced Lines", lines[1]);
    assertEquals("Line 5", lines[2]);
  }

  @Test
  public void testReplaceFirstLine() throws Exception {
    String initialContent = """
        @prefix ex: <http://example.org/> .
        
        ex:Person1 a ex:Person .
        """;
    Files.writeString(tempFile, initialContent);

    String replacement = "@prefix ex: <http://newexample.org/> .";

    Map<String, Object> arguments = Map.of(
        "output", replacement,
        "start_line", 1,
        "end_line", 1
    );

    CompletableFuture<Object> result = handler.handle(arguments);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) result.get();
    assertEquals("success", response.get("status"));

    String newContent = Files.readString(tempFile);
    assertTrue(newContent.contains("newexample.org"));
    assertTrue(newContent.contains("ex:Person1"));
  }

  @Test
  public void testReplaceLastLine() throws Exception {
    String initialContent = """
        Line 1
        Line 2
        Line 3
        """;
    Files.writeString(tempFile, initialContent);

    String replacement = "New Last Line";

    Map<String, Object> arguments = Map.of(
        "output", replacement,
        "start_line", 3,
        "end_line", 3
    );

    CompletableFuture<Object> result = handler.handle(arguments);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) result.get();
    assertEquals("success", response.get("status"));

    String newContent = Files.readString(tempFile);
    String[] lines = newContent.split("\n");
    assertEquals("Line 1", lines[0]);
    assertEquals("Line 2", lines[1]);
    assertEquals("New Last Line", lines[2]);
  }

  @Test
  public void testReplaceNonExistentFile() throws Exception {
    // Delete the temp file to simulate non-existent file
    Files.deleteIfExists(tempFile);

    String replacement = "New content";

    Map<String, Object> arguments = Map.of("output", replacement);
    CompletableFuture<Object> result = handler.handle(arguments);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) result.get();
    assertEquals("success", response.get("status"));

    assertTrue(Files.exists(tempFile));
    String newContent = Files.readString(tempFile);
    assertEquals(replacement, newContent);
  }

  @Test
  public void testReplaceWithMultilineContent() throws Exception {
    String initialContent = """
        Line 1
        Line 2
        Line 3
        """;
    Files.writeString(tempFile, initialContent);

    String replacement = """
        Replacement Line A
        Replacement Line B
        Replacement Line C
        """;

    Map<String, Object> arguments = Map.of(
        "output", replacement,
        "start_line", 2,
        "end_line", 2
    );

    CompletableFuture<Object> result = handler.handle(arguments);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) result.get();
    assertEquals("success", response.get("status"));
    assertEquals(1, response.get("lines_replaced"));

    String newContent = Files.readString(tempFile);
    assertTrue(newContent.contains("Line 1"));
    assertTrue(newContent.contains("Replacement Line A"));
    assertTrue(newContent.contains("Replacement Line B"));
    assertTrue(newContent.contains("Replacement Line C"));
    assertTrue(newContent.contains("Line 3"));
  }

  @Test
  public void testMissingOutputPath() {
    Config emptyConfig = new Config();
    emptyConfig.setOutputPath(null);
    OutputReplaceHandler invalidHandler = new OutputReplaceHandler(emptyConfig);

    Map<String, Object> arguments = Map.of("output", "test");
    CompletableFuture<Object> result = invalidHandler.handle(arguments);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) result.get();
    assertTrue(response.containsKey("error"));
    assertTrue(response.get("error").toString().contains("not configured"));
  }

  @Test
  public void testNullOutput() throws Exception {
    Files.writeString(tempFile, "Initial content");

    Map<String, Object> arguments = Map.of("start_line", 1);
    // Note: output is missing, should be null

    CompletableFuture<Object> result = handler.handle(arguments);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = (Map<String, Object>) result.get();
    assertTrue(response.containsKey("error"));
    assertTrue(response.get("error").toString().contains("No output provided"));
  }
}

