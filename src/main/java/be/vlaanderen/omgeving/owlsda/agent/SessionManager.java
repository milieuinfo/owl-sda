package be.vlaanderen.omgeving.owlsda.agent;

import be.vlaanderen.omgeving.owlsda.agent.handler.OutputReplaceHandler;
import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.exception.LanguageModelException;
import be.vlaanderen.omgeving.owlsda.generation.ShapeProcessingTracker;
import be.vlaanderen.omgeving.owlsda.generation.SupervisorReviewCoordinator;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.context.ContextFactory;
import be.vlaanderen.omgeving.owlsda.agent.handler.ContextReaderHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.DelegationHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.HttpAllowlist;
import be.vlaanderen.omgeving.owlsda.agent.handler.HttpAllowlistFactory;
import be.vlaanderen.omgeving.owlsda.agent.handler.HttpCallHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.MemoryGetHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.MemorySetHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.OutputAppendHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.OutputFeedbackHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.OutputReaderHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.OutputValidatorHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.OutputWriterHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.RunMemoryStore;
import be.vlaanderen.omgeving.owlsda.agent.handler.SessionHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.ShapeStatusCheckerHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.ToolFilter;
import be.vlaanderen.omgeving.owlsda.agent.handler.TripleStoreAddHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.TripleStoreClearHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.TripleStoreReadHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.TripleStoreRemoveHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerProgressHandler;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages all agent sessions: worker pool, supervisor, and reviewer.
 */
@Getter
public class SessionManager {
  private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
  private static final String DELEGATION_CONTEXT_NAME = DelegationHandler.DELEGATION_CONTEXT_NAME;

  private final Config config;

  /**
   * SHACL shapes used for validation.
   */
  @Setter
  private Shacl shacl;
  @Setter
  private Shacl inferredShacl;

  /**
   * Lazy provider for the reviewer service, set after the full workflow is constructed.
   */
  @Setter
  private ReviewerServiceProvider reviewerServiceProvider;

  private final Map<String, Client> clientsByProvider = new LinkedHashMap<>();
  private SessionPool workerSessionPool;
  private Session supervisorSession;
  private Session reviewerSession;
  private final List<Context> sharedContexts = new ArrayList<>();
  private final WorkerTripleStore sharedTripleStore;
  private final RunMemoryStore sharedMemoryStore;
  private final HttpAllowlist httpAllowlist;
  private final HttpClient sharedHttpClient;

  private ShapeProcessingTracker shapeProcessingTracker;

