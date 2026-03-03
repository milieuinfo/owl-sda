package be.vlaanderen.omgeving.owlsda.benchmark;

import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * Token usage breakdown captured for a benchmark snapshot.
 */
@Getter
@Builder
public class BenchmarkTokenUsage {
  private Map<String, Long> workers;
  private long reviewer;
  private long supervisor;
}

