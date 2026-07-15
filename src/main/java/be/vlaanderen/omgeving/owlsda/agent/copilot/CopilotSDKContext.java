package be.vlaanderen.omgeving.owlsda.agent.copilot;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.exception.LanguageModelException;
import com.github.copilot.sdk.json.Attachment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import lombok.Getter;

@Getter
public class CopilotSDKContext extends Context {
  private final Attachment attachment;

  protected CopilotSDKContext(Context context) {
    super(context);
    String filePath = resolveFilePath(context);
    attachment = new Attachment("file", filePath, context.getName());
  }

  /**
   * Returns a valid file path for the given context. If the context already has a file path
   * pointing to an existing file, that path is returned directly. Otherwise the in-memory content
   * is written to a temp file so that the Copilot SDK can attach it.
   */
  private static String resolveFilePath(Context context) {
    String existingPath = context.getFilePath();
    if (existingPath != null && new File(existingPath).exists()) {
      return existingPath;
    }

    String content = context.getContent();
    if (content == null) {
      throw new LanguageModelException(
          "Context '"
              + context.getName()
              + "' has neither a valid file path nor in-memory content");
    }

    try {
      File tempFile = File.createTempFile("owlsda-context-", ".txt");
      tempFile.deleteOnExit();
      Files.writeString(tempFile.toPath(), content, StandardCharsets.UTF_8);
      // Keep the path on the context so subsequent calls reuse the same file.
      context.setFilePath(tempFile.getAbsolutePath());
      return tempFile.getAbsolutePath();
    } catch (IOException e) {
      throw new LanguageModelException(
          "Failed to create temp file for context '" + context.getName() + "'", e);
    }
  }

  @Override
  public boolean equals(Object other) {
    return super.equals(other);
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }
}
