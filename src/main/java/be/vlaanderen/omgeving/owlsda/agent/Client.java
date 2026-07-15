package be.vlaanderen.omgeving.owlsda.agent;

import java.util.List;
import java.util.concurrent.ExecutionException;

/** Large Language Model (LLM) client interface. */
public interface Client extends AutoCloseable {
  /**
   * Retrieves the name of the LLM client.
   *
   * @return the name of the LLM client
   */
  String getName();

  /**
   * Creates a new session for interacting with the LLM.
   *
   * @param config the SessionConfig object containing the configuration for the session
   * @return a new Session object representing the created session
   */
  Session createSession(SessionConfig config) throws ExecutionException, InterruptedException;

  /**
   * Retrieves the list of sessions associated with this LLM.
   *
   * @return a list of LLMSession objects representing the sessions of this LLM
   */
  List<Session> getSessions();

  /**
   * Closes the LLM client and releases any resources associated with it.
   *
   * @throws Exception if an error occurs while closing the client
   */
  void close() throws Exception;
}
