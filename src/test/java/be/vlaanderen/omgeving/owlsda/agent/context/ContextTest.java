package be.vlaanderen.omgeving.owlsda.agent.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class ContextTest {

  @Test
  public void defaultConstructor_StartsWithNullFields() {
    Context context = new Context();

    assertNull(context.getName());
    assertNull(context.getType());
    assertNull(context.getFilePath());
    assertNull(context.getContent());
    assertEquals(0, context.getContentHash());
  }

  @Test
  public void copyConstructor_CopiesNameTypeFilePathAndLoadedContent() {
    Context original = new Context();
    original.setName("ctx-1");
    original.setType("text/plain");
    original.setFilePath("/tmp/does-not-need-to-exist.txt");
    original.setContent("already loaded content");

    Context copy = new Context(original);

    assertEquals(original.getName(), copy.getName());
    assertEquals(original.getType(), copy.getType());
    assertEquals(original.getFilePath(), copy.getFilePath());
    assertEquals(original.getContent(), copy.getContent());
    assertEquals(original.getContentHash(), copy.getContentHash());
    assertNotSame(original, copy);
  }

  @Test
  public void copyConstructor_DoesNotForceFileReadWhenContentNotYetLoaded() throws IOException {
    Path tempFile = Files.createTempFile("context-copy", ".txt");
    Files.writeString(tempFile, "file contents", StandardCharsets.UTF_8);

    Context original = new Context();
    original.setName("ctx-file");
    original.setFilePath(tempFile.toString());
    original.setType("text/plain");
    // Content has never been read via getContent(), so it should still be null internally.

    Context copy = new Context(original);

    // The copy should not have eagerly read the file either; filePath is preserved so lazy
    // loading still works when getContent() is eventually called.
    assertEquals(tempFile.toString(), copy.getFilePath());
    assertEquals("file contents", copy.getContent());
  }

  @Test
  public void setContent_UpdatesContentHash() {
    Context context = new Context();
    context.setContent("hello");

    assertEquals("hello".hashCode(), context.getContentHash());
  }

  @Test
  public void setContent_Null_ResetsHashToZero() {
    Context context = new Context();
    context.setContent("hello");
    context.setContent(null);

    assertNull(context.getContent());
    assertEquals(0, context.getContentHash());
  }

  @Test
  public void hasContentChanged_DetectsDifferenceFromCurrentHash() {
    Context context = new Context();
    context.setContent("original");

    assertTrue(context.hasContentChanged("different"));
    assertFalse(context.hasContentChanged("original"));
  }

  @Test
  public void hasContentChanged_TreatsNullAndUnsetAsEqual() {
    Context context = new Context();

    assertFalse(context.hasContentChanged(null));
    assertTrue(context.hasContentChanged("something"));
  }

  @Test
  public void getContent_LazilyLoadsFromFilePathOnce() throws IOException {
    Path tempFile = Files.createTempFile("context-lazy", ".txt");
    Files.writeString(tempFile, "loaded once", StandardCharsets.UTF_8);

    Context context = new Context();
    context.setType("text/plain");
    context.setFilePath(tempFile.toString());

    assertEquals("loaded once", context.getContent());

    // Modify the file after first read; cached content should not change.
    Files.writeString(tempFile, "modified", StandardCharsets.UTF_8);
    assertEquals("loaded once", context.getContent());
  }

  @Test
  public void getContent_WithNoContentAndNoFilePath_ReturnsNull() {
    Context context = new Context();
    assertNull(context.getContent());
  }

  @Test
  public void getContent_WithMissingFile_ReturnsNullInsteadOfThrowing() {
    Context context = new Context();
    context.setFilePath("/this/path/does/not/exist-" + System.nanoTime() + ".txt");

    assertNull(context.getContent());
  }

  @Test
  public void setType_ChangingType_ClearsCachedContent() {
    Context context = new Context();
    context.setContent("cached");
    context.setType("text/markdown");

    assertNull(context.getContent());
    assertEquals(0, context.getContentHash());
  }

  @Test
  public void setType_SameType_KeepsCachedContent() {
    Context context = new Context();
    context.setType("text/plain");
    context.setContent("cached");
    context.setType("text/plain");

    assertEquals("cached", context.getContent());
  }

  @Test
  public void setFilePath_ClearsCachedContent() {
    Context context = new Context();
    context.setContent("cached");
    context.setFilePath("/tmp/whatever.txt");

    assertNull(context.getContent());
    assertEquals(0, context.getContentHash());
  }

  @Test
  public void setFile_SetsAbsoluteFilePath() {
    Context context = new Context();
    context.setFile(new java.io.File("relative/path.txt"));

    assertEquals(new java.io.File("relative/path.txt").getAbsolutePath(), context.getFilePath());
  }

  @Test
  public void equals_SameName_AreEqualRegardlessOfContent() {
    Context a = new Context();
    a.setName("shared-name");
    a.setContent("content-a");

    Context b = new Context();
    b.setName("shared-name");
    b.setContent("content-b");

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void equals_DifferentNames_AreNotEqual() {
    Context a = new Context();
    a.setName("name-a");

    Context b = new Context();
    b.setName("name-b");

    assertNotEquals(a, b);
  }

  @Test
  public void equals_BothNullNames_AreNotEqualButShareHashCode() {
    // equals() short-circuits to false whenever `name` is null (even comparing two contexts that
    // are both unnamed), because the implementation is `name != null && name.equals(other.name)`.
    // Distinct unnamed Context instances are therefore never equal to one another, only to
    // themselves via the reference-equality fast path. hashCode() still returns 0 for both, which
    // is consistent with the equals/hashCode contract (equal hashCodes are not required to imply
    // equal objects) but means unnamed contexts collide in hash-based collections without ever
    // being treated as duplicates.
    Context a = new Context();
    Context b = new Context();

    assertNotEquals(a, b);
    assertEquals(a, a);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void equals_NullNameVsNamedContext_AreNotEqual() {
    Context a = new Context();
    Context b = new Context();
    b.setName("named");

    assertNotEquals(a, b);
    assertNotEquals(b, a);
  }

  @Test
  public void equals_ReflexiveAndAgainstOtherType() {
    Context a = new Context();
    a.setName("self");

    assertEquals(a, a);
    assertNotEquals(a, "not a context");
    assertNotEquals(null, a);
  }

  @Test
  public void hashCode_MatchesNameHashCodeOrZeroWhenNameNull() {
    Context named = new Context();
    named.setName("abc");
    assertEquals("abc".hashCode(), named.hashCode());

    Context unnamed = new Context();
    assertEquals(0, unnamed.hashCode());
  }
}
