package be.vlaanderen.omgeving.owlsda.agent.context;

import java.io.File;
import java.nio.file.Files;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Getter
@Setter
public class Context {
  private final Logger logger = LoggerFactory.getLogger(this.getClass());

  private String name;
  private String type;
  private String filePath;
  private String content;
  private int contentHash = 0; // Track content changes

  public Context() {
  }

  public Context(Context context) {
    this.type = context.getType();
    this.name = context.getName();
    this.filePath = context.getFilePath();
    this.content = context.getContent();
    this.contentHash = context.getContentHash();
  }

  /**
   * Sets the content directly without creating a file.
   *
   * @param content The content to be saved.
   */
  public void setContent(String content) {
    this.content = content;
    this.contentHash = content != null ? content.hashCode() : 0;
    // Save to a temporary file and set the file path
    try {
      this.content = content;
      this.contentHash = content != null ? content.hashCode() : 0;
      File tempFile = File.createTempFile("context", ".txt");
      Files.write(tempFile.toPath(), content.getBytes());
      this.filePath = tempFile.getAbsolutePath();
    } catch (Exception e) {
      logger.error("Failed to set content for context", e);
    }
  }

  /**
   * Checks if the content has changed compared to the provided content.
   *
   * @param newContent The new content to compare with.
   * @return true if content has changed, false otherwise.
   */
  public boolean hasContentChanged(String newContent) {
    int newHash = newContent != null ? newContent.hashCode() : 0;
    return this.contentHash != newHash;
  }

  /**
   * Retrieves the content of the context by reading it from the file path.
   *
   * @return The content as a String, or null if the file path is not set or an error occurs.
   */
  public String getContent() {
    if (content != null) {
      return content;
    }
    if (filePath == null) {
      return null;
    }
    try {
      return new String(Files.readAllBytes(new File(filePath).toPath()));
    } catch (Exception e) {
      logger.error("Failed to read content from file path: {}", filePath, e);
      return null;
    }
  }

  /**
   * Sets the file path based on the provided File object.
   *
   * @param file The File object whose absolute path will be set as the filePath.
   */
  public void setFile(File file) {
    this.filePath = file.getAbsolutePath();
  }

  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return name.equals(((Context) o).name);
  }

  public int hashCode() {
    return name.hashCode();
  }
}
