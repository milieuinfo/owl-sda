package be.vlaanderen.omgeving.owlsda.agent.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Loads context files with support for plain text and PDF inputs.
 */
public final class ContextContentLoader {
  public static final String PDF_MIME_TYPE = "application/pdf";
  public static final String TEXT_MIME_TYPE = "text/plain";

  private ContextContentLoader() {
  }

  public static String load(String filePath, String declaredType) throws IOException {
    Path path = Path.of(filePath);
    if (isPdf(filePath, declaredType)) {
      return loadPdf(path);
    }
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  public static String inferMimeType(String filePath, String declaredType) {
    if (declaredType != null && !declaredType.isBlank()) {
      return declaredType;
    }
    return hasPdfExtension(filePath) ? PDF_MIME_TYPE : TEXT_MIME_TYPE;
  }

  private static boolean isPdf(String filePath, String declaredType) {
    if (declaredType != null && PDF_MIME_TYPE.equalsIgnoreCase(declaredType.trim())) {
      return true;
    }
    return hasPdfExtension(filePath);
  }

  private static boolean hasPdfExtension(String filePath) {
    if (filePath == null) {
      return false;
    }
    return filePath.toLowerCase(Locale.ROOT).endsWith(".pdf");
  }

  private static String loadPdf(Path path) throws IOException {
    try (PDDocument document = PDDocument.load(path.toFile())) {
      PDFTextStripper stripper = new PDFTextStripper();
      return stripper.getText(document);
    }
  }
}

