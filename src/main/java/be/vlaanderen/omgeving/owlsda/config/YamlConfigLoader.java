package be.vlaanderen.omgeving.owlsda.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.introspector.PropertyUtils;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helper to load YAML configurations into POJOs. Use the {@link YamlConfig}
 * annotation on a class to automatically load from a classpath resource.
 *
 * Supports kebab-case property names in YAML that map to camelCase Java properties.
 */
public final class YamlConfigLoader {

  private YamlConfigLoader() {
    // static helper
  }

  /**
   * Custom PropertyUtils that converts kebab-case YAML properties to camelCase Java properties.
   */
  static class KebabCasePropertyUtils extends PropertyUtils {

    @Override
    public Property getProperty(Class<?> type, String name) {
      // Convert kebab-case to camelCase
      String camelCaseName = kebabToCamelCase(name);
      try {
        return super.getProperty(type, camelCaseName);
      } catch (Exception e) {
        // If camelCase fails, try the original name
        return super.getProperty(type, name);
      }
    }

    private String kebabToCamelCase(String kebab) {
      if (!kebab.contains("-")) {
        return kebab;
      }
      StringBuilder camelCase = new StringBuilder();
      boolean capitalizeNext = false;
      for (char c : kebab.toCharArray()) {
        if (c == '-') {
          capitalizeNext = true;
        } else if (capitalizeNext) {
          camelCase.append(Character.toUpperCase(c));
          capitalizeNext = false;
        } else {
          camelCase.append(c);
        }
      }
      return camelCase.toString();
    }
  }

  /**
   * Loads a configuration object for the given class. The class must be annotated
   * with {@link YamlConfig}. If the annotation provides no resource or the resource
   * is missing and required=true, an IOException is thrown.
   *
   * @param type the POJO type
   * @param <T> generic type
   * @return the instantiated object populated from YAML
   * @throws IOException for IO or parse errors or when a required resource is missing
   */
  public static <T> T load(Class<T> type) throws IOException {
    YamlConfig cfg = type.getAnnotation(YamlConfig.class);
    if (cfg == null || cfg.resource().isEmpty()) {
      throw new IllegalArgumentException("Class must be annotated with @YamlConfig(resource = \"...\")");
    }
    return loadFromClasspath(cfg.resource(), type, cfg.required());
  }

  /**
   * Load a YAML file from the classpath.
   */
  public static <T> T loadFromClasspath(String resource, Class<T> type) throws IOException {
    return loadFromClasspath(resource, type, true);
  }

  private static <T> T loadFromClasspath(String resource, Class<T> type, boolean required) throws IOException {
    InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
    if (in == null) {
      if (required) {
        throw new IOException("Resource not found on classpath: " + resource);
      }
      return null;
    }

    try (Reader reader = new InputStreamReader(in)) {
      Yaml yaml = createYaml();
      try {
        return yaml.loadAs(reader, type);
      } catch (Exception e) {
        throw new IOException("Error parsing YAML resource: " + resource, e);
      }
    }
  }

  /**
   * Load a YAML file from the filesystem path.
   */
  public static <T> T loadFromPath(String path, Class<T> type) throws IOException {
    Path p = Path.of(path);
    if (!Files.exists(p)) {
      throw new IOException("File not found: " + path);
    }
    try (InputStream in = Files.newInputStream(p); Reader reader = new InputStreamReader(in)) {
      Yaml yaml = createYaml();
      try {
        return yaml.loadAs(reader, type);
      } catch (Exception e) {
        throw new IOException("Error parsing YAML file: " + path, e);
      }
    }
  }

  /**
   * Creates a YAML instance configured with KebabCasePropertyUtils to support
   * kebab-case property names in YAML files mapped to camelCase Java properties.
   */
  private static Yaml createYaml() {
    LoaderOptions options = new LoaderOptions();
    Constructor constructor = new Constructor(Object.class, options);
    constructor.setPropertyUtils(new KebabCasePropertyUtils());
    return new Yaml(constructor);
  }
}
