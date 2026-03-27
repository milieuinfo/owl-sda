package be.vlaanderen.omgeving.owlsda;

import be.vlaanderen.omgeving.owlsda.benchmark.BenchmarkService;
import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.exception.LanguageModelException;
import be.vlaanderen.omgeving.owlsda.generation.ConcurrentWorkerBatch;
import be.vlaanderen.omgeving.owlsda.generation.Supervisor;
import be.vlaanderen.omgeving.owlsda.generation.SupervisorReviewCoordinator;
import be.vlaanderen.omgeving.owlsda.generation.SupervisorWorkflow;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.context.OntologyContext;
import be.vlaanderen.omgeving.owlsda.ontology.Ontology;
import be.vlaanderen.omgeving.owlsda.ontology.OntologyExtractor;
import be.vlaanderen.omgeving.owlsda.ontology.OntologyReasoner;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.agent.SessionManager;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main OWLSDA orchestrator.
 */
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
    int shapesPerBatch = config.getBatchSize() * config.getPoolCount();
    supervisorWorkflow.run(shapesPerBatch);
  }

  private void stopSessions() {
    if (sessionManager != null) {
      sessionManager.shutdown();
    }
  }

  private void cleanupOutputAndBenchmarks() {
    deleteFileIfExists(config.getOutputPath(), "output file");

    if (config.getBenchmark() != null && config.getBenchmark().isEnabled()) {
      deleteDirectoryContents(config.getBenchmark().getOutputDir(), "benchmark directory");
    }
  }

  private void deleteFileIfExists(String filePath, String label) {
    if (filePath == null || filePath.isBlank()) {
      return;
    }

    try {
      Path path = Path.of(filePath);
      if (Files.deleteIfExists(path)) {
        logger.info("Deleted previous {}: {}", label, path);
      }
    } catch (Exception e) {
      logger.warn("Could not delete {} '{}': {}", label, filePath, e.getMessage());
    }
  }

  private void deleteDirectoryContents(String dirPath, String label) {
    if (dirPath == null || dirPath.isBlank()) {
      return;
    }

    Path dir = Path.of(dirPath);
    if (!Files.exists(dir)) {
      return;
    }

    try (Stream<Path> paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      logger.info("Deleted previous {}: {}", label, dir);
    } catch (Exception e) {
      logger.warn("Could not delete {} '{}': {}", label, dirPath, e.getMessage());
    }
  }

  private void initialize() throws LanguageModelException {
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

    // Add ontology context to all sessions (workers, supervisor, and reviewer)
    OntologyContext ontologyContext = new OntologyContext(ontology);
    sessionManager.addContextToAllSessions(ontologyContext);
    logger.info("Added ontology context to all sessions");

    // Add user-defined contexts if provided
    if (config.getUserContext() != null && !config.getUserContext().isEmpty()) {
      for (Config.UserContextEntry entry : config.getUserContext()) {
        Context userContext = new Context();
        userContext.setName(entry.getName());
        userContext.setFilePath(entry.getPath());
        userContext.setType("text/plain");
        sessionManager.addContextToAllSessions(userContext);
      }
    }
  }

  private void buildSupervisorWorkflow() {
    Session supervisorSession = sessionManager.getSupervisorSession();
    Session reviewerSession = sessionManager.getReviewerSession();

    ConcurrentWorkerBatch concurrentWorkerBatch = new ConcurrentWorkerBatch(
        config, sessionManager.getWorkerSessionPool(), defaultShacl, validator);
    Supervisor supervisor = new Supervisor(
        supervisorSession,
        validator,
        concurrentWorkerBatch,
        defaultShacl,
        sessionManager.getSharedTripleStore(),
        sessionManager.getShapeProcessingTracker()
    );
    SupervisorReviewCoordinator reviewCoordinator = new SupervisorReviewCoordinator(
        reviewerSession, supervisor, validator, benchmarkService, sessionManager.getSharedTripleStore());

    sessionManager.setReviewerServiceProvider(() -> reviewCoordinator);

    supervisorWorkflow = new SupervisorWorkflow(
        config,
        defaultShacl,
        supervisor,
        reviewCoordinator,
        benchmarkService,
        sessionManager.getSharedTripleStore(),
        sessionManager.getWorkerSessionPool()
    );
  }
}
