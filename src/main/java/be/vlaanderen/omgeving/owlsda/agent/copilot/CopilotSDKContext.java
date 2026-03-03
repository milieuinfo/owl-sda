package be.vlaanderen.omgeving.owlsda.agent.copilot;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import com.github.copilot.sdk.json.Attachment;
import java.io.File;
import lombok.Getter;

@Getter
public class CopilotSDKContext extends Context {
  private final Attachment attachment;

  protected CopilotSDKContext(Context context) {
    super(context);
    attachment = new Attachment(
      "file",
      context.getFilePath(),
      context.getName()
    );
    // Verify that the file exists
    if (context.getFilePath() == null) {
      throw new IllegalArgumentException("Context file path cannot be null");
    } else {
      File file = new File(context.getFilePath());
      if (!file.exists()) {
        throw new IllegalArgumentException("Context file does not exist: " + context.getFilePath());
      }
    }

  }

  public boolean equals(Object other) {
    return super.equals(other);
  }

  public int hashCode() {
    return super.hashCode();
  }
}
