package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.config.Config.ExtractExternalProperties.MirrorEntry;
import be.vlaanderen.omgeving.owlsda.config.Config.HttpToolProperties;
import be.vlaanderen.omgeving.owlsda.config.Config.UserContextEntry;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Builds the {@link HttpAllowlist} used by {@code http_call}, seeding it from hosts the project
 * already trusts (external ontology mirrors, user-context URLs) before adding any explicit
 * {@code tools.http.allowed-hosts} entries.
 */
public final class HttpAllowlistFactory {

  private HttpAllowlistFactory() {
  }

  public static HttpAllowlist build(Config config) {
    Set<String> hosts = new LinkedHashSet<>();
    HttpToolProperties httpProps = config.getTools().getHttp();

    if (httpProps.isSeedFromExtractMirrors() && config.getExtract() != null) {
      for (MirrorEntry entry : config.getExtract().getMirrors()) {
        addHostIfPresent(hosts, entry.getUri());
        for (String mirror : entry.getResolvedMirrors()) {
          addHostIfPresent(hosts, mirror);
        }
      }
    }

    if (httpProps.isSeedFromUserContext() && config.getUserContext() != null) {
      for (UserContextEntry entry : config.getUserContext()) {
        if (entry != null) {
          addHostIfPresent(hosts, entry.getUrl());
        }
      }
    }

    if (httpProps.getAllowedHosts() != null) {
      for (String allowed : httpProps.getAllowedHosts()) {
        addHostIfPresent(hosts, allowed);
      }
    }

    return new HttpAllowlist(hosts);
  }

  private static void addHostIfPresent(Set<String> hosts, String urlOrHost) {
    if (urlOrHost == null || urlOrHost.isBlank()) {
      return;
    }

    String host = extractHost(urlOrHost);
    if (host != null) {
      hosts.add(host);
    }
  }

  private static String extractHost(String urlOrHost) {
    try {
      URI uri = new URI(urlOrHost.trim());
      if (uri.getHost() != null) {
        return uri.getHost();
      }
    } catch (URISyntaxException ignored) {
      // fall through: treat the raw value as a bare host below
    }
    // Not a full URI (or no scheme) - treat the trimmed value as a bare host.
    String trimmed = urlOrHost.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
