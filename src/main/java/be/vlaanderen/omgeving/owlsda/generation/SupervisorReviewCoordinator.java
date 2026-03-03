package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.OutputContext;
import be.vlaanderen.omgeving.owlsda.validation.OutputValidator;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates reviewer feedback and asks Supervisor to distribute tasks to workers.
 */
@Getter
public class SupervisorReviewCoordinator {
  private static final Logger logger = LoggerFactory.getLogger(SupervisorReviewCoordinator.class);

  private final Session reviewerSession;
  private final Supervisor supervisor;
  private final OutputValidator validator;

  @Setter
  private boolean ready = false;
  @Setter
  private boolean error = false;

  public SupervisorReviewCoordinator(Session reviewerSession, Supervisor supervisor, OutputValidator validator) {
    this.reviewerSession = reviewerSession;
    this.supervisor = supervisor;
    this.validator = validator;
  }

  public void review() {
    int fails = 0;
    while (!ready && !error) {
      try {
        String outputData = validator.getOutputDataAsString();
        OutputContext outputContext = new OutputContext(outputData);
        boolean changed = reviewerSession.addContextIfChanged(outputContext);

        String contextInfo = "";
        if (changed) {
          contextInfo = String.format(
              "\nNOTE: Updated output context (%d chars, %d lines).",
              outputData.length(), outputData.split("\n").length);
        }

        ResponseMessage message = reviewerSession.prompt(new RequestMessage(
            "Review the generated output against user instructions and ontology." + contextInfo
        )).get();

        if (ready || error) {
          break;
        }

        // Let supervisor decide how to handle review feedback
        // Supervisor can either handle directly or delegate to workers
        boolean handled = supervisor.handleReviewFeedback(message.getMessage());
        if (handled && validator.validate() == null) {
          logger.info("Review feedback handled successfully and output validated");
        }
      } catch (Exception e) {
        logger.error("Error in review loop: {}", e.getMessage());
        fails++;
        if (fails > 5) {
          break;
        }
      }
    }
  }
}

