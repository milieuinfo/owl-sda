package be.vlaanderen.omgeving.owlsda.agent;

import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.handler.SessionHandler;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public final class SessionConfig {
  private String model;
  private Context systemContext;
  private int timeoutMs;
  private List<SessionHandler> handlers;

  public void addHandler(SessionHandler handler) {
    handlers.add(handler);
  }
}
