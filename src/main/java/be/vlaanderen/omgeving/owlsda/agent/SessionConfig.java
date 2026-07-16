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
  private int betweenMessageTimeoutMs;
  private List<SessionHandler> handlers;
  // The model's real context window, in tokens, as configured for this role's server deployment
  // (e.g. llama.cpp/llama-swap --ctx-size). 0 means unset, in which case compaction falls back to
  // CompactionProperties' fixed token-count threshold instead of a window-relative ratio.
  private int contextWindowTokens;
}
