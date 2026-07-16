package be.vlaanderen.omgeving.owlsda.generation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * Tests for {@link WorkerProgressReportParser}'s private status/progress classification helpers.
 * Follows the reflection-based private-method testing pattern established in {@code
 * SupervisorTest}/{@code WorkerAgentDelegationPromptTest}.
 */
public class WorkerProgressReportParserTest {

  @Test
  public void isAcceptableWorkerStatus_AcceptsOnlyNonBlockedKnownStatuses() throws Exception {
    Method method =
        WorkerProgressReportParser.class.getDeclaredMethod(
            "isAcceptableWorkerStatus", String.class);
    method.setAccessible(true);

    assertTrue((Boolean) method.invoke(null, "CREATED"));
    assertTrue((Boolean) method.invoke(null, "FIXED"));
    assertTrue((Boolean) method.invoke(null, "VERIFIED_NO_CHANGE"));
    assertFalse((Boolean) method.invoke(null, "BLOCKED"));
    assertFalse((Boolean) method.invoke(null, "NOT_A_REAL_STATUS"));
    assertFalse((Boolean) method.invoke(null, ""));
  }

  @Test
  public void isConformingProgress_RequiresAcceptableStatusAndConformsResult() throws Exception {
    WorkerProgressReportParser parser = new WorkerProgressReportParser(null, null);
    Method method =
        WorkerProgressReportParser.class.getDeclaredMethod("isConformingProgress", Map.class);
    method.setAccessible(true);

    assertTrue((Boolean) method.invoke(parser, progressMap("CREATED", "CONFORMS")));
    assertFalse(
        "non-conforming validation result must not count as progress",
        (Boolean) method.invoke(parser, progressMap("CREATED", "NON_CONFORMS")));
    assertFalse(
        "BLOCKED status must never count as progress even if flagged CONFORMS",
        (Boolean) method.invoke(parser, progressMap("BLOCKED", "CONFORMS")));
    assertFalse((Boolean) method.invoke(parser, new HashMap<String, String>()));
  }

  private Map<String, String> progressMap(String status, String validationResult) {
    Map<String, String> map = new HashMap<>();
    map.put("status", status);
    map.put("validation_result", validationResult);
    return map;
  }
}
