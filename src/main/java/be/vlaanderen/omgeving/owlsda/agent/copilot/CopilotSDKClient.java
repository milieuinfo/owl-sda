package be.vlaanderen.omgeving.owlsda.agent.copilot;

import be.vlaanderen.omgeving.owlsda.agent.Client;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import com.github.copilot.sdk.CopilotClient;
import com.github.copilot.sdk.CopilotSession;
import com.github.copilot.sdk.SystemMessageMode;
import com.github.copilot.sdk.json.SessionConfig;
import com.github.copilot.sdk.json.SystemMessageConfig;
import com.github.copilot.sdk.json.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CopilotSDKClient implements Client {

  private final Logger logger = LoggerFactory.getLogger(CopilotSDKClient.class);

  private CopilotClient client;
  private final List<Session> sessions = new ArrayList<>();

  public CopilotSDKClient() {
    try (CopilotClient client = new CopilotClient()) {
      this.client = client;
      logger.info("Initializing Copilot SDK Client ...");
      client.start().get();
      logger.info("Copilot SDK Client initialized successfully");
    } catch (Exception e) {
      logger.error("Failed to initialize Copilot SDK Client", e);
    }
  }

  @Override
  public String getName() {
    return client.getState().name();
  }

  @Override
  public Session createSession(be.vlaanderen.omgeving.owlsda.agent.SessionConfig config)
      throws ExecutionException, InterruptedException {
    logger.info("Creating new session...");

    SessionConfig sessionConfig = new SessionConfig();
    sessionConfig.setModel(config.getModel());
    SystemMessageConfig systemMessageConfig = new SystemMessageConfig();
    systemMessageConfig.setContent(config.getSystemContext().getContent());
    systemMessageConfig.setMode(SystemMessageMode.APPEND);
    sessionConfig.setSystemMessage(systemMessageConfig);
    List<ToolDefinition> tools = config.getHandlers().stream().map(handler ->
        new ToolDefinition(
            handler.getName(),
            handler.getDescription(),
            handler.getArguments(),
            new CopilotSDKSessionHandler(handler)
        )).toList();
    sessionConfig.setTools(tools);
    sessionConfig.setAvailableTools(tools.stream().map(ToolDefinition::name).toList());

    CopilotSession session = client.createSession(sessionConfig).get();
    // Capture sessionConfig as effectively final for the factory lambda.
    CopilotSDKSession.SessionFactory factory = () -> client.createSession(sessionConfig).get();
    CopilotSDKSession copilotSDKSession = new CopilotSDKSession(config, session, factory);
    sessions.add(copilotSDKSession);
    logger.info("Created new session successfully");
    return copilotSDKSession;
  }

  @Override
  public List<Session> getSessions() {
    return sessions;
  }

  @Override
  public void close() {
    sessions.forEach(Session::close);
  }
}
