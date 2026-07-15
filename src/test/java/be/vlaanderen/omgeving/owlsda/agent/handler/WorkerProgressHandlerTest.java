package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class WorkerProgressHandlerTest {

  @Test
  @SuppressWarnings("unchecked")
  public void handle_AcceptsNumericStringForChangedTriplesCount() {
    AtomicReference<Context> publishedContext = new AtomicReference<>();
    WorkerProgressHandler handler = new WorkerProgressHandler("POOL-0", publishedContext::set);

    Map<String, Object> response =
        (Map<String, Object>)
            handler
                .handle(
                    Map.of(
                        "status", "CREATED",
                        "target_shape", "ex:Shape",
                        "target_class", "ex:Class",
                        "changed_triples_count", "5",
                        "created_or_updated_subjects", "ex:subject1",
                        "validation_result", "CONFORMS",
                        "remaining_issues", "none"))
                .join();

    assertEquals("success", response.get("status"));
    assertNotNull("Progress context should be published", publishedContext.get());
    assertEquals(
        "changed_triples_count=5",
        findLine(publishedContext.get().getContent(), "changed_triples_count="));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void handle_ReturnsErrorForNonNumericChangedTriplesCount() {
    WorkerProgressHandler handler = new WorkerProgressHandler("POOL-0", null);

    Map<String, Object> response =
        (Map<String, Object>)
            handler
                .handle(
                    Map.of(
                        "status", "CREATED",
                        "target_shape", "ex:Shape",
                        "target_class", "ex:Class",
                        "changed_triples_count", "not-a-number",
                        "created_or_updated_subjects", "ex:subject1",
                        "validation_result", "CONFORMS",
                        "remaining_issues", "none"))
                .join();

    assertEquals("error", response.get("status"));
  }

  private String findLine(String content, String prefix) {
    return content.lines().filter(line -> line.startsWith(prefix)).findFirst().orElse("");
  }
}
