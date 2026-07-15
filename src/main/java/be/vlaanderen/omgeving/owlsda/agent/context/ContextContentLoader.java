package be.vlaanderen.omgeving.owlsda.agent.context;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/** Loads context files with support for plain text and PDF inputs. */
public final class ContextContentLoader {
  public static final String PDF_MIME_TYPE = "application/pdf";
  public static final String TEXT_MIME_TYPE = "text/plain";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(CONNECT_TIMEOUT)
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  private ContextContentLoader() {}

  public static String load(String filePath, String declaredType) throws IOException {
    if (isHttpUrl(filePath)) {
      return loadFromUrl(filePath, declaredType);
    }

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

  private static String loadFromUrl(String sourceUrl, String declaredType) throws IOException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(sourceUrl))
            .timeout(READ_TIMEOUT)
            .header("User-Agent", "owlsda-context-loader/1.0")
            .GET()
            .build();

    try {
      HttpResponse<byte[]> response =
          HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IOException(
            "Failed to fetch URL context " + sourceUrl + ": HTTP " + response.statusCode());
      }

      byte[] body = response.body();
      if (isPdf(sourceUrl, declaredType)) {
        return loadPdf(new java.io.ByteArrayInputStream(body));
      }
      return new String(body, StandardCharsets.UTF_8);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while fetching URL context " + sourceUrl, e);
    }
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

  private static boolean isHttpUrl(String source) {
    if (source == null) {
      return false;
    }
    String normalized = source.toLowerCase(Locale.ROOT);
    return normalized.startsWith("http://") || normalized.startsWith("https://");
  }

  private static String loadPdf(Path path) throws IOException {
    try (PDDocument document = PDDocument.load(path.toFile())) {
      PDFTextStripper stripper = new PDFTextStripper();
      return stripper.getText(document);
    }
  }

  private static String loadPdf(InputStream in) throws IOException {
    try (PDDocument document = PDDocument.load(in)) {
      PDFTextStripper stripper = new PDFTextStripper();
      return stripper.getText(document);
    }
  }
}
