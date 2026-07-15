package be.vlaanderen.omgeving.owlsda.agent.copilot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.exception.LanguageModelException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * Tests for {@link CopilotSDKContext}'s {@code resolveFilePath} logic: reusing an existing file
 * path, materializing in-memory content to a temp file, and rejecting contexts with neither. The
 * SDK Attachment type it wraps is a plain record with no external side effects, so the full
 * constructor can be exercised directly without a live Copilot connection.
 */
public class CopilotSDKContextTest {

  @Test
  public void construct_WithExistingFilePath_ReusesThatPathDirectly() throws Exception {
    Path tempFile = Files.createTempFile("copilot-sdk-context-existing", ".ttl");
    Files.writeString(tempFile, "@prefix ex: <http://example.org/> .");

    Context context = new Context();
    context.setName("Ontology");
    context.setFilePath(tempFile.toString());

    CopilotSDKContext sdkContext = new CopilotSDKContext(context);

    assertEquals(tempFile.toString(), sdkContext.getAttachment().path());
    assertEquals("file", sdkContext.getAttachment().type());
    assertEquals("Ontology", sdkContext.getAttachment().displayName());
  }

  @Test
  public void construct_WithInMemoryContentAndNoExistingFile_WritesTempFileAndCachesPath()
      throws Exception {
    Context context = new Context();
    context.setName("Instructions");
    context.setContent("Some in-memory instructions");

    CopilotSDKContext sdkContext = new CopilotSDKContext(context);

    String resolvedPath = sdkContext.getAttachment().path();
    assertNotNull(resolvedPath);
    assertTrue(Files.exists(Path.of(resolvedPath)));
    assertEquals("Some in-memory instructions", Files.readString(Path.of(resolvedPath)));
    // The temp file path is cached back onto the original context so subsequent reads reuse it.
    assertEquals(resolvedPath, context.getFilePath());
  }

  @Test
  public void construct_WithNonExistentFilePathButContent_FallsBackToWritingTempFile()
      throws Exception {
    Context context = new Context();
    context.setName("Fallback");
    context.setFilePath("/no/such/path/on/disk.ttl");
    context.setContent("fallback content");

    CopilotSDKContext sdkContext = new CopilotSDKContext(context);

    String resolvedPath = sdkContext.getAttachment().path();
    assertNotNull(resolvedPath);
    assertTrue(Files.exists(Path.of(resolvedPath)));
    assertEquals("fallback content", Files.readString(Path.of(resolvedPath)));
  }

  @Test
  public void construct_WithNeitherFilePathNorContent_ThrowsLanguageModelException() {
    Context context = new Context();
    context.setName("Empty");

    try {
      new CopilotSDKContext(context);
      fail("Expected LanguageModelException for context with no path and no content");
    } catch (LanguageModelException e) {
      assertTrue(e.getMessage().contains("Empty"));
      assertTrue(e.getMessage().contains("neither a valid file path nor in-memory content"));
    }
  }
}
