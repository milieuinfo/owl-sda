package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link OutputReaderHandler}. */
public class OutputReaderHandlerTest {

  private OutputReaderHandler handler;
  private Path tempFile;
  private Config config;

  @Before
  public void setUp() throws Exception {
    tempFile = Files.createTempFile("test-output-reader", ".ttl");
    config = new Config();
    config.setOutputPath(tempFile.toString());
    handler = new OutputReaderHandler(config);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_FullMode_ReturnsEntireFileContent() throws Exception {
    String content = "@prefix ex: <http://example.org/> .\nex:Person1 a ex:Person .";
    Files.writeString(tempFile, content);

    Map<String, Object> response = (Map<String, Object>) handler.handle(Map.of()).join();

    assertEquals(content, response.get("content"));
    assertEquals(content.length(), response.get("length"));
    assertEquals("full", response.get("mode"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_LinesMode_ReturnsRequestedLineRange() throws Exception {
    Files.writeString(tempFile, "Line 1\nLine 2\nLine 3\nLine 4\n");

    Map<String, Object> response =
        (Map<String, Object>)
            handler.handle(Map.of("mode", "lines", "start_line", 1, "end_line", 3)).join();

    assertEquals("lines", response.get("mode"));
    String content = (String) response.get("content");
    assertTrue(content.contains("Line 2"));
    assertTrue(content.contains("Line 3"));
    assertTrue(!content.contains("Line 1"));
    assertTrue(!content.contains("Line 4"));
    assertEquals(4, response.get("total_lines"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_ChunkMode_ReturnsRequestedCharacterRange() throws Exception {
    Files.writeString(tempFile, "0123456789");

    Map<String, Object> response =
        (Map<String, Object>)
            handler.handle(Map.of("mode", "chunk", "start", 2, "length", 4)).join();

    assertEquals("2345", response.get("content"));
    assertEquals("chunk", response.get("mode"));
    assertEquals(2, response.get("chunk_start"));
    assertEquals(6, response.get("chunk_end"));
    assertEquals(true, response.get("has_more"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_ChunkMode_StartBeyondFileLength_ReturnsError() throws Exception {
    Files.writeString(tempFile, "short");

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("mode", "chunk", "start", 100)).join();

    assertTrue(response.containsKey("error"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_MissingOutputPath_ReturnsError() {
    Config emptyConfig = new Config();
    emptyConfig.setOutputPath(null);
    OutputReaderHandler invalidHandler = new OutputReaderHandler(emptyConfig);

    Map<String, Object> response = (Map<String, Object>) invalidHandler.handle(Map.of()).join();

    assertTrue(response.containsKey("error"));
    assertTrue(response.get("error").toString().contains("not configured"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_NonExistentFile_ReturnsError() throws Exception {
    Files.deleteIfExists(tempFile);

    Map<String, Object> response = (Map<String, Object>) handler.handle(Map.of()).join();

    assertTrue(response.containsKey("error"));
    assertTrue(response.get("error").toString().contains("does not exist"));
  }
}
