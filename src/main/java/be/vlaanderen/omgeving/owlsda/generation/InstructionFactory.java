package be.vlaanderen.omgeving.owlsda.generation;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for loading and rendering instruction templates from resource files.
 * Supports placeholder substitution ({{key}}) for dynamic content.
 */
public class InstructionFactory {

  private static final Logger logger = LoggerFactory.getLogger(InstructionFactory.class);
  private static final Map<String, String> templateCache = new HashMap<>();

  /**
   * Load a template from resources and render it with the provided placeholders.
   *
   * @param templateName Name of the template file (without .txt extension)
   * @param placeholders Map of placeholder names to values
   * @return Rendered instruction string
   */
  public static String render(String templateName, Map<String, String> placeholders) {
    String template = loadTemplate(templateName);
    return replacePlaceholders(template, placeholders);
  }

  /**
   * Load a template from resources without rendering.
   *
   * @param templateName Name of the template file (without .txt extension)
   * @return Template string
   */
  public static String load(String templateName) {
    return loadTemplate(templateName);
  }

  /**
   * Load a template and render with a single placeholder.
   *
   * @param templateName Name of the template file (without .txt extension)
   * @param placeholderKey Placeholder key to replace
   * @param placeholderValue Value to substitute
   * @return Rendered instruction string
   */
  public static String render(String templateName, String placeholderKey, String placeholderValue) {
    Map<String, String> placeholders = new HashMap<>();
    placeholders.put(placeholderKey, placeholderValue);
    return render(templateName, placeholders);
  }

  private static String loadTemplate(String templateName) {
    // Check cache first
    if (templateCache.containsKey(templateName)) {
      return templateCache.get(templateName);
    }

    String resourcePath = templateName.endsWith(".txt") ? templateName : templateName + ".txt";
    try (InputStream is = InstructionFactory.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new RuntimeException("Instruction template not found: " + resourcePath);
      }
      String content = new String(is.readAllBytes());
      templateCache.put(templateName, content);
      return content;
    } catch (Exception e) {
      throw new RuntimeException("Failed to load instruction template: " + resourcePath, e);
    }
  }

  private static String replacePlaceholders(String template, Map<String, String> placeholders) {
    String result = template;
    for (Map.Entry<String, String> entry : placeholders.entrySet()) {
      String placeholder = "{{" + entry.getKey() + "}}";
      String value = entry.getValue() != null ? entry.getValue() : "";
      result = result.replace(placeholder, value);
    }
    return result;
  }
}

