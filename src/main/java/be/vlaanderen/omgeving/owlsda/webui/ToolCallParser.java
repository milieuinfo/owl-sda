package be.vlaanderen.omgeving.owlsda.webui;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort parser for the two {@code TOOL_INVOCATION} content shapes seen in session message
 * logs: {@code toolName({"json":"args"})} (HTTP-based chat sessions) and {@code
 * ToolRequest[toolCallId=..., name=..., arguments={...}]} (Copilot SDK sessions, whose arguments
 * are a Java {@code toString()} of a map, not valid JSON).
 */
final class ToolCallParser {

  private static final Pattern SIMPLE_CALL =
      Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\((.*)\\)$", Pattern.DOTALL);
  private static final Pattern TOOL_REQUEST_TO_STRING =
      Pattern.compile(
          "^ToolRequest\\[toolCallId=(.*?), name=(.*?), arguments=(.*)]$", Pattern.DOTALL);

  private ToolCallParser() {}

  /**
   * Returns the parsed name/arguments for a {@code TOOL_INVOCATION} entry, or null if unrecognized.
   */
  static ParsedToolCall parse(String content) {
    if (content == null || content.isBlank()) {
      return null;
    }
    String trimmed = content.trim();

    Matcher toStringMatcher = TOOL_REQUEST_TO_STRING.matcher(trimmed);
    if (toStringMatcher.matches()) {
      // arguments here is a Java Map#toString() (key=value, ...), never real JSON -- Gson's
      // lenient parser will still "succeed" on simple flat maps like "{mode=count}" by treating
      // '=' as ':', which would mislabel it as JSON.
      String arguments = toStringMatcher.group(3).trim();
      return new ParsedToolCall(toStringMatcher.group(2).trim(), arguments, false);
    }

    Matcher simpleMatcher = SIMPLE_CALL.matcher(trimmed);
    if (simpleMatcher.matches()) {
      String arguments = simpleMatcher.group(2).trim();
      return new ParsedToolCall(simpleMatcher.group(1), arguments, isJson(arguments));
    }

    return null;
  }

  private static boolean isJson(String value) {
    if (value.isEmpty()) {
      return false;
    }
    try {
      JsonParser.parseString(value);
      return true;
    } catch (JsonSyntaxException e) {
      return false;
    }
  }

  /** A tool invocation's name and its (possibly non-JSON) argument string. */
  record ParsedToolCall(String name, String arguments, boolean argumentsAreJson) {}
}
