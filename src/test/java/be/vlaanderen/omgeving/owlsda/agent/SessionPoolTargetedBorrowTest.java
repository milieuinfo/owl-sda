package be.vlaanderen.omgeving.owlsda.agent;

import static org.junit.Assert.assertEquals;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class SessionPoolTargetedBorrowTest {

  @Test
  public void targetedBorrow_DoesNotStarveOtherAvailableSessions() throws Exception {
    SessionPool pool = new SessionPool(2);
    Session first = new NoOpSession();
    Session second = new NoOpSession();
    pool.addSession(first);
    pool.addSession(second);

    SessionPool.SessionWithId borrowedTarget = pool.borrowSessionById("POOL-0");
    assertEquals("POOL-0", borrowedTarget.sessionId());

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<SessionPool.SessionWithId> waitingTarget =
          executor.submit(() -> pool.borrowSessionById("POOL-0"));

      // Give the targeted-borrow task time to start waiting.
      Thread.sleep(100);

      SessionPool.SessionWithId generic = pool.borrowSession();
      assertEquals("POOL-1", generic.sessionId());

      pool.returnSession(generic.session());
      pool.returnSession(borrowedTarget.session());

      SessionPool.SessionWithId resumed = waitingTarget.get(2, TimeUnit.SECONDS);
      assertEquals("POOL-0", resumed.sessionId());
      pool.returnSession(resumed.session());
    } finally {
      executor.shutdownNow();
      pool.close();
    }
  }

  @Test
  public void unknownTarget_FallsBackToGenericBorrow() throws Exception {
    SessionPool pool = new SessionPool(1);
    pool.addSession(new NoOpSession());

    SessionPool.SessionWithId borrowed = pool.borrowSessionById("POOL-999");
    assertEquals("POOL-0", borrowed.sessionId());
    pool.returnSession(borrowed.session());
    pool.close();
  }

  private static final class NoOpSession implements Session {

    @Override
    public void addContext(Context context) {}

    @Override
    public boolean addContextIfChanged(Context context) {
      return true;
    }

    @Override
    public List<Context> getContext() {
      return List.of();
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> contexts) {
      return CompletableFuture.completedFuture(new ResponseMessage("ok"));
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      return CompletableFuture.completedFuture(new ResponseMessage("ok"));
    }

    @Override
    public void close() {}
  }
}
