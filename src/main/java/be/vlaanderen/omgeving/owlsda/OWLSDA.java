package be.vlaanderen.omgeving.owlsda;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionManager;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.context.ContextContentLoader;
import be.vlaanderen.omgeving.owlsda.agent.context.OntologyContext;
import be.vlaanderen.omgeving.owlsda.benchmark.BenchmarkService;
import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.generation.ConcurrentWorkerBatch;
import be.vlaanderen.omgeving.owlsda.generation.Supervisor;
import be.vlaanderen.omgeving.owlsda.generation.SupervisorReviewCoordinator;
import be.vlaanderen.omgeving.owlsda.generation.SupervisorWorkflow;
import be.vlaanderen.omgeving.owlsda.ontology.Ontology;
import be.vlaanderen.omgeving.owlsda.ontology.OntologyExtractor;
import be.vlaanderen.omgeving.owlsda.ontology.OntologyReasoner;
import be.vlaanderen.omgeving.owlsda.ontology.OntologySummaryFormatter;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Main OWLSDA orchestrator. */
@Getter
public class OWLSDA {
  private static final Logger logger = LoggerFactory.getLogger(OWLSDA.class);

  private final Config config;
  private final BenchmarkService benchmarkService;

  private Ontology ontology;
  private Shacl defaultShacl;
  private Shacl inferredShacl;

  private SessionManager sessionManager;
  private OutputValidator validator;
  private SupervisorWorkflow supervisorWorkflow;
  private ConcurrentWorkerBatch concurrentWorkerBatch;

  public OWLSDA(Config config) {
    this.config = config;
    this.benchmarkService = new BenchmarkService(config);
    initialize();
  }

  public void run() {
    long timeoutMs = config.getProgramTimeoutMs();
    if (timeoutMs <= 0) {
      runPipeline();
      return;
    }

    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> future = executor.submit(this::runPipeline);
    try {
      future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      stopSessions();
      throw new RuntimeException("Program timeout exceeded after " + timeoutMs + "ms", e);
    } catch (InterruptedException e) {
      future.cancel(true);
      stopSessions();
      Thread.currentThread().interrupt();
      throw new RuntimeException("Program interrupted", e);
    } catch (ExecutionException | CancellationException e) {
      stopSessions();
      throw new RuntimeException("Program failed", e);
    } finally {
      executor.shutdownNow();
    }
  }

  private void runPipeline() {
    cleanupOutputAndBenchmarks();
    prepareStep();

    int totalShapes =
        defaultShacl != null && defaultShacl.getShapes() != null
            ? defaultShacl.getShapes().size()
            : 0;
    if (totalShapes <= 0) {
      logger.error("No SHACL shapes available after preparation; skipping generation run");
      return;
    }

    int shapesPerBatch = config.getBatchSize() * config.getPoolCount();
    supervisorWorkflow.run(shapesPerBatch);
  }

  private void stopSessions() {
    if (concurrentWorkerBatch != null) {
      concurrentWorkerBatch.shutdown();
    }
    if (sessionManager != null) {
      sessionManager.shutdown();
    }
  }

  private void cleanupOutputAndBenchmarks() {
    deleteOutputFile(config.getOutputPath());

    if (config.getBenchmark() != null && config.getBenchmark().isEnabled()) {
      benchmarkService.archivePreviousRunIfPresent();
    }
  }

  private void deleteOutputFile(String filePath) {
    if (filePath == null || filePath.isBlank()) {
      return;
    }

    try {
      Path path = Path.of(filePath);
      if (Files.deleteIfExists(path)) {
        logger.info("Deleted previous output file: {}", path);
      }
    } catch (Exception e) {
      logger.warn("Could not delete output file '{}': {}", filePath, e.getMessage());
    }
  }

  private void initialize() {
    ontology = new Ontology();
    ontology.setFilePath(config.getInputPath());
  }

  private void prepareStep() {
    logger.info("Loading ontology ...");
    ontology.load();

    if (config.getExtract() != null) {
      new OntologyExtractor(config).adapt(ontology);
    }

    if (config.getReasoner() != null) {
      new OntologyReasoner(config).adapt(ontology);
    }

    defaultShacl = Shacl.fromOntology(ontology.getModel());
    inferredShacl = Shacl.fromOntology(ontology.getInferredModel());
    loadOrGenerateShacl();

    validator = new OutputValidator(config.getOutputPath(), defaultShacl);
    initializeSessions();
    buildSupervisorWorkflow();
  }

