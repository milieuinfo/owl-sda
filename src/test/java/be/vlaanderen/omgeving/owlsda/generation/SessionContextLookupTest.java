package be.vlaanderen.omgeving.owlsda.generation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

/** Tests for {@link SessionContextLookup}. */
public class SessionContextLookupTest {

  @Test
  public void findContent_ContextPresent_ReturnsItsContent() {
    Session session = sessionWithContexts(context("Delegation Instructions", "do the work"));

    assertEquals(
        "do the work", SessionContextLookup.findContent(session, "Delegation Instructions"));
  }

  @Test
  public void findContent_ContextAbsent_ReturnsNull() {
    Session session = sessionWithContexts(context("Ontology", "..."));

    assertNull(SessionContextLookup.findContent(session, "Delegation Instructions"));
  }

  @Test
  public void hasNonBlankContent_NonBlankContent_ReturnsTrue() {
    Session session = sessionWithContexts(context("Delegation Instructions", "do the work"));

    assertTrue(SessionContextLookup.hasNonBlankContent(session, "Delegation Instructions"));
  }

  @Test
  public void hasNonBlankContent_BlankContent_ReturnsFalse() {
    Session session = sessionWithContexts(context("Delegation Instructions", "   "));

    assertFalse(SessionContextLookup.hasNonBlankContent(session, "Delegation Instructions"));
  }

  @Test
  public void hasNonBlankContent_ContextAbsent_ReturnsFalse() {
    Session session = sessionWithContexts();

    assertFalse(SessionContextLookup.hasNonBlankContent(session, "Delegation Instructions"));
  }

  private static Context context(String name, String content) {
    Context context = new Context();
    context.setName(name);
    context.setType("text/plain");
    context.setContent(content);
    return context;
  }

  private static Session sessionWithContexts(Context... contexts) {
    List<Context> stored = new ArrayList<>(List.of(contexts));
    return new Session() {
      @Override
      public void addContext(Context context) {
        stored.add(context);
      }

      @Override
      public boolean addContextIfChanged(Context context) {
        stored.add(context);
        return true;
      }

      @Override
      public List<Context> getContext() {
        return List.copyOf(stored);
      }

      @Override
      public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> ctxs) {
        return CompletableFuture.completedFuture(new ResponseMessage("id"));
      }

      @Override
      public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
        return prompt(input, getContext());
      }

      @Override
      public void close() {}
    };
  }
}
