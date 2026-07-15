package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.handler.DelegationHandler;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerProgressHandler;
import java.util.List;

/**
 * Collects each delegated worker's latest activity (structured progress report if available,
 * otherwise its latest inbound/error message) and publishes it as a single digest context on the
 * supervisor session, so the supervisor can see what happened last round without re-reading every
 * worker's full transcript.
 */
class WorkerResponsePublisher {

  private static final String DELEGATION_CONTEXT_NAME = DelegationHandler.DELEGATION_CONTEXT_NAME;
  private static final String WORKER_PROGRESS_CONTEXT_NAME = WorkerProgressHandler.CONTEXT_NAME;
  private static final String WORKER_RESPONSES_CONTEXT_NAME = "Worker Responses";
  private static final int MAX_WORKER_RESPONSE_CHARS = 1200;

  private final ConcurrentWorkerBatch concurrentWorkerBatch;

  WorkerResponsePublisher(ConcurrentWorkerBatch concurrentWorkerBatch) {
    this.concurrentWorkerBatch = concurrentWorkerBatch;
  }

  void publish(Session supervisorSession, List<Session> delegatedWorkerSessions) {
    if (supervisorSession == null
        || concurrentWorkerBatch == null
        || concurrentWorkerBatch.workerSessionPool() == null
        || delegatedWorkerSessions == null
        || delegatedWorkerSessions.isEmpty()) {
      return;
    }

    StringBuilder content = new StringBuilder();
    for (Session workerSession : delegatedWorkerSessions) {
      if (workerSession == null
          || !SessionContextLookup.hasNonBlankContent(workerSession, DELEGATION_CONTEXT_NAME)) {
        continue;
      }

      String workerId = concurrentWorkerBatch.workerSessionPool().getSessionId(workerSession);
      if (workerId == null || workerId.isBlank() || "UNKNOWN".equals(workerId)) {
        continue;
      }

      String workerReport =
          SessionContextLookup.findContent(workerSession, WORKER_PROGRESS_CONTEXT_NAME);
      if (workerReport != null && !workerReport.isBlank()) {
        content
            .append(workerId)
            .append(" [STRUCTURED_PROGRESS]:\n")
            .append(truncate(workerReport))
            .append("\n\n");
        // Progress report already provides full context; skip the raw message log.
        continue;
      }

      // No structured progress report for this round — fall back to latest message.
      SessionMessageLogEntry latest = getLatestWorkerResponse(workerSession);
      if (latest == null) {
        continue;
      }

      content
          .append(workerId)
          .append(" [")
          .append(latest.direction())
          .append("]:\n")
          .append(truncate(latest.content()))
          .append("\n\n");
    }

    if (content.isEmpty()) {
      return;
    }

    Context workerResponses = new Context();
    workerResponses.setName(WORKER_RESPONSES_CONTEXT_NAME);
    workerResponses.setType("text/plain");
    workerResponses.setContent(content.toString().trim());
    supervisorSession.addContextIfChanged(workerResponses);
  }

  private SessionMessageLogEntry getLatestWorkerResponse(Session workerSession) {
    List<SessionMessageLogEntry> entries = workerSession.getMessageLog();
    for (int i = entries.size() - 1; i >= 0; i--) {
      SessionMessageLogEntry entry = entries.get(i);
      if ("INBOUND".equals(entry.direction()) || "ERROR".equals(entry.direction())) {
        return entry;
      }
    }
    return null;
  }

  private String truncate(String value) {
    if (value == null) {
      return "";
    }
    if (value.length() <= MAX_WORKER_RESPONSE_CHARS) {
      return value;
    }
    return value.substring(0, MAX_WORKER_RESPONSE_CHARS) + "...";
  }
}
