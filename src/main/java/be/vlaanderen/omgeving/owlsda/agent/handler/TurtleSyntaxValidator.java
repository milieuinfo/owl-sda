package be.vlaanderen.omgeving.owlsda.agent.handler;

import java.io.StringReader;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

/**
 * Validates that a Turtle string parses cleanly before it is written to the output file. Used by
 * the supervisor's direct file-write tools, which operate on raw text and would otherwise let
 * syntactically invalid Turtle (e.g. malformed blank node syntax) reach disk.
 */
final class TurtleSyntaxValidator {

  private TurtleSyntaxValidator() {}

  /**
   * @return null if the content is valid Turtle, otherwise a human-readable parse error
   */
  static String validate(String turtleContent) {
    try {
      RDFDataMgr.read(
          ModelFactory.createDefaultModel(), new StringReader(turtleContent), null, Lang.TURTLE);
      return null;
    } catch (Exception e) {
      return e.getMessage();
    }
  }
}
