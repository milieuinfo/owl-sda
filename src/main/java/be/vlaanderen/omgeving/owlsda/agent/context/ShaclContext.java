package be.vlaanderen.omgeving.owlsda.agent.context;

import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import be.vlaanderen.omgeving.owlsda.ontology.Shacl.Shape;
import java.io.StringWriter;
import java.util.List;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;

public class ShaclContext extends Context {
  private final Shacl shacl;

  /**
   * Constructor for concurrent agents - each agent gets a range of shapes
   *
   * @param shacl The SHACL shapes
   * @param startIndex The start index of shapes to process (0-based, inclusive)
   * @param endIndex The end index of shapes to process (0-based, exclusive)
   * @param totalShapes Total number of shapes being processed in this batch
   */
  public ShaclContext(Shacl shacl, int startIndex, int endIndex, int totalShapes) {
    super();
    this.shacl = shacl;
    this.setType("text/turtle");
    int shapeCount = endIndex - startIndex;
    this.setName(
        String.format(
            "SHACL Shapes %d-%d/%d (%d shapes)",
            startIndex + 1, endIndex, totalShapes, shapeCount));
    this.setContent(getShapeRangeContext(startIndex, endIndex));
  }

  private String getShapeRangeContext(int startIndex, int endIndex) {
    List<Shape> shapes = shacl.getShapes().stream().filter(shape -> !shape.isProcessed()).toList();

    if (startIndex < 0 || endIndex > shapes.size() || startIndex >= endIndex) {
      return "# Invalid shape range: " + startIndex + " to " + endIndex;
    }

    Model rangeModel = ModelFactory.createDefaultModel();

    // Add shapes and their target classes
    for (int i = startIndex; i < endIndex; i++) {
      Shape shape = shapes.get(i);
      rangeModel.add(shape.getModel());
    }

    StringWriter rangeString = new StringWriter();
    rangeModel.write(rangeString, "TURTLE");
    return rangeString.toString();
  }
}
