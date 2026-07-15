package be.vlaanderen.omgeving.owlsda.agent.context;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
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

  public Context() {}

  public Context(Context context) {
    this.type = context.getType();
    this.name = context.getName();
    this.filePath = context.getFilePath();
    // Preserve already loaded content only; do not force file/PDF reads while cloning contexts.
    this.content = context.content;
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

  /** Retrieves context content, lazily loading from disk only once. */
  public String getContent() {
    String currentContent = content;
    if (currentContent != null) {
      return currentContent;
    }
    if (filePath == null) {
      return null;
    }

    synchronized (this) {
      if (content != null) {
        return content;
      }
      try {
        String loadedContent = ContextContentLoader.load(filePath, type);
        setContent(loadedContent);
        return loadedContent;
      } catch (IOException e) {
        logger.error("Failed to read content from file path: {}", filePath, e);
        return null;
      }
    }
  }

  public void setType(String type) {
    if (!Objects.equals(this.type, type)) {
      this.content = null;
      this.contentHash = 0;
    }
    this.type = type;
  }

  public void setFilePath(String filePath) {
    this.content = null;
    this.contentHash = 0;
    this.filePath = filePath;
  }

  /**
   * Sets the file path based on the provided File object.
   *
   * @param file The File object whose absolute path will be set as the filePath.
   */
  public void setFile(File file) {
    setFilePath(file.getAbsolutePath());
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
