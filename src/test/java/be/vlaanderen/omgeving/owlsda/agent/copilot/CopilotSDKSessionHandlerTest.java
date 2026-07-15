package be.vlaanderen.omgeving.owlsda.agent.copilot;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.handler.SessionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.sdk.json.ToolInvocation;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Tests {@link CopilotSDKSessionHandler}'s adaptation of the Copilot SDK's {@link ToolInvocation}
 * shape onto {@link SessionHandler}. {@link ToolInvocation} is a plain SDK POJO with no external
 * side effects, so it can be constructed directly without a live Copilot connection.
 *
 * <p>Note: this uses a hand-written {@link SessionHandler} stub rather than Mockito, because
 * mockito-core's inline mock maker (ByteBuddy) does not yet support Java 25 in this environment.
 */
public class CopilotSDKSessionHandlerTest {

  @Test
  public void invoke_DelegatesToSessionHandlerWithParsedArguments() {
    CompletableFuture<Object> expected = CompletableFuture.completedFuture(Map.of("status", "ok"));
    AtomicReference<Map<String, Object>> receivedArguments = new AtomicReference<>();
    StubSessionHandler stub =
        new StubSessionHandler(
            "shacl_validator",
            arguments -> {
              receivedArguments.set(arguments);
              return expected;
            });

    ToolInvocation invocation =
        new ToolInvocation()
            .setToolName("shacl_validator")
            .setArguments(new ObjectMapper().valueToTree(Map.of("source", "data")));

    CopilotSDKSessionHandler handler = new CopilotSDKSessionHandler(stub);

    CompletableFuture<Object> result = handler.invoke(invocation);

    assertSame(expected, result);
    assertTrue(Map.of("source", "data").equals(receivedArguments.get()));
  }

  @Test
  public void invoke_WithNoArguments_PassesNullArgumentsThrough() {
    CompletableFuture<Object> expected = CompletableFuture.completedFuture(Map.of());
    AtomicReference<Map<String, Object>> receivedArguments = new AtomicReference<>();
    receivedArguments.set(Map.of("sentinel", true)); // prove it gets overwritten with null
    StubSessionHandler stub =
        new StubSessionHandler(
            "noop",
            arguments -> {
              receivedArguments.set(arguments);
              return expected;
            });

    ToolInvocation invocation = new ToolInvocation().setToolName("noop");

    CopilotSDKSessionHandler handler = new CopilotSDKSessionHandler(stub);

    CompletableFuture<Object> result = handler.invoke(invocation);

    assertSame(expected, result);
    assertTrue(receivedArguments.get() == null);
  }

  /** Minimal {@link SessionHandler} stub for observing delegation without a mocking framework. */
  private record StubSessionHandler(
      String name,
      java.util.function.Function<Map<String, Object>, CompletableFuture<Object>> onHandle)
      implements SessionHandler {

    @Override
    public Map<String, Object> getArguments() {
      return Map.of();
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getDescription() {
      return "stub";
    }

    @Override
    public CompletableFuture<Object> handle(Map<String, Object> arguments) {
      return onHandle.apply(arguments);
    }
  }
}
