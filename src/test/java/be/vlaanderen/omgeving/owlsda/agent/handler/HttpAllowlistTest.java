package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.net.URI;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpAllowlistTest {

  @Test
  public void isAllowed_MatchingHost_ReturnsTrue() {
    HttpAllowlist allowlist = new HttpAllowlist(Set.of("example.org"));
    assertTrue(allowlist.isAllowed(URI.create("https://example.org/data.ttl")));
  }

  @Test
  public void isAllowed_HostIsCaseInsensitive() {
    HttpAllowlist allowlist = new HttpAllowlist(Set.of("Example.ORG"));
    assertTrue(allowlist.isAllowed(URI.create("https://example.org/data.ttl")));
  }

  @Test
  public void isAllowed_NonAllowlistedHost_ReturnsFalse() {
    HttpAllowlist allowlist = new HttpAllowlist(Set.of("example.org"));
    assertFalse(allowlist.isAllowed(URI.create("https://evil.example/data.ttl")));
  }

  @Test
  public void isAllowed_UriWithoutHost_ReturnsFalse() {
    HttpAllowlist allowlist = new HttpAllowlist(Set.of("example.org"));
    assertFalse(allowlist.isAllowed(URI.create("mailto:someone@example.org")));
  }

  @Test
  public void isAllowed_NullUri_ReturnsFalse() {
    HttpAllowlist allowlist = new HttpAllowlist(Set.of("example.org"));
    assertFalse(allowlist.isAllowed(null));
  }
}
