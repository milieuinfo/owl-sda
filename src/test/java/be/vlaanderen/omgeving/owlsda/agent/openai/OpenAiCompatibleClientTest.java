package be.vlaanderen.omgeving.owlsda.agent.openai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

/** Tests for {@link OpenAiCompatibleClient}: session creation/tracking and close() fan-out. */
public class OpenAiCompatibleClientTest {

  private OpenAiCompatibleClient client;

  @Before
  public void setUp() {
    Config config = new Config();
    config.getClient().getOpenaiCompatible().setApiKey("test-api-key");
    client = new OpenAiCompatibleClient(config);
  }

  @Test
  public void constructor_WithoutApiKeyOrEnvVar_ThrowsIllegalStateException() {
    Config config = new Config();
    config.getClient().getOpenaiCompatible().setApiKey(null);
    // Note: this relies on OPENAI_API_KEY not being set in the test environment; if it is set,
    // this assertion would need an explicit env override, which the class does not expose.
    if (System.getenv("OPENAI_API_KEY") != null && !System.getenv("OPENAI_API_KEY").isBlank()) {
      return;
    }

    try {
      new OpenAiCompatibleClient(config);
      fail("Expected IllegalStateException when no API key is configured");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("No API key configured"));
    }
  }

  @Test
  public void getName_ReturnsOpenAiCompatible() {
    assertEquals("openai-compatible", client.getName());
  }

  @Test
  public void createSession_ReturnsWorkingOpenAiCompatibleSession() throws Exception {
    SessionConfig config = SessionConfig.builder().model("gpt-4o-mini").timeoutMs(5000).build();

    Session session = client.createSession(config);

    assertNotNull(session);
    assertTrue(session instanceof OpenAiCompatibleSession);
  }

  @Test
  public void createSession_TracksCreatedSessionsInGetSessions() throws Exception {
    SessionConfig config = SessionConfig.builder().model("gpt-4o-mini").timeoutMs(5000).build();

    Session first = client.createSession(config);
    Session second = client.createSession(config);

    assertEquals(List.of(first, second), client.getSessions());
  }

  @Test
  public void close_ClosesAllTrackedSessions() throws Exception {
    SessionConfig config = SessionConfig.builder().model("gpt-4o-mini").timeoutMs(5000).build();
    client.createSession(config);

    RecordingSession recording = new RecordingSession();
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
