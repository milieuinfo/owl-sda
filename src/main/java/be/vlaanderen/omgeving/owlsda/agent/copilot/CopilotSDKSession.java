package be.vlaanderen.omgeving.owlsda.agent.copilot;

import be.vlaanderen.omgeving.owlsda.agent.SessionConfig;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import com.github.copilot.sdk.CopilotSession;
import com.github.copilot.sdk.events.AssistantMessageEvent;
import com.github.copilot.sdk.events.AssistantMessageEvent.AssistantMessageData.ToolRequest;
import com.github.copilot.sdk.events.SessionIdleEvent;
import com.github.copilot.sdk.events.SessionUsageInfoEvent;
import com.github.copilot.sdk.events.SessionUsageInfoEvent.SessionUsageInfoData;
import com.github.copilot.sdk.json.MessageOptions;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CopilotSDKSession implements Session {
  private final Logger logger = LoggerFactory.getLogger(CopilotSDKSession.class);

  private final Set<CopilotSDKContext> contexts = ConcurrentHashMap.newKeySet();
  private final CopilotSession session;
  private final SessionConfig config;

  private final java.util.List<SessionMessageLogEntry> messageLog = new CopyOnWriteArrayList<>();
  private final AtomicLong totalTokensUsed = new AtomicLong(0L);

  protected CopilotSDKSession(SessionConfig config, CopilotSession session) {
    this.session = session;
    this.config = config;
    registerSessionEvents();
  }

  private void registerSessionEvents() {
    session.on(AssistantMessageEvent.class, message -> {
      String content = message.getData().content();
      String reasoning = message.getData().reasoningText();
      List<ToolRequest> requests = message.getData().toolRequests();

      if (content != null && !content.isEmpty()) {
        logger.debug("Received message from assistant: {}", message.getData().content());
      } else {
        logger.debug("Received tool requests from assistant: {}", requests);
        if (reasoning != null) {
          logger.debug("Received reasoning: {}", reasoning);
        }
      }

      // Log tool invocations
      if (requests != null && !requests.isEmpty()) {
        for (ToolRequest toolRequest : requests) {
          addMessageLogEntry("TOOL_INVOCATION", null, toolRequest.toString());
          logger.debug("Tool invocation: {}", toolRequest.toString());
        }
      }
    });
    session.on(SessionUsageInfoEvent.class, usage -> {
      SessionUsageInfoData data = usage.getData();
      long currentTokens = Math.round(data.currentTokens());
      totalTokensUsed.accumulateAndGet(currentTokens, Math::max);
      logger.debug("Session usage info - Tokens used: {}", currentTokens);
    });
    session.on(SessionIdleEvent.class, event -> logger.debug("Session is now idle"));
    session.setEventErrorHandler(
        (_, e) -> logger.error("Error occurred while processing event", e));
  }

  @Override
  public void addContext(Context context) {
    synchronized (contexts) {
      contexts.removeIf(context::equals);
      contexts.add(new CopilotSDKContext(context));
    }
  }

  @Override
  public boolean addContextIfChanged(Context context) {
    synchronized (contexts) {
      // Find existing context with same name
      CopilotSDKContext existingContext = contexts.stream()
          .filter(context::equals)
          .findFirst()
          .orElse(null);

      // If context doesn't exist, add it
      if (existingContext == null) {
        contexts.add(new CopilotSDKContext(context));
        logger.debug("Added new context: {}", context.getName());
        return true;
      }

      // Check if content has changed
      String newContent = context.getContent();
      String existingContent = existingContext.getContent();

      if (newContent == null && existingContent == null) {
        return false; // No change
      }

      if (newContent == null || !newContent.equals(existingContent)) {
        // Content changed, replace it
        contexts.removeIf(context::equals);
        contexts.add(new CopilotSDKContext(context));
        logger.debug("Updated context (content changed): {}", context.getName());
        return true;
      }

      logger.debug("Context unchanged (same content): {}", context.getName());
      return false; // No change
    }
  }

  @Override
  public List<Context> getContext() {
    synchronized (contexts) {
      return contexts.stream().map(c -> (Context) c).toList();
    }
  }

  @Override
  public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> contexts) {
    CompletableFuture<ResponseMessage> future = new CompletableFuture<>();
    MessageOptions options = new MessageOptions();
    // Context is no longer added to the prompt to avoid issues with large context
    // Instead, handlers like ContextReaderHandler can be used to retrieve context
    options.setPrompt(input.getMessage());

    addMessageLogEntry("OUTBOUND", null, input.getMessage());
    logger.info("Sending prompt to Copilot SDK Session: {}", input.getMessage());
    session.sendAndWait(options, config.getTimeoutMs()).thenApply(response -> {
      ResponseMessage responseMessage = new ResponseMessage(response.getData().messageId());
      responseMessage.setMessage(response.getData().content());
      addMessageLogEntry("INBOUND", response.getData().messageId(), response.getData().content());
      future.complete(responseMessage);
      logger.debug("Received response for message ID {}: {}", response.getData().messageId(), response.getData().content());
      return null;
    }).exceptionally(ex -> {
      addMessageLogEntry("ERROR", null, ex.getMessage());
      future.completeExceptionally(ex);
      return null;
    });
    return future;
  }

  @Override
  public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
    // Create a thread-safe snapshot of contexts
    List<Context> contextSnapshot;
    synchronized (contexts) {
      contextSnapshot = List.copyOf(contexts);
    }
    return prompt(input, contextSnapshot);
  }

  @Override
  public java.util.List<SessionMessageLogEntry> getMessageLog() {
    return List.copyOf(messageLog);
  }

  private void addMessageLogEntry(String direction, String messageId, String content) {
    messageLog.add(new SessionMessageLogEntry(
        Instant.now().toString(),
        direction,
        messageId,
        content == null ? "" : content
    ));
  }

  @Override
  public void close() {
    session.close();
  }

  @Override
  public long getTotalTokensUsed() {
    return totalTokensUsed.get();
  }
}
