package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs worker batches concurrently across the worker session pool.
 */
public record ConcurrentWorkerBatch(Config config, SessionPool workerSessionPool, Shacl shacl,
                                    OutputValidator validator) {

  private static final Logger logger = LoggerFactory.getLogger(ConcurrentWorkerBatch.class);

  public boolean run(String instructions, int shapes, boolean isDelegationMode)
      throws InterruptedException {
    if (shacl == null || shacl.getShapes() == null) {
      logger.error("Cannot run batch: SHACL or shapes are null");
      return false;
    }

    if (workerSessionPool == null) {
      logger.error("Cannot run batch: Worker session pool is null");
      return false;
    }

    int totalUnprocessedShapes = (int) shacl.getShapes().stream()
        .filter(shape -> !shape.isProcessed())
        .count();

    int workerCount = workerSessionPool.getSize();
    int batchSize = (config != null) ? config.getBatchSize() : 1;
    int actualShapes = Math.min(shapes, totalUnprocessedShapes);

    List<Thread> threads = new ArrayList<>();
    List<AtomicBoolean> results = new ArrayList<>();

    for (int i = 0; i < workerCount; i++) {
      int startIndex = i * batchSize;
      int endIndex = Math.min(startIndex + batchSize, actualShapes);

      AtomicBoolean workerSuccess = new AtomicBoolean(false);
      results.add(workerSuccess);

      WorkerAgent worker = new WorkerAgent(
          workerSessionPool, shacl,
          i, workerCount,
          startIndex, endIndex,
          actualShapes,
          instructions,
          workerSuccess,
          isDelegationMode
      );

      Thread thread = new Thread(worker);
      threads.add(thread);
      thread.start();
    }

    for (Thread thread : threads) {
      thread.join();
    }

    boolean allSuccess = results.stream().allMatch(AtomicBoolean::get);
    if (!allSuccess) {
      logger.warn("One or more workers failed");
      return false;
    }

    if (validator == null) {
      return true;
    }

    // Validation remains informational here; global store validity is tracked separately.
    String validationReport = validator.validate();
    if (validationReport != null && !validationReport.isBlank()) {
      logger.debug("Store still has validation issues after worker batch; continuing to next round");
    }
    return true;
  }

  public boolean runWithDelegation(String instructions, int shapes) throws InterruptedException {
    // Delegation mode: workers should not receive new SHACL shapes, only work with existing data
    return run(instructions, shapes, true);
  }

  public int getWorkerCount() {
    return workerSessionPool.getSize();
  }
}
