package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link WorkerTripleStore#rewriteRelativeIrisAsCuries}, isolated from parsing/base
 * URI concerns (covered by {@link WorkerTripleStoreBaseUriTest}).
 */
public class WorkerTripleStoreRelativeIriRewriteTest {

  @Test
  public void bareIdentifier_IsRewrittenToDefaultPrefixCurie() {
    assertEquals(
        ":Operator-operator-002 <http://xmlns.com/foaf/0.1/name> \"MyGlass\" .",
        WorkerTripleStore.rewriteRelativeIrisAsCuries(
            "<Operator-operator-002> <http://xmlns.com/foaf/0.1/name> \"MyGlass\" ."));
  }

  @Test
  public void absoluteHttpIri_IsLeftUnchanged() {
    String turtle = "<https://example.org/id/proces/glass-proc-002> a :Process .";
    assertEquals(turtle, WorkerTripleStore.rewriteRelativeIrisAsCuries(turtle));
  }

  @Test
  public void urnIri_IsLeftUnchanged() {
    String turtle = "<urn:owlsda:no-default-namespace/Thing> a :Thing .";
    assertEquals(turtle, WorkerTripleStore.rewriteRelativeIrisAsCuries(turtle));
  }

  @Test
  public void relativeIriWithSlash_IsLeftAsIriref() {
    // Rewriting to a CURIE would produce an invalid PN_LOCAL (unescaped '/' not allowed), turning
    // a parseable-if-wrong IRI into an unparseable one - must be left alone instead.
    String turtle = "<foo/bar> a :Thing .";
    assertEquals(turtle, WorkerTripleStore.rewriteRelativeIrisAsCuries(turtle));
  }

  @Test
  public void contentInsideStringLiteral_IsNotTreatedAsAnIri() {
    String turtle = "<https://example.org/x> rdfs:label \"looks like <a-tag> in text\" .";
    assertEquals(turtle, WorkerTripleStore.rewriteRelativeIrisAsCuries(turtle));
  }

  @Test
  public void multipleRelativeIrisInOneDocument_AreAllRewritten() {
    String input = "<subj-001> <http://xmlns.com/foaf/0.1/knows> <subj-002> .";
    String expected = ":subj-001 <http://xmlns.com/foaf/0.1/knows> :subj-002 .";
    assertEquals(expected, WorkerTripleStore.rewriteRelativeIrisAsCuries(input));
  }
}
