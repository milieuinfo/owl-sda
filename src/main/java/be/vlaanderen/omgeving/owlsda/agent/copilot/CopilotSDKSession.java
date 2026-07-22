package be.vlaanderen.omgeving.owlsda.agent.copilot;

import be.vlaanderen.omgeving.owlsda.agent.AbstractSession;
import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import com.github.copilot.sdk.CopilotSession;
import com.github.copilot.sdk.events.AssistantMessageEvent;
import com.github.copilot.sdk.events.AssistantMessageEvent.AssistantMessageData.ToolRequest;
import com.github.copilot.sdk.events.SessionIdleEvent;
import com.github.copilot.sdk.events.SessionUsageInfoEvent;
import com.github.copilot.sdk.events.SessionUsageInfoEvent.SessionUsageInfoData;
import com.github.copilot.sdk.json.MessageOptions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CopilotSDKSession extends AbstractSession {
  private static final Logger logger = LoggerFactory.getLogger(CopilotSDKSession.class);

  /**
   * Factory that creates a fresh underlying {@link CopilotSession} with the same configuration.
   * Used by {@link #reset()} to clear the server-side message history between delegation rounds.
   */
  @FunctionalInterface
  public interface SessionFactory {
    CopilotSession create() throws ExecutionException, InterruptedException;
  }

  /** Mutable reference so reset() can swap in a freshly created CopilotSession. */
  private final AtomicReference<CopilotSession> sessionRef;

  private final be.vlaanderen.omgeving.owlsda.agent.SessionConfig config;

  /** Allows creating a replacement Copilot session on reset; null means reset is not supported. */
  private final SessionFactory sessionFactory;

  private final ScheduledThreadPoolExecutor inactivityMonitorExecutor;

  protected CopilotSDKSession(
      be.vlaanderen.omgeving.owlsda.agent.SessionConfig config, CopilotSession session) {
    this(config, session, null);
  }

  protected CopilotSDKSession(
      be.vlaanderen.omgeving.owlsda.agent.SessionConfig config,
      CopilotSession session,
      SessionFactory sessionFactory) {
    this.sessionRef = new AtomicReference<>(session);
    this.config = config;
    this.sessionFactory = sessionFactory;
    this.inactivityMonitorExecutor = createInactivityMonitorExecutor();
    registerSessionEvents(session);
  }

  private ScheduledThreadPoolExecutor createInactivityMonitorExecutor() {
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "copilot-session-inactivity-monitor");
          thread.setDaemon(true);
          return thread;
        };
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, factory);
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  private long resolveMonitorIntervalMs(long betweenMessageTimeoutMs) {
    long candidate = betweenMessageTimeoutMs / 4L;
    if (candidate < 200L) {
      return 200L;
    }
    return Math.min(candidate, 1000L);
  }

  private void registerSessionEvents(CopilotSession s) {
    s.on(
        AssistantMessageEvent.class,
        message -> {
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
    s.on(
        SessionUsageInfoEvent.class,
        usage -> {
          markAssistantActivity();
          SessionUsageInfoData data = usage.getData();
          long currentTokens = Math.round(data.currentTokens());
          long inputTokens =
              readUsageTokenValue(
                  data, "inputTokens", "promptTokens", "input_tokens", "prompt_tokens");
          long outputTokens =
              readUsageTokenValue(
                  data, "outputTokens", "completionTokens", "output_tokens", "completion_tokens");

          totalTokensUsed.accumulateAndGet(currentTokens, Math::max);
          inputTokensUsed.accumulateAndGet(inputTokens, Math::max);
          outputTokensUsed.accumulateAndGet(outputTokens, Math::max);

          logger.debug(
              "Session usage info - Tokens used total={}, input={}, output={}",
              currentTokens,
              inputTokens,
              outputTokens);
        });
    s.on(
        SessionIdleEvent.class,
        ignored -> {
          markAssistantActivity();
          logger.debug("Session is now idle");
        });
    s.setEventErrorHandler((_, e) -> logger.error("Error occurred while processing event", e));
  }

  private static long readUsageTokenValue(Object data, String... candidateMethodNames) {
    for (String methodName : candidateMethodNames) {
      Long tokenValue = invokeTokenMethod(data, methodName);
      if (tokenValue != null) {
        return tokenValue;
      }
    }
    return 0L;
  }

  private static Long invokeTokenMethod(Object data, String methodName) {
    try {
      Method method = data.getClass().getMethod(methodName);
      Object value = method.invoke(data);
      Long parsed = parseTokenValue(value);
      if (parsed != null) {
        return parsed;
      }
      logger.debug(
          "Usage token method '{}' returned non-numeric value type: {}",
          methodName,
          value == null ? "null" : value.getClass().getName());
    } catch (NoSuchMethodException ignored) {
      // Try next known method name.
    } catch (IllegalAccessException | InvocationTargetException e) {
      logger.debug("Unable to read usage token field '{}'", methodName, e);
    }
    return null;
  }

  static Long parseTokenValue(Object value) {
    if (value instanceof Number numberValue) {
      return Math.round(numberValue.doubleValue());
    }
    if (value instanceof String stringValue) {
      String trimmed = stringValue.trim();
      if (trimmed.isEmpty()) {
        return null;
      }
      try {
        return Math.round(Double.parseDouble(trimmed));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  /** Stores contexts as {@link CopilotSDKContext} so each carries its resolved SDK attachment. */
  @Override
  protected Context wrapContext(Context context) {
    return new CopilotSDKContext(context);
  }

  /** Exposes stored {@link CopilotSDKContext} instances as-is (no defensive copy). */
  @Override
  protected Context exposeContext(Context stored) {
    return stored;
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
    markPromptStarted();

    long betweenMessageTimeoutMs = Math.max(0, config.getBetweenMessageTimeoutMs());
    ScheduledFuture<?> inactivityMonitor = null;
    if (betweenMessageTimeoutMs > 0) {
      long monitorIntervalMs = resolveMonitorIntervalMs(betweenMessageTimeoutMs);
      inactivityMonitor =
          inactivityMonitorExecutor.scheduleAtFixedRate(
              () -> {
                if (future.isDone()) {
                  return;
                }

                long idleMs = System.currentTimeMillis() - getLastAssistantActivityMs();
                if (idleMs < betweenMessageTimeoutMs) {
                  return;
                }

                TimeoutException timeout =
                    new TimeoutException(
                        "No assistant message/tool request received for "
                            + idleMs
                            + "ms"
                            + " (configured between-message-timeout-ms="
                            + betweenMessageTimeoutMs
                            + ")");

                if (future.completeExceptionally(timeout)) {
                  addMessageLogEntry("ERROR", null, timeout.getMessage());
                  logger.warn("Prompt timed out due to inactivity: {}", timeout.getMessage());
                }
              },
              monitorIntervalMs,
              monitorIntervalMs,
              TimeUnit.MILLISECONDS);
    }

    final ScheduledFuture<?> finalInactivityMonitor = inactivityMonitor;

    // Log instruction key in info level, full message only in debug level
    if (input.getInstructionKey() != null && !input.getInstructionKey().isEmpty()) {
      logger.info("Sending prompt to Copilot SDK Session: {}", input.getInstructionKey());
      logger.debug("Full message content: {}", input.getMessage());
    } else {
      logger.info("Sending prompt to Copilot SDK Session: {}", input.getMessage());
    }

    sessionRef
        .get()
        .sendAndWait(options, config.getTimeoutMs())
        .thenApply(
            response -> {
              if (future.isDone()) {
                return null;
              }
              markAssistantActivity();
              ResponseMessage responseMessage = new ResponseMessage(response.getData().messageId());
              responseMessage.setMessage(response.getData().content());
              addMessageLogEntry(
                  "INBOUND", response.getData().messageId(), response.getData().content());
              future.complete(responseMessage);
              logger.debug(
                  "Received response for message ID {}: {}",
                  response.getData().messageId(),
                  response.getData().content());
              return null;
            })
        .exceptionally(
            ex -> {
              if (!future.isDone()) {
                addMessageLogEntry("ERROR", null, ex.getMessage());
                future.completeExceptionally(ex);
              }
              return null;
            });

    future.whenComplete(
        (ignored1, ignored2) -> {
          if (finalInactivityMonitor != null) {
            finalInactivityMonitor.cancel(true);
          }
          markPromptFinished();
        });
    return future;
  }

  @Override
  public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
    return prompt(input, getContext());
  }

  /**
   * Copilot's usage callback tracks {@link #inputTokensUsed} as a running max of the latest
   * reported value (see the {@code usage} handler above), not a cumulative sum like the HTTP-chat
   * providers - so it already approximates current context occupancy reasonably well.
   */
  @Override
  public long getLastPromptTokens() {
    return inputTokensUsed.get();
  }

  @Override
  public void reset() {
    if (sessionFactory == null) {
      logger.debug("Session reset requested but no SessionFactory is configured; skipping");
      return;
    }
    try {
      logger.info("Resetting session to clear server-side message history");
      CopilotSession old = sessionRef.get();
      CopilotSession fresh = sessionFactory.create();
      sessionRef.set(fresh);
      registerSessionEvents(fresh);
      // Safely close the old session after the swap so in-flight calls are not disrupted.
      try {
        old.close();
      } catch (Exception closeEx) {
        logger.warn("Error closing old session during reset: {}", closeEx.getMessage());
      }
      markAssistantActivity();
      logger.info("Session reset complete — message history cleared, contexts preserved");
    } catch (Exception e) {
      logger.error("Session reset failed: {}", e.getMessage(), e);
    }
  }

  @Override
  public void close() {
    inactivityMonitorExecutor.shutdownNow();
    sessionRef.get().close();
  }
}
