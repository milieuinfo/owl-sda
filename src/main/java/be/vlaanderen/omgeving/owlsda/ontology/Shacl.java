package be.vlaanderen.omgeving.owlsda.ontology;

import java.io.FileOutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.apache.jena.rdf.model.Literal;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Getter
public class Shacl {

  private static final String SH = "http://www.w3.org/ns/shacl#";
  private final Logger logger = LoggerFactory.getLogger(Shacl.class);
  @Getter
  @Setter
  private Model model;
  @Getter
  @Setter
  private Model ontology;
  private final List<Shape> shapes = new ArrayList<>();
  private Shapes jenaShapes;

  public Shacl(Model ontology) {
    this.ontology = ontology;
    this.model = ModelFactory.createDefaultModel();
    model.setNsPrefix("sh", SH);
    model.setNsPrefix("owl", OWL.NS);
    model.setNsPrefix("rdfs", RDFS.getURI());
    ontology.getNsPrefixMap().forEach(model::setNsPrefix);
  }

  public Shacl(Model ontology, Model shacl) {
    this(ontology);
    this.model = shacl;
  }

  public static Shacl fromOntology(Model ontology) {
    return new Shacl(ontology);
  }

  public ValidationReport validate(Model data) {
    ValidationReport report = ShaclValidator.get().validate(getJenaShapes(), data.getGraph());
    if (report.conforms()) {
      logger.debug("Ontology conforms to SHACL shapes");
    } else {
      logger.debug("Ontology does NOT conform to SHACL shapes");
    }
    return report;
  }

  private void refresh() {
    // Get all shapes and store in list
    Iterator<Resource> it = model.listResourcesWithProperty(RDF.type,
        model.createResource(SH + "NodeShape"));
    while (it.hasNext()) {
      Resource shapeRes = it.next();
      // Extract all properties of the shapeRes from 'model', including nested blank nodes
      Model shapeModel = ModelFactory.createDefaultModel();
      shapeModel.setNsPrefixes(model.getNsPrefixMap());
      extractShapeWithBlankNodes(shapeRes, shapeModel);
      shapes.add(new Shape(shapeModel));
    }
    // Sort the shapes by the one with the most constraints)
    shapes.sort((s1, s2) -> {
      long count1 = s1.getModel().listStatements(null, null, (RDFNode) null).toList().size();
      long count2 = s2.getModel().listStatements(null, null, (RDFNode) null).toList().size();
      return Long.compare(count2, count1);
    });
    // Log the shapes
    jenaShapes = Shapes.parse(model);
    logger.info("Loaded {} SHACL shapes:", shapes.size());
  }

  /**
   * Recursively extracts all statements related to a resource, including nested blank nodes.
   * This ensures that the entire shape structure (property shapes, constraints, etc.) is captured.
   */
  private void extractShapeWithBlankNodes(Resource resource, Model targetModel) {
    // Add all statements where this resource is the subject
    List<Statement> statements = model.listStatements(resource, null, (RDFNode) null).toList();
    targetModel.add(statements);

    // Recursively process objects that are blank nodes
    for (Statement stmt : statements) {
      RDFNode object = stmt.getObject();
      if (object.isResource() && object.asResource().isAnon()) {
        // Only traverse if we haven't already added this blank node
        if (!targetModel.contains(object.asResource(), null, (RDFNode) null)) {
          extractShapeWithBlankNodes(object.asResource(), targetModel);
        }
      }
    }
  }

  public void load(String filePath, boolean refresh) {
    try {
      model.read(filePath);
      jenaShapes = Shapes.parse(model);
      if (refresh) {
        refresh();
      }
    } catch (Exception e) {
      logger.error("Failed to load SHACL shapes from file: {}", filePath, e);
    }
  }

  public void save(String filePath) {
    // Ensure parent directories exist
    try {
      Path outputPath = Paths.get(filePath);
      Files.createDirectories(outputPath.getParent());
    } catch (Exception e) {
      logger.error("Failed to create directories for file path: {}", filePath, e);
      return;
    }
    try (FileOutputStream out = new FileOutputStream(filePath)) {
      model.write(out, "TURTLE");
    } catch (Exception e) {
      logger.error("Failed to save SHACL shapes to file: {}", filePath, e);
    }
  }

  public String getTurtle() {
    StringWriter stringWriter = new StringWriter();
    model.write(stringWriter, "TURTLE");
    return stringWriter.toString();
  }

  /**
   * Generate SHACL shapes from the ontology
   */
  public void generate() {
    Iterator<Resource> classes = ontology.listResourcesWithProperty(RDF.type, OWL.Class);
    while (classes.hasNext()) {
      Resource cls = classes.next();
      if (!cls.isURIResource()) {
        continue;
      }
      logger.info("Generating SHACL shape for class {}", cls.getURI());
      Shape shape = new Shape(cls, ontology);
      model.getNsPrefixMap().forEach((prefix, value) ->
          shape.getModel().setNsPrefix(prefix, value));
      model.add(shape.getModel());
    }
    refresh();
  }

  private Property shaclProp(String local) {
    return model.createProperty(SH + local);
  }

  private boolean isDatatype(Resource res) {
    return res != null && res.isURIResource() && res.getURI()
        .startsWith("http://www.w3.org/2001/XMLSchema#");
  }

  @Getter
  public class Shape {

    private final Model model;
    private final String name;
    private final String comment;
    @Setter
    private boolean processed = false;

    public Shape(Model model) {
      this.model = model;
      Resource nodeShape = model.createResource(SH + "NodeShape");
      Resource shape = model.listResourcesWithProperty(RDF.type,
          nodeShape).toList().getFirst();
      name = shape.getLocalName();
      // Get rdfs comment from shape
      comment = shape.hasProperty(RDFS.comment) ? shape.getProperty(RDFS.comment).getString() : null;
    }