  private void loadOrGenerateShacl() {
    if (config.getShacl() != null && config.getShacl().getOutputDir() != null) {
      String defaultPath = config.getShacl().getOutputDir() + "/default-shacl.ttl";
      String inferredPath = config.getShacl().getOutputDir() + "/inferred-shacl.ttl";
      if (Files.exists(Path.of(defaultPath)) && Files.exists(Path.of(inferredPath))) {
        defaultShacl.load(defaultPath, true);
        inferredShacl.load(inferredPath, false);

        int loadedShapeCount =
            defaultShacl.getShapes() != null ? defaultShacl.getShapes().size() : 0;
        if (loadedShapeCount <= 0) {
          logger.warn(
              "Loaded cached SHACL files but found {} default shape(s); regenerating SHACL from ontology",
              loadedShapeCount);
          generateAndSaveShacl();
        } else {
          logger.info("Loaded {} SHACL shape(s) from cache", loadedShapeCount);
        }
      } else {
        generateAndSaveShacl();
      }
    } else {
      defaultShacl.generate();
      inferredShacl.generate();
    }
  }

  private void generateAndSaveShacl() {
    defaultShacl.generate();
    inferredShacl.generate();
    if (config.getShacl() != null && config.getShacl().getOutputDir() != null) {
      defaultShacl.save(config.getShacl().getOutputDir() + "/default-shacl.ttl");
      inferredShacl.save(config.getShacl().getOutputDir() + "/inferred-shacl.ttl");
    }
  }

  private void initializeSessions() {
    sessionManager = new SessionManager(config);
    sessionManager.setShacl(defaultShacl);
    sessionManager.setInferredShacl(inferredShacl);
    sessionManager.initialize();

    Context ontologySummaryContext = new Context();
    ontologySummaryContext.setName("Ontology Summary");
    ontologySummaryContext.setType("text/plain");
    ontologySummaryContext.setContent(OntologySummaryFormatter.summarize(ontology));
    sessionManager.addContextToAllSessions(ontologySummaryContext);
    logger.info("Added ontology summary context to all sessions");

    OntologyContext ontologyContext = new OntologyContext(ontology);
    if (config.getOntology().isProvideFullToWorkers()) {
      sessionManager.addContextToAllSessions(ontologyContext);
      logger.info("Added full ontology context to all sessions");
    } else {
      sessionManager.addContextToSupervisorAndReviewer(ontologyContext);
      logger.info(
          "Full ontology context withheld from workers (ontology.provide-full-to-workers=false);"
              + " workers get the summary only, supervisor/reviewer still receive the full"
              + " ontology");
    }

    // Add user-defined contexts if provided
    if (config.getUserContext() != null && !config.getUserContext().isEmpty()) {
      for (Config.UserContextEntry entry : config.getUserContext()) {
        if (entry == null || !entry.hasSource()) {
          logger.warn(
              "Skipping user context with missing source (path/url): {}",
              entry != null ? entry.getName() : "<null>");
          continue;
        }

        String source = entry.getSource();

        Context userContext = new Context();
        String contextName =
            (entry.getName() == null || entry.getName().isBlank())
                ? "user-context"
                : entry.getName();
        userContext.setName(contextName);
        userContext.setFilePath(source);
        userContext.setType(ContextContentLoader.inferMimeType(source, null));
        sessionManager.addContextToAllSessions(userContext);
      }
    }
  }

  private void buildSupervisorWorkflow() {
    Session supervisorSession = sessionManager.getSupervisorSession();

    concurrentWorkerBatch =
        new ConcurrentWorkerBatch(
            config, sessionManager.getWorkerSessionPool(), defaultShacl, validator);
    Supervisor supervisor =
        new Supervisor(
            supervisorSession,
            validator,
            concurrentWorkerBatch,
            defaultShacl,
            sessionManager.getSharedTripleStore(),
            sessionManager.getShapeProcessingTracker());
    int maxReviewAttempts =
        config.getClient() != null && config.getClient().getReviewer() != null
            ? config.getClient().getReviewer().getMaxReviewAttempts()
            : 3;

    SupervisorReviewCoordinator reviewCoordinator =
        new SupervisorReviewCoordinator(
            (Supplier<Session>) sessionManager::getReviewerSession,
            supervisor,
            validator,
            benchmarkService,
            sessionManager.getSharedTripleStore(),
            maxReviewAttempts);

    sessionManager.setReviewerServiceProvider(() -> reviewCoordinator);

    supervisorWorkflow =
        new SupervisorWorkflow(
            config,
            defaultShacl,
            supervisor,
            reviewCoordinator,
            benchmarkService,
            sessionManager.getSharedTripleStore(),
            sessionManager.getWorkerSessionPool());
  }
}
