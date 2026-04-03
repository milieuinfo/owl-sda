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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CopilotSDKSession implements Session {
  private static final Logger logger = LoggerFactory.getLogger(CopilotSDKSession.class);

  private final Set<CopilotSDKContext> contexts = ConcurrentHashMap.newKeySet();
  private final CopilotSession session;
  private final SessionConfig config;

  private final List<SessionMessageLogEntry> messageLog = new CopyOnWriteArrayList<>();
  private final AtomicLong totalTokensUsed = new AtomicLong(0L);
  private final AtomicLong lastAssistantActivityMs = new AtomicLong(System.currentTimeMillis());
  private final ScheduledThreadPoolExecutor inactivityMonitorExecutor;

  protected CopilotSDKSession(SessionConfig config, CopilotSession session) {
    this.session = session;
    this.config = config;
    this.inactivityMonitorExecutor = createInactivityMonitorExecutor();
    registerSessionEvents();
  }

  private ScheduledThreadPoolExecutor createInactivityMonitorExecutor() {
    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "copilot-session-inactivity-monitor");
      thread.setDaemon(true);
      return thread;
    };
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, factory);
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  private void markAssistantActivity() {
    lastAssistantActivityMs.set(System.currentTimeMillis());
  }

  private long resolveMonitorIntervalMs(long betweenMessageTimeoutMs) {
    long candidate = betweenMessageTimeoutMs / 4L;
    if (candidate < 200L) {
      return 200L;
    }
    return Math.min(candidate, 1000L);
  }

  private void registerSessionEvents() {
    session.on(AssistantMessageEvent.class, message -> {
      markAssistantActivity();
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
          String toolInfo = toolRequest.toString();
          addMessageLogEntry("TOOL_INVOCATION", null, toolInfo);
          logger.debug("Tool invocation: {}", toolInfo);
        }
      }
    });
    session.on(SessionUsageInfoEvent.class, usage -> {
      markAssistantActivity();
      SessionUsageInfoData data = usage.getData();
      long currentTokens = Math.round(data.currentTokens());
      totalTokensUsed.accumulateAndGet(currentTokens, Math::max);
      logger.debug("Session usage info - Tokens used: {}", currentTokens);
    });
    session.on(SessionIdleEvent.class, ignored -> {
      markAssistantActivity();
      logger.debug("Session is now idle");
    });
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
    markAssistantActivity();

    long betweenMessageTimeoutMs = Math.max(0, config.getBetweenMessageTimeoutMs());
    ScheduledFuture<?> inactivityMonitor = null;
    if (betweenMessageTimeoutMs > 0) {
      long monitorIntervalMs = resolveMonitorIntervalMs(betweenMessageTimeoutMs);
      inactivityMonitor = inactivityMonitorExecutor.scheduleAtFixedRate(() -> {
        if (future.isDone()) {
          return;
        }

        long idleMs = System.currentTimeMillis() - lastAssistantActivityMs.get();
        if (idleMs < betweenMessageTimeoutMs) {
          return;
        }

        TimeoutException timeout = new TimeoutException(
            "No assistant message/tool request received for " + idleMs + "ms"
                + " (configured between-message-timeout-ms=" + betweenMessageTimeoutMs + ")"
        );

        if (future.completeExceptionally(timeout)) {
          addMessageLogEntry("ERROR", null, timeout.getMessage());
          logger.warn("Prompt timed out due to inactivity: {}", timeout.getMessage());
        }
      }, monitorIntervalMs, monitorIntervalMs, TimeUnit.MILLISECONDS);
    }

    final ScheduledFuture<?> finalInactivityMonitor = inactivityMonitor;

    // Log instruction key in info level, full message only in debug level
    if (input.getInstructionKey() != null && !input.getInstructionKey().isEmpty()) {
      logger.info("Sending prompt to Copilot SDK Session: {}", input.getInstructionKey());
      logger.debug("Full message content: {}", input.getMessage());
    } else {
      logger.info("Sending prompt to Copilot SDK Session: {}", input.getMessage());
    }

    session.sendAndWait(options, config.getTimeoutMs()).thenApply(response -> {
      if (future.isDone()) {
        return null;
      }
      markAssistantActivity();
      ResponseMessage responseMessage = new ResponseMessage(response.getData().messageId());
      responseMessage.setMessage(response.getData().content());
      addMessageLogEntry("INBOUND", response.getData().messageId(), response.getData().content());
      future.complete(responseMessage);
      logger.debug("Received response for message ID {}: {}", response.getData().messageId(), response.getData().content());
      return null;
    }).exceptionally(ex -> {
      if (!future.isDone()) {
        addMessageLogEntry("ERROR", null, ex.getMessage());
        future.completeExceptionally(ex);
      }
      return null;
    });

    future.whenComplete((ignored1, ignored2) -> {
      if (finalInactivityMonitor != null) {
        finalInactivityMonitor.cancel(true);
      }
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
  public List<SessionMessageLogEntry> getMessageLog() {
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
    inactivityMonitorExecutor.shutdownNow();
    session.close();
  }

  @Override
  public long getTotalTokensUsed() {
    return totalTokensUsed.get();
  }

  /**
   * Returns the timestamp (ms) of the last assistant activity (message/tool/event).
   * Used for monitoring session progress and detecting stalls.
   */
  public long getLastAssistantActivityMs() {
    return lastAssistantActivityMs.get();
  }

  /**
   * Returns whether this session has been idle for longer than the specified duration.
   * Useful for detecting stuck sessions during delegation execution.
   *
   * @param idleThresholdMs idle time threshold in milliseconds
   * @return true if session has no assistant activity for >= idleThresholdMs
   */
  public boolean isIdleSince(long idleThresholdMs) {
    long lastActivity = lastAssistantActivityMs.get();
    long elapsedMs = System.currentTimeMillis() - lastActivity;
    return elapsedMs >= idleThresholdMs;
  }
}
