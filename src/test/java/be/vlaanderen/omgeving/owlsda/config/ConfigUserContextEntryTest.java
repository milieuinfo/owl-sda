package be.vlaanderen.omgeving.owlsda.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConfigUserContextEntryTest {

  @Test
  public void loadFile_WithUrlUserContext_MapsUrlAndResolvesSource() throws IOException {
    Path configFile = Files.createTempFile("owlsda-user-context", ".yml");
    Files.writeString(configFile, """
        input-path: "input.ttl"
        output-path: "output.ttl"
        user-input: "generate test data"
        user-context:
          - name: "LDES spec"
            url: "https://example.org/ldes"
        """, StandardCharsets.UTF_8);

    Config config = Config.loadFile(configFile.toString());

    assertEquals(1, config.getUserContext().size());
    Config.UserContextEntry entry = config.getUserContext().getFirst();
    assertEquals("https://example.org/ldes", entry.getUrl());
    assertEquals("https://example.org/ldes", entry.getSource());
    assertTrue(entry.hasSource());
  }

  @Test
  public void userContextEntry_WhenUrlMissing_FallsBackToPath() {
    Config.UserContextEntry entry = new Config.UserContextEntry();
    entry.setPath("examples/project-1/input-example.txt");

    assertEquals("examples/project-1/input-example.txt", entry.getSource());
    assertTrue(entry.hasSource());
  }
}


