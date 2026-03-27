package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool;
import be.vlaanderen.omgeving.owlsda.agent.SessionPool.SessionWithId;
import be.vlaanderen.omgeving.owlsda.agent.context.ShaclContext;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a single worker agent handling an assigned SHACL shape range.
 */
public record WorkerAgent(SessionPool workerSessionPool, Shacl shacl, int workerIndex,
                          int totalWorkers, int startIndex, int endIndex, int totalShapes,
                          String instructions, AtomicBoolean success,
                          boolean isDelegationMode) implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(WorkerAgent.class);
  private static final String DELEGATION_CONTEXT_NAME = "Delegation Instructions";

  @Override
  public void run() {
    SessionWithId sessionWithId = null;
    try {
      String expectedSessionId = String.format("POOL-%d", workerIndex);
      sessionWithId = isDelegationMode
          ? workerSessionPool.borrowSessionById(expectedSessionId)
          : workerSessionPool.borrowSession();

      logger.info("[{}] Processing shapes {}-{}", sessionWithId.sessionId(), startIndex,
          endIndex - 1);
      if (isDelegationMode && !expectedSessionId.equals(sessionWithId.sessionId())) {
        logger.warn("[{}] Delegation mode session mismatch: expected {}, got {}",
            sessionWithId.sessionId(), expectedSessionId, sessionWithId.sessionId());
      }

      if (startIndex >= endIndex) {
        success.set(true);
        return;
      }

      Session session = sessionWithId.session();

      // In delegation mode, only run when this worker has fresh non-empty delegation instructions.
      if (isDelegationMode && !hasActiveDelegationInstructions(session)) {
        logger.info("[{}] No delegation instructions for this round; skipping worker execution",
            sessionWithId.sessionId());
        success.set(true);
        return;
      }

      // Only add SHACL context during initial generation, not during delegation/fix mode
      if (!isDelegationMode) {
        ShaclContext shaclContext = new ShaclContext(shacl, startIndex, endIndex, totalShapes);
        session.addContextIfChanged(shaclContext);
      }

      String workerInstructions = buildWorkerInstructions(session);
      session.prompt(new RequestMessage(workerInstructions, "worker-instructions-generation")).get();
      success.set(true);
    } catch (Exception e) {
      String sessionId = sessionWithId != null ? sessionWithId.sessionId() : "UNKNOWN";
      logger.error("[{}] Worker failed: {}", sessionId, e.getMessage());
      success.set(false);
    } finally {
      if (sessionWithId != null) {
        workerSessionPool.returnSession(sessionWithId.session());
      }
    }
  }

  private String buildWorkerInstructions(Session session) {
    String workerInstructions = instructions;
    if (totalWorkers > 1) {
      workerInstructions += String.format(
          "\n\nAssignment context: shapes %d-%d (worker %d of %d). "
              + "Follow your delegated task exactly (generate or fix as instructed). "
              + "Use triplestore_add/remove/read and validation tools as needed.",
          startIndex, endIndex - 1, workerIndex + 1, totalWorkers);
    } else {
      workerInstructions +=
          "\n\nAssignment context: single-worker run. "
              + "Follow your delegated task exactly (generate or fix as instructed). "
              + "Use triplestore_add/remove/read and validation tools as needed.";
    }

    if (!isDelegationMode) {
      return workerInstructions;
    }

    String delegationSnapshot = getDelegationContextContent(session);
    if (delegationSnapshot == null || delegationSnapshot.isBlank()) {
      return workerInstructions;
    }

    return workerInstructions
        + "\n\nLIVE DELEGATION SNAPSHOT (authoritative for this round; use this even if you do not call context_read):\n"
        + delegationSnapshot.trim()
        + "\n\nIf this live snapshot differs from your memory of earlier rounds, follow this snapshot."
        + " Use context_read to inspect the stored context verbatim when needed.";
  }

  private String getDelegationContextContent(Session session) {
    for (Context context : session.getContext()) {
      if (DELEGATION_CONTEXT_NAME.equals(context.getName())) {
        return context.getContent();
      }
    }
    return null;
  }

  private boolean hasActiveDelegationInstructions(Session session) {
    String delegationInstructions = getDelegationContextContent(session);
    return delegationInstructions != null && !delegationInstructions.isBlank();
  }
}
