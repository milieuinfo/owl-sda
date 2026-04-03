package be.vlaanderen.omgeving.owlsda.agent.context;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Factory for creating pre-loaded system contexts for worker, reviewer, and supervisor agents.
 * Contexts are loaded once from classpath resources and cached for reuse.
 */
public class ContextFactory {
  private static volatile Context workerContext;
  private static volatile Context reviewerContext;
  private static volatile Context supervisorContext;

  private ContextFactory() {
    // static factory
  }

  public static Context createWorkerContext() {
    if (workerContext == null) {
      synchronized (ContextFactory.class) {
        if (workerContext == null) {
          workerContext = loadFromClasspath("worker.context.txt");
          workerContext.setName("Worker instructions");
        }
      }
    }
    return workerContext;
  }

  public static Context createReviewerContext() {
    if (reviewerContext == null) {
      synchronized (ContextFactory.class) {
        if (reviewerContext == null) {
          reviewerContext = loadFromClasspath("reviewer.context.txt");
          reviewerContext.setName("Reviewer instructions");
        }
      }
    }
    return reviewerContext;
  }

  public static Context createSupervisorContext() {
    if (supervisorContext == null) {
      synchronized (ContextFactory.class) {
        if (supervisorContext == null) {
          supervisorContext = loadFromClasspath("supervisor.context.txt");
          supervisorContext.setName("Supervisor instructions");
        }
      }
    }
    return supervisorContext;
  }

  private static Context loadFromClasspath(String path) {
    try (InputStream is = ContextFactory.class.getClassLoader().getResourceAsStream(path)) {
      if (is == null) {
        throw new RuntimeException("Context file not found: " + path);
      }
      String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
      Context context = new Context();
      context.setType("text/plain");
      context.setContent(content);
      return context;
    } catch (Exception e) {
      throw new RuntimeException("Failed to load context from: " + path, e);
    }
  }
}
