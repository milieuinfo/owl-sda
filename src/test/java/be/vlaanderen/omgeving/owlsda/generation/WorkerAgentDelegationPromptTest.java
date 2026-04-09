package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.config.Config;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WorkerAgentDelegationPromptTest {

  @Test
  public void run_InDelegationMode_EmbedsLiveDelegationSnapshotIntoPrompt() {
    SessionPool sessionPool = new SessionPool(1);
    RecordingSession session = new RecordingSession();

    Context delegation = new Context();
    delegation.setName("Delegation Instructions");
    delegation.setType("text/plain");
    delegation.setContent("Target shape(s): OperatorShape\nTarget class: :Operator");
    session.addContext(delegation);

    sessionPool.addSession(session);

    AtomicBoolean success = new AtomicBoolean(false);
    WorkerAgent workerAgent = new WorkerAgent(
        sessionPool,
        null,
        0,
        1,
        0,
        1,
        1,
        "Base worker instructions",
        success,
        true
    );

    workerAgent.run();

    assertTrue(success.get());
    assertTrue(session.lastPrompt.contains("LIVE DELEGATION SNAPSHOT"));
    assertTrue(session.lastPrompt.contains("Target shape(s): OperatorShape"));
    assertTrue(session.lastPrompt.contains("Target class: :Operator"));
  }

  @Test
  public void buildWorkerInstructions_WithRichnessConfig_InjectsExpectedPolicy() throws Exception {
    Config config = new Config();
    config.getGeneration().setDataRichness("rich");

    Supervisor supervisor = new Supervisor(
        null,
        null,
        new ConcurrentWorkerBatch(config, null, null, null),
        null,
        null,
        null
    );

    Method method = Supervisor.class.getDeclaredMethod("buildWorkerInstructions", boolean.class, boolean.class);
    method.setAccessible(true);
    String instructions = (String) method.invoke(supervisor, true, false);

    assertTrue(instructions.contains("RICHNESS PROFILE: RICH"));
    assertTrue(instructions.contains("invent new predicates"));
  }

  @Test
  public void buildDelegationInstructions_DoesNotIncludeWorkerRichnessPolicy() throws Exception {
    Config config = new Config();
    config.getGeneration().setDataRichness("rich");

    SessionPool sessionPool = new SessionPool(1);
    sessionPool.addSession(new RecordingSession());

    Supervisor supervisor = new Supervisor(
        null,
        null,
        new ConcurrentWorkerBatch(config, sessionPool, null, null),
        null,
        null,
        null
    );

    Method method = Supervisor.class.getDeclaredMethod("buildDelegationInstructions", int.class,
        boolean.class, boolean.class);
    method.setAccessible(true);
    String instructions = (String) method.invoke(supervisor, 1, true, false);

    assertTrue(instructions.contains("Coordinate worker delegation for this round."));
    assertTrue(!instructions.contains("RICHNESS PROFILE:"));
  }

  @Test
  public void dataRichness_UnknownValue_FallsBackToMinimal() {
    Config config = new Config();
    config.getGeneration().setDataRichness("experimental-mode");

    assertEquals(Config.DataRichness.MINIMAL, config.getDataRichness());
  }

  private static final class RecordingSession implements Session {
    private final List<Context> contexts = new ArrayList<>();
    private String lastPrompt = "";

    @Override
    public void addContext(Context context) {
      contexts.removeIf(existing -> sameName(existing, context));
      contexts.add(context);
    }

    @Override
    public boolean addContextIfChanged(Context context) {
      Context existing = contexts.stream()
          .filter(candidate -> sameName(candidate, context))
          .findFirst()
          .orElse(null);
      if (existing != null && java.util.Objects.equals(existing.getContent(), context.getContent())) {
        return false;
      }
      addContext(context);
      return true;
    }

    @Override
    public List<Context> getContext() {
      return List.copyOf(contexts);
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> contexts) {
      this.lastPrompt = input.getMessage();
      ResponseMessage response = new ResponseMessage("test-id");
      response.setMessage("ok");
      return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      return prompt(input, getContext());
    }

    @Override
    public void close() {
    }

    private boolean sameName(Context left, Context right) {
      return java.util.Objects.equals(left.getName(), right.getName());
    }
  }
}