    public Shape(Resource cls, Model ontology) {
      this.model = ModelFactory.createDefaultModel();
      Resource ns = model.createResource(cls.getURI() + "Shape");
      ns.addProperty(RDF.type, model.createResource(SH + "NodeShape"));
      ns.addProperty(shaclProp("targetClass"), cls);
      name = cls.getLocalName();

      // Get comment from ontology targetClass (cls)
      comment = cls.hasProperty(RDFS.comment) ? cls.getProperty(RDFS.comment).getString() : null;

      // find rdfs:subClassOf values that are OWL.Restriction
      Iterator<Statement> it = ontology.listStatements(cls, RDFS.subClassOf, (RDFNode) null);
      while (it.hasNext()) {
        Statement st = it.next();
        RDFNode obj = st.getObject();
        if (obj.isResource()) {
          Resource r = obj.asResource();
          if (r.hasProperty(RDF.type, OWL.Restriction)) {
            generatePropertyShape(r, ns);
          }
        }
      }
    }

    /**
     * Get this shape as TURTLE string.
     */
    public String getTurtle() {
      StringWriter stringWriter = new StringWriter();
      model.write(stringWriter, "TURTLE");
      return stringWriter.toString();
    }

    private void addComment(Resource from, Resource to) {
      Statement commentStmt = from.getProperty(RDFS.comment);
      if (commentStmt != null && commentStmt.getObject().isLiteral()) {
        to.addProperty(model.createProperty(SH + "description"), commentStmt.getLiteral());
      }
    }

    private Integer intValue(Resource res, Property p) {
      Statement s = res.getProperty(p);
      if (s == null) {
        return null;
      }
      RDFNode node = s.getObject();
      if (node.isLiteral()) {
        try {
          return ((Literal) node).getInt();
        } catch (Exception e) {
          return null;
        }
      }
      return null;
    }

    private void addCardinality(Resource restriction, Resource ps) {
      Integer min = intValue(restriction, OWL.minCardinality);
      Integer max = intValue(restriction, OWL.maxCardinality);
      Integer exact = intValue(restriction, OWL.cardinality);

      if (min != null) {
        ps.addLiteral(shaclProp("minCount"), min);
      }
      if (max != null) {
        ps.addLiteral(shaclProp("maxCount"), max);
      }
      if (exact != null) {
        ps.addLiteral(shaclProp("minCount"), exact);
        ps.addLiteral(shaclProp("maxCount"), exact);
      }
    }

    private void addMinCountIfNeeded(Resource restriction, Resource ps) {
      if (restriction.hasProperty(OWL.someValuesFrom)
          && !restriction.hasProperty(OWL.minCardinality)
          && !restriction.hasProperty(OWL.cardinality)) {
        ps.addLiteral(shaclProp("minCount"), 1);
      }
    }

    private RDFNode createOrList(Resource unionNode) {
      Resource nodeWithUnion = unionNode;
      Statement unionStmt = unionNode.getProperty(OWL.unionOf);
      if (unionStmt != null && unionStmt.getObject().isResource()) {
        nodeWithUnion = unionStmt.getResource();
      }
      if (nodeWithUnion.canAs(RDFList.class)) {
        RDFList list = nodeWithUnion.as(RDFList.class);
        List<RDFNode> shapes = new ArrayList<>();
        Iterator<RDFNode> it = list.iterator();
        while (it.hasNext()) {
          RDFNode member = it.next();
          if (member.isResource()) {
            Resource ps = Shacl.this.model.createResource();
            addClassOrDatatype(ps, member.asResource());
            shapes.add(ps);
          }
        }
        return Shacl.this.model.createList(shapes.iterator());
      }
      return Shacl.this.model.createResource();
    }

    private void addClassOrDatatype(Resource ps, Resource value) {
      if (isDatatype(value)) {
        ps.addProperty(shaclProp("datatype"), value);
      } else {
        ps.addProperty(shaclProp("class"), value);
      }
    }

    private RDFNode createPath(Resource prop) {
      Statement invStmt = prop.getProperty(OWL.inverseOf);
      if (invStmt != null && invStmt.getObject().isResource()) {
        Resource inv = invStmt.getResource();
        Resource b = Shacl.this.model.createResource();
        b.addProperty(shaclProp("inversePath"), inv);
        return b;
      }
      // otherwise use the property URI
      if (prop.isURIResource()) {
        return Shacl.this.model.createResource(prop.getURI());
      }
      return Shacl.this.model.createResource();
    }

    private void generatePropertyShape(Resource restriction, Resource nodeShape) {
      Resource onProp = restriction.getPropertyResourceValue(OWL.onProperty);
      if (onProp == null) {
        return;
      }

      Resource ps = model.createResource();
      ps.addProperty(shaclProp("path"), createPath(onProp));

      Resource some = restriction.getPropertyResourceValue(OWL.someValuesFrom);
      Resource all = restriction.getPropertyResourceValue(OWL.allValuesFrom);

      if (some != null) {
        if (some.hasProperty(OWL.unionOf)) {
          ps.addProperty(shaclProp("or"), createOrList(some));
        } else {
          addClassOrDatatype(ps, some);
        }
      }

      if (all != null) {
        if (all.hasProperty(OWL.unionOf)) {
          ps.addProperty(shaclProp("or"), createOrList(all));
        } else {
          addClassOrDatatype(ps, all);
        }
      }

      addMinCountIfNeeded(restriction, ps);
      addCardinality(restriction, ps);
      addComment(restriction, ps);

      nodeShape.addProperty(shaclProp("property"), ps);
    }
  }

}
