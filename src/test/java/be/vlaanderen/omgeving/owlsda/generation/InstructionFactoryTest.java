package be.vlaanderen.omgeving.owlsda.generation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class InstructionFactoryTest {

  @Test
  public void render_SubstitutesAllPlaceholders() {
    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("name", "Ada");
    placeholders.put("place", "Brussels");
    placeholders.put("role", "worker");

    String result = InstructionFactory.render("instruction-factory-test-template", placeholders);

    assertTrue(result.contains("Hello Ada, welcome to Brussels."));
    assertTrue(result.contains("Your role is worker."));
  }

  @Test
  public void render_LeavesUnknownPlaceholdersUnresolved() {
    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("name", "Ada");
    placeholders.put("place", "Brussels");
    placeholders.put("role", "worker");

    String result = InstructionFactory.render("instruction-factory-test-template", placeholders);

    // The template references {{missing}}, which is absent from the placeholder map, so the
    // literal placeholder text is left untouched rather than substituted with an empty string.
    assertTrue(result.contains("Unresolved: {{missing}}"));
  }

  @Test
  public void render_WithNullPlaceholderValue_SubstitutesEmptyString() {
    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("name", "Ada");
    placeholders.put("place", "Brussels");
    placeholders.put("role", null);

    String result = InstructionFactory.render("instruction-factory-test-template", placeholders);

    assertTrue(result.contains("Your role is ."));
  }

  @Test
  public void render_SingleKeyValueOverload_SubstitutesPlaceholder() {
    String result = InstructionFactory.render("instruction-factory-test-template", "name", "Grace");

    assertTrue(result.contains("Hello Grace, welcome to {{place}}."));
  }

  @Test
  public void render_SingleKeyValueOverload_WithNullValue_SubstitutesEmptyString() {
    String result = InstructionFactory.render("instruction-factory-test-template", "name", null);

    assertTrue(result.contains("Hello , welcome to {{place}}."));
  }

  @Test
  public void load_ReturnsRawTemplateWithoutSubstitution() {
    String template = InstructionFactory.load("instruction-factory-test-template");

    assertTrue(template.contains("{{name}}"));
    assertTrue(template.contains("{{place}}"));
    assertTrue(template.contains("{{role}}"));
  }

  @Test
  public void render_HandlesTxtSuffixInTemplateNameExplicitly() {
    String withSuffix =
        InstructionFactory.render("instruction-factory-test-template.txt", Map.of("name", "X"));
    String withoutSuffix =
        InstructionFactory.render("instruction-factory-test-template", Map.of("name", "X"));

    assertEquals(withoutSuffix, withSuffix);
  }

  @Test
  public void render_RealWorkerInstructionsTemplate_SubstitutesKnownPlaceholders() {
    Map<String, String> placeholders = new HashMap<>();
    placeholders.put("delegationContextName", "delegation-round-1");
    placeholders.put("dataRichnessHint", "Data is sparse; generate new instances.");

    String result = InstructionFactory.render("worker-instructions-generation", placeholders);

    assertTrue(result.contains("delegation-round-1"));
    assertTrue(result.contains("Data is sparse; generate new instances."));
    assertFalse(result.contains("{{delegationContextName}}"));
    assertFalse(result.contains("{{dataRichnessHint}}"));
  }

  @Test
  public void render_MissingTemplate_ThrowsRuntimeException() {
    try {
      InstructionFactory.render("this-template-does-not-exist", Map.of());
      fail("Expected a RuntimeException for a missing template");
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("this-template-does-not-exist"));
    }
  }

  @Test
  public void render_EmptyPlaceholderMap_ReturnsTemplateUnchanged() {
    String template = InstructionFactory.load("instruction-factory-test-template");
    String result = InstructionFactory.render("instruction-factory-test-template", Map.of());

    assertEquals(template, result);
  }
}
