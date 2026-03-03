package be.vlaanderen.omgeving.owlsda.benchmark;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import java.util.List;

/**
 * Snapshot input container for benchmark creation.
 */
public interface BenchmarkSnapshotData {
  String stage(); // GENERATE, FINALIZING, or REVIEW

  int shapesProcessed();

  long durationMs();

  Session generatorSession();

  Session reviewerSession();

  WorkerTripleStore tripleStore();

  List<Session> workerSessions();
}