  public SessionManager(Config config) {
    this.config = config;
    this.sharedTripleStore = new WorkerTripleStore(config.getOutputPath());
    this.sharedMemoryStore = new RunMemoryStore(
        config.getTools().getMemory().getMaxEntries(),
        config.getTools().getMemory().getMaxValueBytes());
    this.httpAllowlist = HttpAllowlistFactory.build(config);
    this.sharedHttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(config.getTools().getHttp().getConnectTimeoutMs()))
        .build();
  }

  /**
   * Functional interface for lazy ReviewerService access
   */
  @FunctionalInterface
  public interface ReviewerServiceProvider {
    SupervisorReviewCoordinator get();
  }

  public void initialize() {
    logger.info("Initializing LLM clients and sessions...");

    try {
      initializeWorkerSessionPool();
      initializeSupervisorSession();
      logger.info("Worker and supervisor sessions initialized successfully");
    } catch (Exception e) {
      logger.error("Failed to initialize sessions", e);
      throw new LanguageModelException("Failed to initialize sessions: " + e.getMessage());
    }
  }

  private void initializeWorkerSessionPool() {
    String workerProvider = ClientFactory.resolveProvider(config, ClientFactory.Role.WORKER);
    Client workerClient = getOrCreateClient(workerProvider);

    int poolCount = config.getPoolCount();
    workerSessionPool = new SessionPool(poolCount);

    logger.info("Creating worker session pool with {} sessions sharing one triple store (provider: {})",
        poolCount, workerProvider);

    for (int i = 0; i < poolCount; i++) {
      try {
        List<SessionHandler> handlers = new ArrayList<>();

        // Worker ID for this session
        String workerId = "POOL-" + i;

        Config.RoleToolsProperties workerTools = config.getTools().getWorker();

        // Self-referential context reader for pool sessions
        final Session[] sessionRef = new Session[1];
        if (ToolFilter.isEnabled(workerTools, ContextReaderHandler.NAME)) {
          handlers.add(new ContextReaderHandler(() ->
              sessionRef[0] != null ? sessionRef[0].getContext() : List.of()
          ));
        }
        if (ToolFilter.isEnabled(workerTools, WorkerProgressHandler.NAME)) {
          handlers.add(new WorkerProgressHandler(workerId, progress -> {
            if (sessionRef[0] != null) {
              sessionRef[0].addContextIfChanged(progress);
            }
          }));
        }

        // Triple store handlers (shared store with unique worker ID)
        if (ToolFilter.isEnabled(workerTools, TripleStoreAddHandler.NAME)) {
          handlers.add(new TripleStoreAddHandler(sharedTripleStore, workerId));
        }
        if (ToolFilter.isEnabled(workerTools, TripleStoreRemoveHandler.NAME)) {
          handlers.add(new TripleStoreRemoveHandler(sharedTripleStore, workerId));
        }
        if (ToolFilter.isEnabled(workerTools, TripleStoreReadHandler.NAME)) {
          handlers.add(new TripleStoreReadHandler(sharedTripleStore, workerId));
        }
        if (ToolFilter.isEnabled(workerTools, TripleStoreClearHandler.NAME)) {
          handlers.add(new TripleStoreClearHandler(sharedTripleStore, workerId));
        }

        // Output handlers
        if (ToolFilter.isEnabled(workerTools, OutputReaderHandler.NAME)) {
          handlers.add(new OutputReaderHandler(config));
        }

        if (shacl != null && ToolFilter.isEnabled(workerTools, OutputValidatorHandler.NAME)) {
          // Workers validate the shared triple store and publish validation context to all sessions
          handlers.add(new OutputValidatorHandler(
              shacl,
              null,
              sharedTripleStore,
              this::addContextToAllSessionsIfChanged
          ));
        }

        if (config.getTools().getHttp().isEnabled()
            && ToolFilter.isEnabled(workerTools, HttpCallHandler.NAME)) {
          handlers.add(newHttpCallHandler());
        }
        if (config.getTools().getMemory().isEnabled()) {
          if (ToolFilter.isEnabled(workerTools, MemorySetHandler.NAME)) {
            handlers.add(new MemorySetHandler(sharedMemoryStore, workerId));
          }
          if (ToolFilter.isEnabled(workerTools, MemoryGetHandler.NAME)) {
            handlers.add(new MemoryGetHandler(sharedMemoryStore));
          }
        }

        SessionConfig sessionConfig = SessionConfig.builder()
            .systemContext(ContextFactory.createWorkerContext())
            .model(config.getClient().getWorker().getModel())
            .timeoutMs(config.getClient().getWorker().getTimeoutMs())
            .betweenMessageTimeoutMs(config.getClient().getWorker().getBetweenMessageTimeoutMs())
            .handlers(handlers)
            .build();

        Session workerSession = workerClient.createSession(sessionConfig);
        sessionRef[0] = workerSession;
        workerSessionPool.addSession(workerSession);
      } catch (Exception e) {
        logger.error("Failed to create worker session {}", i, e);
        throw new LanguageModelException("Failed to create worker session: " + e.getMessage());
      }
    }

    logger.info("Worker pool initialized with {} sessions sharing one triple store", poolCount);
  }

  private void initializeSupervisorSession() {
    try {
      String supervisorProvider = ClientFactory.resolveProvider(config, ClientFactory.Role.SUPERVISOR);
      Client supervisorClient = getOrCreateClient(supervisorProvider);

      Config.RoleToolsProperties supervisorTools = config.getTools().getSupervisor();
      List<SessionHandler> handlers = new ArrayList<>();
      if (ToolFilter.isEnabled(supervisorTools, ContextReaderHandler.NAME)) {
        handlers.add(new ContextReaderHandler(
            () -> supervisorSession != null ? supervisorSession.getContext() : List.of()
        ));
      }
      if (ToolFilter.isEnabled(supervisorTools, DelegationHandler.NAME)) {
        handlers.add(new DelegationHandler(this::publishDelegationContext));
      }
      if (ToolFilter.isEnabled(supervisorTools, OutputWriterHandler.NAME)) {
        handlers.add(new OutputWriterHandler(config));
      }
      if (ToolFilter.isEnabled(supervisorTools, OutputAppendHandler.NAME)) {
        handlers.add(new OutputAppendHandler(config));
      }
      if (ToolFilter.isEnabled(supervisorTools, OutputReplaceHandler.NAME)) {
        handlers.add(new OutputReplaceHandler(config));
      }
      if (ToolFilter.isEnabled(supervisorTools, OutputReaderHandler.NAME)) {
        handlers.add(new OutputReaderHandler(config));
      }

      // Add shape status checker tool if tracker is available
      if (shapeProcessingTracker != null
          && ToolFilter.isEnabled(supervisorTools, ShapeStatusCheckerHandler.NAME)) {
        handlers.add(new ShapeStatusCheckerHandler(shapeProcessingTracker));
      }

      if (shacl != null && ToolFilter.isEnabled(supervisorTools, OutputValidatorHandler.NAME)) {
        // Supervisor can validate both file and argument data and publish validation context to all sessions
        handlers.add(new OutputValidatorHandler(
            inferredShacl,
            config,
            null,
            this::addContextToAllSessionsIfChanged
        ));
      }

      if (config.getTools().getHttp().isEnabled()
          && ToolFilter.isEnabled(supervisorTools, HttpCallHandler.NAME)) {
        handlers.add(newHttpCallHandler());
      }
      if (config.getTools().getMemory().isEnabled()) {
        if (ToolFilter.isEnabled(supervisorTools, MemorySetHandler.NAME)) {
          handlers.add(new MemorySetHandler(sharedMemoryStore, "SUPERVISOR"));
        }
        if (ToolFilter.isEnabled(supervisorTools, MemoryGetHandler.NAME)) {
          handlers.add(new MemoryGetHandler(sharedMemoryStore));
        }
      }

      SessionConfig supervisorConfig = SessionConfig.builder()
          .systemContext(ContextFactory.createSupervisorContext())
          .model(config.getClient().getSupervisor().getModel())
          .timeoutMs(config.getClient().getSupervisor().getTimeoutMs())
          .betweenMessageTimeoutMs(config.getClient().getSupervisor().getBetweenMessageTimeoutMs())
          .handlers(handlers)
          .build();

      supervisorSession = supervisorClient.createSession(supervisorConfig);
      logger.info("Supervisor session created with dedicated context (provider: {}, model: {}, timeout: {}ms)",
          supervisorProvider,
          config.getClient().getSupervisor().getModel(),
          config.getClient().getSupervisor().getTimeoutMs());
    } catch (Exception e) {
      logger.error("Failed to create supervisor session", e);
      throw new LanguageModelException("Failed to create supervisor session: " + e.getMessage());
    }
  }

  private DelegationHandler.PublicationResult publishDelegationContext(
      String name, String targetAgent, String instructions) {
    if (workerSessionPool == null) {
      return DelegationHandler.PublicationResult.error("Worker session pool is not initialized");
    }

    Session targetSession = resolveTargetSession(targetAgent);
    if (targetSession == null) {
      return DelegationHandler.PublicationResult.error(
          "Unknown target_agent '" + targetAgent + "'. Use POOL-<index> (for example: POOL-0).");
    }

    String resolvedTargetAgent = workerSessionPool.getSessionId(targetSession);

    Context delegation = new Context();
    delegation.setName(name);
    delegation.setType("text/plain");
    delegation.setContent(instructions);

    // Canonical alias consumed by workers and supervisor delegation tracking.
    Context canonicalDelegation = new Context();
    canonicalDelegation.setName(DELEGATION_CONTEXT_NAME);
    canonicalDelegation.setType("text/plain");
    canonicalDelegation.setContent(instructions);

    if (supervisorSession != null) {
      Context supervisorDelegationView = new Context();
      supervisorDelegationView.setName(name + " [" + resolvedTargetAgent + "]");
      supervisorDelegationView.setType("text/plain");
      supervisorDelegationView.setContent(instructions);
      supervisorSession.addContextIfChanged(supervisorDelegationView);
    }

    targetSession.addContextIfChanged(delegation);
    targetSession.addContextIfChanged(canonicalDelegation);
    logger.info("Delegation '{}' routed to {}", name, resolvedTargetAgent);

    return DelegationHandler.PublicationResult.success(resolvedTargetAgent);
  }

  private Session resolveTargetSession(String targetAgent) {
    String normalizedTarget = normalizeTargetAgent(targetAgent);
    if (normalizedTarget == null) {
      return null;
    }

    for (Session session : workerSessionPool.getAllSessions()) {
      if (normalizedTarget.equals(workerSessionPool.getSessionId(session))) {
        return session;
      }
    }

    return null;
  }

  private String normalizeTargetAgent(String targetAgent) {
    if (targetAgent == null || targetAgent.isBlank()) {
      return null;
    }

    String normalized = targetAgent.trim().toUpperCase(Locale.ROOT);
    if (normalized.startsWith("POOL-")) {
      return normalized;
    }

    if (normalized.startsWith("WORKER-")) {
      String suffix = normalized.substring("WORKER-".length());
      Integer index = parseNonNegativeInteger(suffix);
      return index == null ? null : "POOL-" + index;
    }

    Integer rawIndex = parseNonNegativeInteger(normalized);
    return rawIndex == null ? null : "POOL-" + rawIndex;
  }

  private Integer parseNonNegativeInteger(String value) {
    try {
      int parsed = Integer.parseInt(value);
      return parsed >= 0 ? parsed : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private Session initializeReviewerSession() {
    try {
      String reviewerProvider = ClientFactory.resolveProvider(config, ClientFactory.Role.REVIEWER);
      Client reviewerClient = getOrCreateClient(reviewerProvider);

      Config.RoleToolsProperties reviewerTools = config.getTools().getReviewer();
      List<SessionHandler> handlers = new ArrayList<>();
      if (ToolFilter.isEnabled(reviewerTools, ContextReaderHandler.NAME)) {
        handlers.add(new ContextReaderHandler(
            () -> reviewerSession != null ? reviewerSession.getContext() : List.of()
        ));
      }
      if (ToolFilter.isEnabled(reviewerTools, OutputReaderHandler.NAME)) {
        handlers.add(new OutputReaderHandler(config));
      }
      if (inferredShacl != null && ToolFilter.isEnabled(reviewerTools, OutputValidatorHandler.NAME)) {
        // Reviewer can validate both file and argument data and publish validation context to all sessions
        handlers.add(new OutputValidatorHandler(
            inferredShacl,
            config,
            null,
            this::addContextToAllSessionsIfChanged
        ));
      }

      if (ToolFilter.isEnabled(reviewerTools, OutputFeedbackHandler.NAME)) {
        // Use lazy provider for OutputFeedbackHandler (unified feedback handler)
        handlers.add(new OutputFeedbackHandler(
            () -> reviewerServiceProvider != null ? reviewerServiceProvider.get() : null));
      }

      if (config.getTools().getHttp().isEnabled()
          && ToolFilter.isEnabled(reviewerTools, HttpCallHandler.NAME)) {
        handlers.add(newHttpCallHandler());
      }
      if (config.getTools().getMemory().isEnabled()) {
        if (ToolFilter.isEnabled(reviewerTools, MemorySetHandler.NAME)) {
          handlers.add(new MemorySetHandler(sharedMemoryStore, "REVIEWER"));
        }
        if (ToolFilter.isEnabled(reviewerTools, MemoryGetHandler.NAME)) {
          handlers.add(new MemoryGetHandler(sharedMemoryStore));
        }
      }

      SessionConfig reviewerConfig = SessionConfig.builder()
          .systemContext(ContextFactory.createReviewerContext())
          .model(config.getClient().getReviewer().getModel())
          .timeoutMs(config.getClient().getReviewer().getTimeoutMs())
          .betweenMessageTimeoutMs(config.getClient().getReviewer().getBetweenMessageTimeoutMs())
          .handlers(handlers)
          .build();

      Session createdReviewerSession = reviewerClient.createSession(reviewerConfig);
      logger.info("Reviewer session created (provider: {}, model: {}, timeout: {}ms)",
          reviewerProvider,
          config.getClient().getReviewer().getModel(),
          config.getClient().getReviewer().getTimeoutMs());
      return createdReviewerSession;
    } catch (Exception e) {
      logger.error("Failed to create reviewer session", e);
      throw new LanguageModelException("Failed to create reviewer session: " + e.getMessage());
    }
  }

  public Session getReviewerSession() {
    synchronized (sharedContexts) {
      if (reviewerSession == null) {
        reviewerSession = initializeReviewerSession();
        replaySharedContextsToReviewer(reviewerSession);
      }
      return reviewerSession;
    }
  }

  public Session getReviewerSessionIfInitialized() {
    synchronized (sharedContexts) {
      return reviewerSession;
    }
  }

  private void replaySharedContextsToReviewer(Session reviewer) {
    for (Context storedContext : sharedContexts) {
      reviewer.addContextIfChanged(new Context(storedContext));
    }
  }

  private void rememberSharedContext(Context context) {
    synchronized (sharedContexts) {
      Context snapshot = new Context(context);
      sharedContexts.remove(snapshot);
      sharedContexts.add(snapshot);
    }
  }

  public void addContextToAllSessions(Context context) {
    rememberSharedContext(context);

    List<Session> allSessions = new ArrayList<>(workerSessionPool.getAllSessions());
    allSessions.add(supervisorSession);
    Session initializedReviewer = getReviewerSessionIfInitialized();
    if (initializedReviewer != null) {
      allSessions.add(initializedReviewer);
    }

    for (Session session : allSessions) {
      if (session != null) {
        session.addContext(new Context(context));
      }
    }

    logger.debug("Added context '{}' to all sessions", context.getName());
  }

  public void addContextToAllSessionsIfChanged(Context context) {
    rememberSharedContext(context);

    List<Session> allSessions = new ArrayList<>(workerSessionPool.getAllSessions());
    allSessions.add(supervisorSession);
    Session initializedReviewer = getReviewerSessionIfInitialized();
    if (initializedReviewer != null) {
      allSessions.add(initializedReviewer);
    }

    for (Session session : allSessions) {
      if (session != null) {
        session.addContextIfChanged(new Context(context));
      }
    }

    logger.debug("Added/updated context '{}' to all sessions (if changed)", context.getName());
  }


  public void shutdown() {
    try {
      sharedMemoryStore.clear();

      if (workerSessionPool != null) {
        for (Session session : workerSessionPool.getAllSessions()) {
          safeCloseSession(session, "worker");
        }
        workerSessionPool.close();
      }

      safeCloseSession(supervisorSession, "supervisor");
      safeCloseSession(getReviewerSessionIfInitialized(), "reviewer");

      for (Client client : clientsByProvider.values()) {
        client.close();
      }
      logger.info("Session manager shut down");
    } catch (Exception e) {
      logger.warn("Session manager shutdown encountered errors: {}", e.getMessage());
    }
  }

  private HttpCallHandler newHttpCallHandler() {
    Config.HttpToolProperties httpProps = config.getTools().getHttp();
    return new HttpCallHandler(
        httpAllowlist,
        sharedHttpClient,
        httpProps.getReadTimeoutMs(),
        httpProps.getMaxResponseBodyBytes(),
        httpProps.isAllowPost(),
        httpProps.getMaxRetries());
  }

  private Client getOrCreateClient(String provider) {
    return clientsByProvider.computeIfAbsent(provider, p -> {
      logger.info("Initializing LLM client for provider '{}'", p);
      return ClientFactory.create(config, p);
    });
  }

  private void safeCloseSession(Session session, String role) {
    if (session == null) {
      return;
    }
    try {
      session.close();
    } catch (Exception e) {
      logger.warn("Failed to close {} session: {}", role, e.getMessage());
    }
  }
}
