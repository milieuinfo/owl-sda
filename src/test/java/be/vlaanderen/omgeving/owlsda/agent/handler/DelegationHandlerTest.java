package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.handler.DelegationHandler.PublicationResult;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class DelegationHandlerTest {

  @Test
  public void getName_ReturnsDelegateTasks() {
    DelegationHandler handler = new DelegationHandler((name, target, instructions) -> null);
    assertEquals("delegate_tasks", handler.getName());
    assertEquals(DelegationHandler.NAME, handler.getName());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void getArguments_DeclaresExpectedSchema() {
    DelegationHandler handler = new DelegationHandler((name, target, instructions) -> null);

    Map<String, Object> arguments = handler.getArguments();

    assertEquals("object", arguments.get("type"));
    Map<String, Object> properties = (Map<String, Object>) arguments.get("properties");
    assertTrue(properties.containsKey("context_name"));
    assertTrue(properties.containsKey("target_agent"));
    assertTrue(properties.containsKey("instructions"));
    assertEquals(List.of("target_agent", "instructions"), arguments.get("required"));
  }

  @Test
  public void getDescription_IsNonBlank() {
    DelegationHandler handler = new DelegationHandler((name, target, instructions) -> null);
    assertTrue(handler.getDescription() != null && !handler.getDescription().isBlank());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_SuccessfulPublication_InvokesPublisherAndReturnsSuccess() {
    AtomicReference<String[]> published = new AtomicReference<>();
    DelegationHandler handler =
        new DelegationHandler(
            (name, target, instructions) -> {
              published.set(new String[] {name, target, instructions});
              return PublicationResult.success("POOL-0");
            });

    Map<String, Object> response =
        (Map<String, Object>)
            handler
                .handle(
                    Map.of(
                        "target_agent", "POOL-0",
                        "instructions", "Do the thing"))
                .join();

    assertEquals("success", response.get("status"));
    assertEquals(DelegationHandler.DELEGATION_CONTEXT_NAME, response.get("context_name"));
    assertEquals("POOL-0", response.get("target_agent"));
    assertEquals("Do the thing".length(), response.get("characters"));

    assertEquals(DelegationHandler.DELEGATION_CONTEXT_NAME, published.get()[0]);
    assertEquals("POOL-0", published.get()[1]);
    assertEquals("Do the thing", published.get()[2]);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_CustomContextName_IsPassedToPublisher() {
    AtomicReference<String> publishedName = new AtomicReference<>();
    DelegationHandler handler =
        new DelegationHandler(
            (name, target, instructions) -> {
              publishedName.set(name);
              return PublicationResult.success(target);
            });

    Map<String, Object> response =
        (Map<String, Object>)
            handler
                .handle(
                    Map.of(
                        "context_name", "Custom Context",
                        "target_agent", "POOL-1",
                        "instructions", "Instructions"))
                .join();

    assertEquals("success", response.get("status"));
    assertEquals("Custom Context", publishedName.get());
    assertEquals("Custom Context", response.get("context_name"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_PublisherReportsFailure_ReturnsErrorWithMessage() {
    DelegationHandler handler =
        new DelegationHandler(
            (name, target, instructions) -> PublicationResult.error("Unknown target agent"));

    Map<String, Object> response =
        (Map<String, Object>)
            handler
                .handle(
                    Map.of(
                        "target_agent", "POOL-99",
                        "instructions", "Instructions"))
                .join();

    assertEquals("error", response.get("status"));
    assertEquals("POOL-99", response.get("target_agent"));
    assertEquals("Unknown target agent", response.get("message"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_MissingTargetAgent_ReturnsErrorWithoutCallingPublisher() {
    AtomicReference<Boolean> publisherCalled = new AtomicReference<>(false);
    DelegationHandler handler =
        new DelegationHandler(
            (name, target, instructions) -> {
              publisherCalled.set(true);
              return PublicationResult.success(target);
            });

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("instructions", "Instructions")).join();

    assertEquals("error", response.get("status"));
    assertEquals("target_agent is required", response.get("message"));
    assertEquals(false, publisherCalled.get());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_MissingInstructions_ReturnsErrorWithoutCallingPublisher() {
    AtomicReference<Boolean> publisherCalled = new AtomicReference<>(false);
    DelegationHandler handler =
        new DelegationHandler(
            (name, target, instructions) -> {
              publisherCalled.set(true);
              return PublicationResult.success(target);
            });

    Map<String, Object> response =
        (Map<String, Object>) handler.handle(Map.of("target_agent", "POOL-0")).join();

    assertEquals("error", response.get("status"));
    assertEquals("instructions is required", response.get("message"));
    assertEquals(false, publisherCalled.get());
  }
}
