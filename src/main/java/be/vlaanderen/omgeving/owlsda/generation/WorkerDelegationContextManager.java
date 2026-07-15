package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.handler.DelegationHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerProgressHandler;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages worker-session delegation-context lifecycle: clearing stale delegation instructions and
 * the worker progress report at the start of each round, and querying which worker sessions
 * currently carry active (non-blank) delegation instructions.
 */
class WorkerDelegationContextManager {

  private static final String DELEGATION_CONTEXT_NAME = DelegationHandler.DELEGATION_CONTEXT_NAME;
  private static final String WORKER_PROGRESS_CONTEXT_NAME = WorkerProgressHandler.CONTEXT_NAME;

  private final ConcurrentWorkerBatch concurrentWorkerBatch;

  WorkerDelegationContextManager(ConcurrentWorkerBatch concurrentWorkerBatch) {
    this.concurrentWorkerBatch = concurrentWorkerBatch;
  }

  /**
   * Clear delegation context and stale progress report for all workers at the start of each
   * delegation round. Workers without new assignments must not retain stale instructions, and the
   * Worker Progress Report must be wiped so the supervisor cannot mistake a prior round's result
   * for current-round progress.
   *
   * <p>Sessions are intentionally NOT reset here: message history persistence lets workers remember
   * static context (ontology, SHACL shapes) they already fetched in earlier rounds instead of
   * re-reading it every round via context_reader. Context-window growth across rounds is bounded by
   * each session's own automatic compaction (see {@code CompactionProperties}) rather than a
   * blanket reset.
   */
  void clearDelegationInstructions() {
    if (concurrentWorkerBatch == null || concurrentWorkerBatch.workerSessionPool() == null) {
      return;
    }

    for (Session workerSession : concurrentWorkerBatch.workerSessionPool().getAllSessions()) {
      List<String> delegationContextNames =
          workerSession.getContext().stream()
              .map(Context::getName)
              .filter(WorkerDelegationContextManager::isDelegationContextName)
              .distinct()
              .toList();

      if (delegationContextNames.isEmpty()) {
        delegationContextNames = List.of(DELEGATION_CONTEXT_NAME);
      }

      for (String contextName : delegationContextNames) {
        Context clearedDelegation = new Context();
        clearedDelegation.setName(contextName);
        clearedDelegation.setType("text/plain");
        clearedDelegation.setContent("");
        workerSession.addContextIfChanged(clearedDelegation);
      }

      // Clear the Worker Progress Report so the supervisor does not read a stale result
      // from a previous round and mistake it for a report from the current round.
      Context clearedProgress = new Context();
      clearedProgress.setName(WORKER_PROGRESS_CONTEXT_NAME);
      clearedProgress.setType("text/plain");
      clearedProgress.setContent("");
      workerSession.addContextIfChanged(clearedProgress);
    }
  }

  int getDelegatedWorkerCount() {
    if (concurrentWorkerBatch == null || concurrentWorkerBatch.workerSessionPool() == null) {
      return 0;
    }

    int delegatedWorkers = 0;
    for (Session workerSession : concurrentWorkerBatch.workerSessionPool().getAllSessions()) {
      if (SessionContextLookup.hasNonBlankContent(workerSession, DELEGATION_CONTEXT_NAME)) {
        delegatedWorkers++;
      }
    }

    return delegatedWorkers;
  }

  List<Session> getDelegatedWorkerSessions() {
    if (concurrentWorkerBatch == null || concurrentWorkerBatch.workerSessionPool() == null) {
      return List.of();
    }

    List<Session> delegated = new ArrayList<>();
    for (Session workerSession : concurrentWorkerBatch.workerSessionPool().getAllSessions()) {
      if (SessionContextLookup.hasNonBlankContent(workerSession, DELEGATION_CONTEXT_NAME)) {
        delegated.add(workerSession);
      }
    }
    return delegated;
  }

  private static boolean isDelegationContextName(String contextName) {
    return contextName != null && contextName.toLowerCase().contains("delegation");
  }
}
