package be.vlaanderen.omgeving.owlsda.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * {@code Main} resolves {@code --config} through {@link Config#load(String)}, so the documented
 * {@code file:} and {@code classpath:} prefixes have to keep working.
 */
public class ConfigLoadPrefixTest {

  private static final String CLASSPATH_FIXTURE = "owlsda-classpath-config.yml";

  private Path writeTempConfig() throws IOException {
    Path configFile = Files.createTempFile("owlsda-load-prefix", ".yml");
    Files.writeString(
        configFile,
        """
        input-path: "input.ttl"
        output-path: "output.ttl"
        user-input: "generate test data"
        """,
        StandardCharsets.UTF_8);
    return configFile;
  }

  @Test
  public void load_WithPlainFilesystemPath_ReadsTheFile() throws IOException {
    Config config = Config.load(writeTempConfig().toString());

    assertEquals("input.ttl", config.getInputPath());
    assertEquals("generate test data", config.getUserInput());
  }

  @Test
  public void load_WithFilePrefix_ReadsTheFile() throws IOException {
    Config config = Config.load("file:" + writeTempConfig());

    assertEquals("input.ttl", config.getInputPath());
  }

  @Test
  public void load_WithClasspathPrefix_ReadsTheResource() throws IOException {
    Config config = Config.load("classpath:" + CLASSPATH_FIXTURE);

    assertEquals("classpath-input.ttl", config.getInputPath());
    assertEquals("loaded from the classpath", config.getUserInput());
  }

  @Test
  public void load_WithoutPrefix_PrefersTheClasspathOverTheFilesystem() throws IOException {
    Config config = Config.load(CLASSPATH_FIXTURE);

    assertEquals("classpath-input.ttl", config.getInputPath());
  }

  @Test
  public void load_WithFilePrefixOnAClasspathResource_DoesNotFallBackToTheClasspath() {
    assertThrows(IOException.class, () -> Config.load("file:" + CLASSPATH_FIXTURE));
  }

  @Test
  public void load_WithNullLocation_Fails() {
    assertThrows(IllegalArgumentException.class, () -> Config.load(null));
  }
}
