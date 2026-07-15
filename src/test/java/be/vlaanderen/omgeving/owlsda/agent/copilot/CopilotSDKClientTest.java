package be.vlaanderen.omgeving.owlsda.agent.copilot;

import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import org.objenesis.Objenesis;
import org.objenesis.ObjenesisStd;

/**
 * Regression tests for {@link CopilotSDKClient#close()}.
 *
 * <p>{@link CopilotSDKClient}'s constructor spins up a real {@code
 * com.github.copilot.sdk.CopilotClient} and blocks on {@code client.start().get()}, which talks to
 * an external Copilot CLI process. That makes constructing a real instance impractical in this test
 * environment: without a live Copilot CLI it would either hang or fail non-deterministically, and
 * even a "successful" failure path swallows the exception (the constructor catches {@code
 * Exception} and only logs it), so there is no reliable way to assert on construction outcome here.
 *
 * <p>Instead, these tests use Objenesis (already on the test classpath transitively via
 * mockito-core) to allocate a {@link CopilotSDKClient} instance without running its constructor,
 * then use reflection to drive the private {@code client} and {@code sessions} fields directly.
 * This lets us exercise the actual regression under test: the constructor used to close its own
 * {@code CopilotClient} via try-with-resources immediately after construction, leaving the client
 * unusable for the rest of its lifecycle. As part of that fix, {@link CopilotSDKClient#close()}
 * itself now null-checks the client field so it can be called safely even when construction did not
 * fully populate it (e.g. if {@code new CopilotClient()} or {@code start()} failed).
 */
public class CopilotSDKClientTest {

  private static final Objenesis OBJENESIS = new ObjenesisStd();

  @Test
  public void close_WithNullClientField_DoesNotThrow() throws Exception {
    CopilotSDKClient client = OBJENESIS.newInstance(CopilotSDKClient.class);
    setSessions(client, new ArrayList<>());
    setClientField(client, null);

    client.close(); // Must not throw NullPointerException.
  }

  @Test
  public void close_CalledTwice_IsIdempotent() throws Exception {
    CopilotSDKClient client = OBJENESIS.newInstance(CopilotSDKClient.class);
    setSessions(client, new ArrayList<>());
    setClientField(client, null);

    client.close();
    client.close(); // Second call must also be safe.
  }

  @Test
  public void close_ClosesAllTrackedSessions() throws Exception {
    CopilotSDKClient client = OBJENESIS.newInstance(CopilotSDKClient.class);
    RecordingSession session = new RecordingSession();
    List<Session> sessions = new ArrayList<>();
    sessions.add(session);
    setSessions(client, sessions);
    setClientField(client, null);

    client.close();

    assertTrue("close() should have closed all tracked sessions", session.closed);
  }

  private static void setClientField(CopilotSDKClient client, Object value) throws Exception {
    Field field = CopilotSDKClient.class.getDeclaredField("client");
    field.setAccessible(true);
    field.set(client, value);
  }

  private static void setSessions(CopilotSDKClient client, List<Session> sessions)
      throws Exception {
    Field field = CopilotSDKClient.class.getDeclaredField("sessions");
    field.setAccessible(true);
    field.set(client, sessions);
  }

  /** Minimal {@link Session} stub used only to observe whether close() was invoked. */
  private static final class RecordingSession implements Session {
    private boolean closed = false;

    @Override
    public void addContext(Context context) {
      // no-op
    }

    @Override
    public boolean addContextIfChanged(Context context) {
      return false;
    }

    @Override
    public List<Context> getContext() {
      return List.of();
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> contexts) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
