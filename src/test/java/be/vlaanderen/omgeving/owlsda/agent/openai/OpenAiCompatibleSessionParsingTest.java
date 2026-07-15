package be.vlaanderen.omgeving.owlsda.agent.openai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.Test;

public class OpenAiCompatibleSessionParsingTest {

  @Test
  public void parseAssistantTurn_WithToolCallsAndUsage_ReturnsExpectedValues() {
    JsonObject response =
        JsonParser.parseString(
                """
        {
          "choices": [
            {
              "message": {
                "role": "assistant",
                "content": null,
                "tool_calls": [
                  {
                    "id": "call_abc123",
                    "type": "function",
                    "function": {
                      "name": "output_data_write",
                      "arguments": "{\\"output\\":\\"@prefix ex: <https://example.org/> .\\"}"
                    }
                  }
                ]
              }
            }
          ],
          "usage": {
            "prompt_tokens": 123,
            "completion_tokens": 45,
            "total_tokens": 168
          }
        }
        """)
            .getAsJsonObject();

    OpenAiCompatibleSession.ParsedAssistantTurn turn =
        OpenAiCompatibleSession.parseAssistantTurn(response);

    assertEquals(1, turn.toolCalls().size());
    assertEquals("call_abc123", turn.toolCalls().getFirst().id());
    assertEquals("output_data_write", turn.toolCalls().getFirst().name());
    assertEquals(
        "@prefix ex: <https://example.org/> .",
        turn.toolCalls().getFirst().arguments().get("output"));
    assertEquals(123L, turn.promptTokens());
    assertEquals(45L, turn.completionTokens());
  }

  @Test
  public void parseAssistantTurn_WithPlainContentAndNoToolCalls_ReturnsContent() {
    JsonObject response =
        JsonParser.parseString(
                """
        {
          "choices": [
            {
              "message": {
                "role": "assistant",
                "content": "hello there"
              }
            }
          ],
          "usage": {
            "prompt_tokens": 10,
            "completion_tokens": 2
          }
        }
        """)
            .getAsJsonObject();

    OpenAiCompatibleSession.ParsedAssistantTurn turn =
        OpenAiCompatibleSession.parseAssistantTurn(response);

    assertEquals("hello there", turn.content());
    assertTrue(turn.toolCalls().isEmpty());
    assertEquals(10L, turn.promptTokens());
    assertEquals(2L, turn.completionTokens());
  }

  @Test
  public void parseAssistantTurn_WithMissingChoices_ReturnsEmptyTurn() {
    JsonObject response = JsonParser.parseString("{}").getAsJsonObject();

    OpenAiCompatibleSession.ParsedAssistantTurn turn =
        OpenAiCompatibleSession.parseAssistantTurn(response);

    assertNull(turn.content());
    assertTrue(turn.toolCalls().isEmpty());
    assertEquals(0L, turn.promptTokens());
    assertEquals(0L, turn.completionTokens());
  }

  @Test
  public void parseToolArguments_WithJsonEncodedString_ParsesObject() {
    Map<String, Object> arguments =
        OpenAiCompatibleSession.parseToolArguments(
            JsonParser.parseString("\"{\\\"target_agent\\\":\\\"POOL-0\\\"}\""));

    assertEquals("POOL-0", arguments.get("target_agent"));
  }

  @Test
  public void parseToolArguments_WithInvalidInput_ReturnsEmptyMap() {
    Map<String, Object> arguments =
        OpenAiCompatibleSession.parseToolArguments(JsonParser.parseString("42"));

    assertTrue(arguments.isEmpty());
  }
}
