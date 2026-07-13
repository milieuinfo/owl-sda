package be.vlaanderen.omgeving.owlsda.agent.ollama;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OllamaSessionParsingTest {

  @Test
  public void parseAssistantTurn_WithToolCallsAndTokenCounts_ReturnsExpectedValues() {
    JsonObject response = JsonParser.parseString("""
        {
          "message": {
            "role": "assistant",
            "content": "",
            "tool_calls": [
              {
                "function": {
                  "name": "output_data_write",
                  "arguments": {
                    "output": "@prefix ex: <https://example.org/> ."
                  }
                }
              }
            ]
          },
          "prompt_eval_count": 123,
          "eval_count": 45
        }
        """).getAsJsonObject();

    OllamaSession.ParsedAssistantTurn turn = OllamaSession.parseAssistantTurn(response);

    assertEquals(1, turn.toolCalls().size());
    assertEquals("output_data_write", turn.toolCalls().getFirst().name());
    assertEquals("@prefix ex: <https://example.org/> .", turn.toolCalls().getFirst().arguments().get("output"));
    assertEquals(123L, turn.promptEvalCount());
    assertEquals(45L, turn.evalCount());
  }

  @Test
  public void parseToolArguments_WithJsonString_ParsesObject() {
    Map<String, Object> arguments = OllamaSession.parseToolArguments(
        JsonParser.parseString("\"{\\\"target_agent\\\":\\\"POOL-0\\\"}\"")
    );

    assertEquals("POOL-0", arguments.get("target_agent"));
  }

  @Test
  public void parseToolArguments_WithInvalidInput_ReturnsEmptyMap() {
    Map<String, Object> arguments = OllamaSession.parseToolArguments(JsonParser.parseString("42"));

    assertTrue(arguments.isEmpty());
  }
}

