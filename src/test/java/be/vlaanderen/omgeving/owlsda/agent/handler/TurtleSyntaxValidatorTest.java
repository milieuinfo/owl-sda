package be.vlaanderen.omgeving.owlsda.agent.handler;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for the package-private {@link TurtleSyntaxValidator}. */
public class TurtleSyntaxValidatorTest {

  @Test
  public void validate_ValidTurtle_ReturnsNull() {
    String turtle =
        """
        @prefix ex: <http://example.org/> .

        ex:Person1 a ex:Person ;
          ex:name "Alice" .
        """;

    assertNull(TurtleSyntaxValidator.validate(turtle));
  }

  @Test
  public void validate_EmptyDocument_ReturnsNull() {
    assertNull(TurtleSyntaxValidator.validate(""));
  }

  @Test
  public void validate_InvalidTurtle_ReturnsNonNullErrorMessage() {
    String invalidTurtle = "@prefix ex: <http://example.org/> .\nex:Person1 ex:name \"unterminated";

    String error = TurtleSyntaxValidator.validate(invalidTurtle);

    assertNotNull(error);
    assertTrue(error.length() > 0);
  }

  @Test
  public void validate_MalformedBlankNodeSyntax_ReturnsNonNullErrorMessage() {
    String invalidTurtle = "@prefix ex: <http://example.org/> .\nex:Person1 ex:knows [ .";

    assertNotNull(TurtleSyntaxValidator.validate(invalidTurtle));
  }
}
