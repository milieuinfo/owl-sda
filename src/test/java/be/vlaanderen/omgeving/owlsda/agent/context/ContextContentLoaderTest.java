package be.vlaanderen.omgeving.owlsda.agent.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.Test;

public class ContextContentLoaderTest {

  @Test
  public void getContent_WithTextFile_ReadsUtf8Content() throws IOException {
    Path tempText = Files.createTempFile("context-content", ".txt");
    Files.writeString(tempText, "alpha beta", StandardCharsets.UTF_8);

    Context context = new Context();
    context.setFilePath(tempText.toString());
    context.setType("text/plain");

    assertEquals("alpha beta", context.getContent());
  }

  @Test
  public void getContent_WithPdfFile_ExtractsTextContent() throws IOException {
    Path tempPdf = Files.createTempFile("context-content", ".pdf");
    createPdf(tempPdf, "Cancer treatment pathway");

    Context context = new Context();
    context.setFilePath(tempPdf.toString());

    String content = context.getContent();

    assertTrue(content != null && content.contains("Cancer treatment pathway"));
    assertEquals("application/pdf", ContextContentLoader.inferMimeType(tempPdf.toString(), null));
  }

  @Test
  public void getContent_CachesFileContentUntilContextSourceChanges() throws IOException {
    Path tempText = Files.createTempFile("context-content-cache", ".txt");
    Files.writeString(tempText, "first", StandardCharsets.UTF_8);

    Context context = new Context();
    context.setType("text/plain");
    context.setFilePath(tempText.toString());

    assertEquals("first", context.getContent());

    Files.writeString(tempText, "second", StandardCharsets.UTF_8);
    assertEquals("first", context.getContent());

    // Re-setting source invalidates cached content and forces a fresh read.
    context.setFilePath(tempText.toString());
    assertEquals("second", context.getContent());
  }

  @Test
  public void getContent_WithHttpUrl_ReadsRemoteContent() throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/context.txt", new FixedResponseHandler("available spaces: 124", "text/plain"));
    server.start();

    try {
      String url = "http://localhost:" + server.getAddress().getPort() + "/context.txt";
      Context context = new Context();
      context.setFilePath(url);

      assertEquals("available spaces: 124", context.getContent());
      assertEquals("text/plain", ContextContentLoader.inferMimeType(url, null));
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void inferMimeType_WithPdfHttpUrl_ReturnsPdfMimeType() {
    String url = "https://example.org/specification.pdf";
    assertEquals("application/pdf", ContextContentLoader.inferMimeType(url, null));
  }

  private void createPdf(Path path, String text) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
        contentStream.beginText();
        contentStream.setFont(PDType1Font.HELVETICA, 12);
        contentStream.newLineAtOffset(50, 700);
        contentStream.showText(text);
        contentStream.endText();
      }
      document.save(path.toFile());
    }
  }

  private static final class FixedResponseHandler implements HttpHandler {
    private final byte[] body;
    private final String contentType;

    private FixedResponseHandler(String body, String contentType) {
      this.body = body.getBytes(StandardCharsets.UTF_8);
      this.contentType = contentType;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
      exchange.getResponseHeaders().add("Content-Type", contentType);
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream outputStream = exchange.getResponseBody()) {
        outputStream.write(body);
      }
    }
  }
}
