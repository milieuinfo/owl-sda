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

/** Tests for {@link OutputWriterHandler}. */
public class OutputWriterHandlerTest {

  private OutputWriterHandler handler;
  private Path tempFile;
  private Config config;

  @Before
  public void setUp() throws Exception {
    tempFile = Files.createTempFile("test-output-writer", ".ttl");
    config = new Config();
    config.setOutputPath(tempFile.toString());
    handler = new OutputWriterHandler(config);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_WritesValidTurtleToFile() throws Exception {
    String output = "@prefix ex: <http://example.org/> .\nex:Person1 a ex:Person .";

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("output", output)).join();

    assertEquals("success", response.get("status"));
    assertEquals(tempFile.toString(), response.get("file_path"));
    assertEquals(output, Files.readString(tempFile));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_OverwritesExistingContent() throws Exception {
    Files.writeString(tempFile, "@prefix ex: <http://example.org/> .\nex:Old a ex:Person .");

    String newOutput = "@prefix ex: <http://example.org/> .\nex:New a ex:Person .";
    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("output", newOutput)).join();

    assertEquals("success", response.get("status"));
    String content = Files.readString(tempFile);
    assertEquals(newOutput, content);
    assertFalse(content.contains("ex:Old"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_EmptyOutput_DeletesExistingFile() throws Exception {
    Files.writeString(tempFile, "@prefix ex: <http://example.org/> .");

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("output", "")).join();

    assertEquals("deleted", response.get("status"));
    assertFalse(Files.exists(tempFile));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_EmptyOutputWithNoExistingFile_ReturnsNoFileStatus() throws Exception {
    Files.deleteIfExists(tempFile);

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("output", "")).join();

    assertEquals("no_file", response.get("status"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_MissingOutputPath_ReturnsError() {
    Config emptyConfig = new Config();
    emptyConfig.setOutputPath(null);
    OutputWriterHandler invalidHandler = new OutputWriterHandler(emptyConfig);

    Map<String, Object> response =
        (Map<String, Object>) invalidHandler.handle(Map.of("output", "data")).join();

    assertTrue(response.containsKey("error"));
    assertTrue(response.get("error").toString().contains("not configured"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_InvalidTurtle_IsRejectedAndFileNotWritten() throws Exception {
    Files.deleteIfExists(tempFile);

    Map<String, Object> response =
        (Map<String, Object>)
            handler.handle(Map.of("output", "ex:Person1 ex:name \"unterminated")).join();

    assertTrue(response.containsKey("error"));
    assertFalse(Files.exists(tempFile));
  }
}
