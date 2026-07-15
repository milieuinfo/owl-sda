package be.vlaanderen.omgeving.owlsda.ontology;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes a file atomically via a temp-file-then-move, avoiding partial writes on failure. */
final class FileUtil {

  private FileUtil() {}

  @FunctionalInterface
  interface OutputStreamWriter {
    void write(OutputStream out) throws IOException;
  }

  static void writeAtomically(Path target, OutputStreamWriter writer) throws IOException {
    Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
    try {
      try (var out = Files.newOutputStream(tmp)) {
        writer.write(out);
      }
      try {
        Files.move(
            tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}
