package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Set of hosts that the {@code http_call} tool is allowed to reach. A host matches if it is exactly
 * equal (case-insensitive) to an allowlisted host.
 */
public class HttpAllowlist {

  private final Set<String> hosts;

  public HttpAllowlist(Set<String> hosts) {
    Set<String> normalized = new LinkedHashSet<>();
    for (String host : hosts) {
      if (host != null && !host.isBlank()) {
        normalized.add(host.trim().toLowerCase(Locale.ROOT));
      }
    }
    this.hosts = Collections.unmodifiableSet(normalized);
  }

  public boolean isAllowed(URI uri) {
    if (uri == null || uri.getHost() == null) {
      return false;
    }
    return hosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
  }

  public Set<String> getHosts() {
    return hosts;
  }
}
