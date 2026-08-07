package be.vlaanderen.omgeving.owlsda.benchmark;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import java.util.List;

/** Snapshot input container for benchmark creation. */
public interface BenchmarkSnapshotData {
  /**
   * LIVE, GENERATE, FINALIZING, REVIEW_ITERATION_&lt;n&gt;, REVIEW_ACCEPTED or REVIEW_REJECTED.
   */
  String stage();

  /** Monotonically increasing round number within the current run. */
  int round();

  int shapesProcessed();

  long durationMs();

  Session generatorSession();

  Session reviewerSession();

  WorkerTripleStore tripleStore();

  List<Session> workerSessions();
}
