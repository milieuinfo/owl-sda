package be.vlaanderen.omgeving.owlsda.benchmark;

import lombok.Builder;
import lombok.Getter;

/** Represents a single benchmark snapshot with its metadata. */
@Getter
@Builder
public class BenchmarkSnapshot {
  private String timestamp;
  private String stage; // GENERATE, FINALIZING, or REVIEW
  private int shapesProcessed;
  private long durationMs;
  private long triplestoreSize;
  private boolean triplestoreEmpty;
  private int currentViolations;
  private String snapshotDirectory;
  private BenchmarkTokenUsage tokens;
}
