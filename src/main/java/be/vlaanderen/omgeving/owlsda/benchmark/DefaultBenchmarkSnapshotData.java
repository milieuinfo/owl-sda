package be.vlaanderen.omgeving.owlsda.benchmark;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import java.util.List;

/** Default implementation of BenchmarkSnapshotData. */
public record DefaultBenchmarkSnapshotData(
    String stage,
    int round,
    int shapesProcessed,
    long durationMs,
    Session generatorSession,
    Session reviewerSession,
    WorkerTripleStore tripleStore,
    List<Session> workerSessions)
    implements BenchmarkSnapshotData {}
