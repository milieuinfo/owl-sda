package be.vlaanderen.omgeving.owlsda.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class SessionPoolTest {

  @Test
  public void returnSession_MakesItAvailableAgainAndTriggersCallback() throws Exception {
    SessionPool pool = new SessionPool(2);
    Session first = new NoOpSession();
    Session second = new NoOpSession();
    pool.addSession(first);
    pool.addSession(second);

    SessionPool.SessionWithId borrowed = pool.borrowSession();
    assertEquals(1, pool.getAvailableCount());

    AtomicInteger callbackCount = new AtomicInteger();
    pool.setSessionReturnCallback((session, sessionId) -> callbackCount.incrementAndGet());

    pool.returnSession(borrowed.session());

    assertEquals(2, pool.getAvailableCount());
    assertEquals(1, callbackCount.get());
    pool.close();
  }

  @Test
  public void returnSession_UnknownSession_IsIgnoredAndDoesNotGrowQueue() {
    SessionPool pool = new SessionPool(1);
    pool.addSession(new NoOpSession());
    assertEquals(1, pool.getAvailableCount());

    pool.returnSession(new NoOpSession()); // never added to this pool

    assertEquals(1, pool.getAvailableCount());
    pool.close();
  }

  @Test
  public void returnSession_Null_IsIgnored() {
    SessionPool pool = new SessionPool(1);
    pool.addSession(new NoOpSession());

    pool.returnSession(null);

    assertEquals(1, pool.getAvailableCount());
    pool.close();
  }

  @Test
  public void getAvailableCount_DecreasesOnBorrowAndIncreasesOnReturn() throws Exception {
    SessionPool pool = new SessionPool(3);
    pool.addSession(new NoOpSession());
    pool.addSession(new NoOpSession());
    pool.addSession(new NoOpSession());

    assertEquals(3, pool.getAvailableCount());

    SessionPool.SessionWithId a = pool.borrowSession();
    assertEquals(2, pool.getAvailableCount());

    SessionPool.SessionWithId b = pool.borrowSession();
    assertEquals(1, pool.getAvailableCount());

    pool.returnSession(a.session());
    assertEquals(2, pool.getAvailableCount());

    pool.returnSession(b.session());
    assertEquals(3, pool.getAvailableCount());
    pool.close();
  }

  @Test
  public void close_ClearsAvailableAndAllSessions() {
    SessionPool pool = new SessionPool(2);
    pool.addSession(new NoOpSession());
    pool.addSession(new NoOpSession());

    pool.close();

    assertEquals(0, pool.getAvailableCount());
    assertTrue(pool.getAllSessions().isEmpty());
  }

  @Test
  public void close_ThenReturnSession_NoLongerRecognizesSession() {
    SessionPool pool = new SessionPool(1);
    Session session = new NoOpSession();
    pool.addSession(session);

    pool.close();
    pool.returnSession(session);

    // After close(), allSessions no longer contains the session, so returnSession is a no-op.
    assertEquals(0, pool.getAvailableCount());
  }

  @Test
  public void getSessionId_ReturnsAssignedPoolIdForKnownSession() {
    SessionPool pool = new SessionPool(2);
    Session first = new NoOpSession();
    Session second = new NoOpSession();
    pool.addSession(first);
    pool.addSession(second);

    assertEquals("POOL-0", pool.getSessionId(first));
    assertEquals("POOL-1", pool.getSessionId(second));
    pool.close();
  }

  @Test
  public void getSessionId_UnknownSession_ReturnsUnknown() {
    SessionPool pool = new SessionPool(1);
    pool.addSession(new NoOpSession());

    assertEquals("UNKNOWN", pool.getSessionId(new NoOpSession()));
    pool.close();
  }

  @Test
  public void getAllSessions_ReturnsEverySessionAddedRegardlessOfBorrowState() throws Exception {
    SessionPool pool = new SessionPool(2);
    Session first = new NoOpSession();
    Session second = new NoOpSession();
    pool.addSession(first);
    pool.addSession(second);

    pool.borrowSession(); // remove one from the "available" queue only

    List<Session> all = pool.getAllSessions();
    assertEquals(2, all.size());
    assertTrue(all.contains(first));
    assertTrue(all.contains(second));
    pool.close();
  }

  @Test
  public void addSession_BeyondPoolSize_IsRejectedAndNotAddedToAllSessions() {
    SessionPool pool = new SessionPool(1);
    Session first = new NoOpSession();
    Session extra = new NoOpSession();

    pool.addSession(first);
    pool.addSession(extra);

    assertEquals(1, pool.getAllSessions().size());
    assertEquals("UNKNOWN", pool.getSessionId(extra));
    pool.close();
  }

  @Test
  public void isAvailable_ReflectsBorrowedState() throws Exception {
    SessionPool pool = new SessionPool(1);
    Session session = new NoOpSession();
    pool.addSession(session);

    assertTrue(pool.isAvailable(session));

    pool.borrowSession();
    assertFalse(pool.isAvailable(session));
    pool.close();
  }

  @Test
  public void getSize_ReturnsTotalSessionsAddedNotAvailableCount() throws Exception {
    SessionPool pool = new SessionPool(2);
    pool.addSession(new NoOpSession());
    pool.addSession(new NoOpSession());

    pool.borrowSession();

    assertEquals(2, pool.getSize());
    assertEquals(1, pool.getAvailableCount());
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
