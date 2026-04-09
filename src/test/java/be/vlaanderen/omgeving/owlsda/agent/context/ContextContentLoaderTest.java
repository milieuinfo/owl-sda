package be.vlaanderen.omgeving.owlsda.agent.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
}
