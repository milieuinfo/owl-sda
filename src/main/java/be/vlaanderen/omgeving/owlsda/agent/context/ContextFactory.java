package be.vlaanderen.omgeving.owlsda.agent.context;

import java.io.InputStream;

public class ContextFactory {
  private static Context workerContext;
  private static Context reviewerContext;
  private static Context supervisorContext;

  public static Context createWorkerContext() {
    if (workerContext == null) {
      workerContext = loadFromClasspath("worker.context.txt");
      workerContext.setName("Worker instructions");
    }
    return workerContext;
  }

  public static Context createReviewerContext() {
    if (reviewerContext == null) {
      reviewerContext = loadFromClasspath("reviewer.context.txt");
      reviewerContext.setName("Reviewer instructions");
    }
    return reviewerContext;
  }

  public static Context createSupervisorContext() {
    if (supervisorContext == null) {
      supervisorContext = loadFromClasspath("supervisor.context.txt");
      supervisorContext.setName("Supervisor instructions");
    }
    return supervisorContext;
  }

  private static Context loadFromClasspath(String path) {
    try (InputStream is = ContextFactory.class.getClassLoader().getResourceAsStream(path)) {
      if (is == null) {
        throw new RuntimeException("Context file not found: " + path);
      }
      String content = new String(is.readAllBytes());
      Context context = new Context();
      context.setType("text/plain");
      context.setContent(content);
      return context;
    } catch (Exception e) {
      throw new RuntimeException("Failed to load context from: " + path, e);
    }
  }
}
