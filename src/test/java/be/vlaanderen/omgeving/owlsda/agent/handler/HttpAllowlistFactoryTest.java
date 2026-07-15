package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class HttpAllowlistFactoryTest {

  @Test
  public void build_SeedsHostsFromExtractMirrorsAndUserContext() throws IOException {
    Path configFile = Files.createTempFile("owlsda-http-allowlist", ".yml");
    Files.writeString(
        configFile,
        """
        input-path: "input.ttl"
        output-path: "output.ttl"
        user-input: "generate test data"
        user-context:
          - name: "LDES guide"
            url: "https://semiceu.github.io/LinkedDataEventStreams/"
        extract:
          mirrors:
            - uri: "http://www.w3.org/ns/prov#"
              mirrors:
                - "https://www.w3.org/ns/prov-o"
            - uri: "http://xmlns.com/foaf/0.1/"
              mirrors:
                - "https://xmlns.com/foaf/spec/index.rdf"
        """,
        StandardCharsets.UTF_8);

    Config config = Config.loadFile(configFile.toString());
    HttpAllowlist allowlist = HttpAllowlistFactory.build(config);

    assertTrue(allowlist.isAllowed(URI.create("http://www.w3.org/ns/prov#")));
    assertTrue(allowlist.isAllowed(URI.create("https://www.w3.org/ns/prov-o")));
    assertTrue(allowlist.isAllowed(URI.create("https://xmlns.com/foaf/spec/index.rdf")));
    assertTrue(
        allowlist.isAllowed(URI.create("https://semiceu.github.io/LinkedDataEventStreams/")));
    assertFalse(allowlist.isAllowed(URI.create("https://not-trusted.example/data.ttl")));
  }

  @Test
  public void build_WithSeedingDisabled_OnlyUsesExplicitAllowedHosts() throws IOException {
    Path configFile = Files.createTempFile("owlsda-http-allowlist", ".yml");
    Files.writeString(
        configFile,
        """
        input-path: "input.ttl"
        output-path: "output.ttl"
        user-input: "generate test data"
        user-context:
          - name: "LDES guide"
            url: "https://semiceu.github.io/LinkedDataEventStreams/"
        extract:
          mirrors:
            - uri: "http://www.w3.org/ns/prov#"
        tools:
          http:
            seed-from-extract-mirrors: false
            seed-from-user-context: false
            allowed-hosts: ["allowed.example"]
        """,
        StandardCharsets.UTF_8);

    Config config = Config.loadFile(configFile.toString());
    HttpAllowlist allowlist = HttpAllowlistFactory.build(config);

    assertTrue(allowlist.isAllowed(URI.create("https://allowed.example/data.ttl")));
    assertFalse(allowlist.isAllowed(URI.create("http://www.w3.org/ns/prov#")));
    assertFalse(
        allowlist.isAllowed(URI.create("https://semiceu.github.io/LinkedDataEventStreams/")));
  }
}
