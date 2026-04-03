package be.vlaanderen.omgeving.owlsda.agent.context;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Getter
@Setter
public class Context {
  private static final Logger logger = LoggerFactory.getLogger(Context.class);

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
   * Sets the content directly.
   *
   * @param content The content to set.
   */
  public void setContent(String content) {
    this.content = content;
    this.contentHash = content != null ? content.hashCode() : 0;
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
   * Retrieves the content of the context by reading it from the file path if content is not set.
   *
   * @return The content as a String, or null if neither content nor filePath is set.
   */
  public String getContent() {
    if (content != null) {
      return content;
    }
    if (filePath == null) {
      return null;
    }
    try {
      return Files.readString(Path.of(filePath));
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Context other)) {
      return false;
    }
    return name != null && name.equals(other.name);
  }

  @Override
  public int hashCode() {
    return name != null ? name.hashCode() : 0;
  }
}
