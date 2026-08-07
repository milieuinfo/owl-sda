package be.vlaanderen.omgeving.owlsda.benchmark;

import lombok.Builder;
import lombok.Getter;

/** Represents a single benchmark snapshot with its metadata. */
@Getter
@Builder
public class BenchmarkSnapshot {
  private String timestamp;
  // LIVE, GENERATE, FINALIZING, REVIEW_ITERATION_<n>, REVIEW_ACCEPTED or REVIEW_REJECTED
  private String stage;
  private int shapesProcessed;
  private long durationMs;
  private long triplestoreSize;
  private boolean triplestoreEmpty;
  private int currentViolations;
  private BenchmarkTokenUsage tokens;
}
