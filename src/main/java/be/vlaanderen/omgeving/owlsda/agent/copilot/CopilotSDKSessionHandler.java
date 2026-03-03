package be.vlaanderen.omgeving.owlsda.agent.copilot;

import be.vlaanderen.omgeving.owlsda.agent.handler.SessionHandler;
import com.github.copilot.sdk.json.ToolHandler;
import com.github.copilot.sdk.json.ToolInvocation;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record CopilotSDKSessionHandler(SessionHandler sessionHandler) implements ToolHandler {
  private static final Logger logger = LoggerFactory.getLogger(CopilotSDKSessionHandler.class);

  @Override
  public CompletableFuture<Object> invoke(ToolInvocation toolInvocation) {
    logger.debug("Invoking tool: {}", sessionHandler.getName());
    return sessionHandler.handle(toolInvocation.getArguments());
  }
}
