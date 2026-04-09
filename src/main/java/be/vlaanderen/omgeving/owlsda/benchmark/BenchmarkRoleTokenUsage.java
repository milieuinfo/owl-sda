package be.vlaanderen.omgeving.owlsda.benchmark;

import lombok.Builder;
import lombok.Getter;

/**
 * Directional token usage for a single agent role.
 */
@Getter
@Builder
public class BenchmarkRoleTokenUsage {
  private long input;
  private long output;
  private long total;

  public static BenchmarkRoleTokenUsage fromValues(long input, long output, long total) {
    return BenchmarkRoleTokenUsage.builder()
        .input(input)
        .output(output)
        .total(total)
        .build();
  }
}

