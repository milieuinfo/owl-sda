package be.vlaanderen.omgeving.owlsda.agent.ollama;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionConfig;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.config.Config;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link OllamaClient}: session creation/tracking and close() fan-out. */
public class OllamaClientTest {

  private OllamaClient client;

  @Before
  public void setUp() {
    client = new OllamaClient(new Config());
  }

  @Test
  public void getName_ReturnsOllama() {
    assertEquals("ollama", client.getName());
  }

  @Test
  public void createSession_ReturnsWorkingOllamaSession() throws Exception {
    SessionConfig config = SessionConfig.builder().model("llama3").timeoutMs(5000).build();

    Session session = client.createSession(config);

    assertNotNull(session);
    assertTrue(session instanceof OllamaSession);
  }

  @Test
  public void createSession_TracksCreatedSessionsInGetSessions() throws Exception {
    SessionConfig config = SessionConfig.builder().model("llama3").timeoutMs(5000).build();

    Session first = client.createSession(config);
    Session second = client.createSession(config);

    assertEquals(List.of(first, second), client.getSessions());
  }

  @Test
  public void close_ClosesAllTrackedSessions() throws Exception {
    SessionConfig config = SessionConfig.builder().model("llama3").timeoutMs(5000).build();
    client.createSession(config);

    RecordingSession recording = new RecordingSession();
    // Substitute a session we can observe close() on; getSessions() returns the live backing list.
    client.getSessions().set(0, recording);

    client.close();

    assertTrue("close() should have closed the tracked session", recording.closed);
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
