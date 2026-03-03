package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface SessionHandler {

  Map<String, Object> getArguments();

  String getName();

  String getDescription();

  CompletableFuture<Object> handle(Map<String, Object> arguments);
}
