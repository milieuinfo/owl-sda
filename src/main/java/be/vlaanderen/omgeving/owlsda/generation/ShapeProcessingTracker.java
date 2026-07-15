package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks which SHACL shapes have been completed by workers, for supervisor status reporting via
 * {@code check_shape_status}.
 *
 * <p>This only tracks completion, not per-worker delegation: because delegation instructions are
 * free-text (a supervisor tells workers what to do in prose, not a rigid shape-to-worker assignment
 * table), there is no reliable way to attribute "this shape is currently being worked on by worker
 * X" versus "not yet delegated." Earlier versions of this class attempted to track that distinction
 * but nothing ever populated it, so every shape not yet marked completed was silently misreported
 * as "not delegated" and {@code allDelegatedShapesCompleted()} vacuously always returned {@code
 * true}. Reporting only total/completed/remaining avoids presenting data this class cannot actually
 * know.
 */
public class ShapeProcessingTracker {
  private static final Logger logger = LoggerFactory.getLogger(ShapeProcessingTracker.class);

  private final Shacl shacl;

  // Set of shape names that have been marked as completed
  private final Set<String> completedShapes = new HashSet<>();

  public ShapeProcessingTracker(Shacl shacl) {
    this.shacl = shacl;
  }

  /**
   * Marks a shape as completed/processed.
   *
   * @param shapeName The name of the shape
   */
  public synchronized void markCompleted(String shapeName) {
    completedShapes.add(shapeName);

    // Also mark in SHACL shape object if available
    if (shacl != null && shacl.getShapes() != null) {
      shacl.getShapes().stream()
          .filter(shape -> shape.getName().equals(shapeName))
          .findFirst()
          .ifPresent(shape -> shape.setProcessed(true));
    }

    logger.info("Shape '{}' marked as completed", shapeName);
  }

  /**
   * Marks multiple shapes as completed.
   *
   * @param shapeNames List of shape names
   */
  public synchronized void markCompleted(List<String> shapeNames) {
    for (String shapeName : shapeNames) {
      markCompleted(shapeName);
    }
  }

  /**
   * Gets the status of all shapes for supervisor review.
   *
   * @return ShapeProcessingStatus containing all processing information
   */
  public synchronized ShapeProcessingStatus getStatus() {
    int totalShapes = shacl != null && shacl.getShapes() != null ? shacl.getShapes().size() : 0;

    return new ShapeProcessingStatus(
        totalShapes, completedShapes.size(), new ArrayList<>(completedShapes));
  }

  /**
   * Gets a formatted report of shape processing status.
   *
   * @return Human-readable status report
   */
  public synchronized String getStatusReport() {
    ShapeProcessingStatus status = getStatus();

    StringBuilder report = new StringBuilder();
    report.append("=== SHAPE PROCESSING STATUS ===\n\n");
    report.append(String.format("Total Shapes: %d\n", status.getTotalShapes()));
    report.append(
        String.format(
            "Completed: %d (%.1f%%)\n",
            status.getCompletedCount(),
            status.getTotalShapes() > 0
                ? (status.getCompletedCount() * 100.0 / status.getTotalShapes())
                : 0));
    report.append(String.format("Remaining: %d\n\n", status.getRemainingCount()));

    if (!status.getCompletedShapes().isEmpty()) {
      report.append("COMPLETED SHAPES:\n");
      for (String shapeName : status.getCompletedShapes()) {
        report.append(String.format("  - %s\n", shapeName));
      }
      report.append("\n");
    }

    return report.toString();
  }

  /**
   * Checks whether every known shape has been marked completed.
   *
   * @return true if there are no remaining shapes
   */
  public synchronized boolean allShapesCompleted() {
    int totalShapes = shacl != null && shacl.getShapes() != null ? shacl.getShapes().size() : 0;
    return totalShapes > 0 && completedShapes.size() >= totalShapes;
  }

  /** Resets all tracking data (for new generation runs). */
  public synchronized void reset() {
    completedShapes.clear();
    logger.info("Shape processing tracker reset");
  }

  /** Data class containing shape processing status information. */
  @Getter
  public static class ShapeProcessingStatus {
    private final int totalShapes;
    private final int completedCount;
    private final List<String> completedShapes;

    public ShapeProcessingStatus(
        int totalShapes, int completedCount, List<String> completedShapes) {
      this.totalShapes = totalShapes;
      this.completedCount = completedCount;
      this.completedShapes = completedShapes;
    }

    public int getRemainingCount() {
      return Math.max(0, totalShapes - completedCount);
    }
  }
}
