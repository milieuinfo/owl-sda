package be.vlaanderen.omgeving.owlsda.ontology;

import java.io.FileOutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
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

/**
 * SHACL shapes for one ontology: loads shapes from a file, or {@link #generate()}s one shape per
 * ontology class when none is supplied, and validates RDF data against them via {@link
 * #validate(Model)}. Each shape is tracked as a {@link Shacl.Shape}, whose {@link Shacl.Shape#isProcessed()}
 * flag records whether the generation workflow considers it complete; that flag is mutated from
 * both {@code generation.Supervisor} (single-threaded, between rounds) and read by workers running
 * in parallel during a round, so callers must not mutate it concurrently with an in-progress {@link
 * be.vlaanderen.omgeving.owlsda.generation.ConcurrentWorkerBatch} round.
 */
@Getter
public class Shacl {

  private static final String SH = "http://www.w3.org/ns/shacl#";
  private final Logger logger = LoggerFactory.getLogger(Shacl.class);
  @Setter private Model model;
  @Setter private Model ontology;
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

  /**
   * Returns the ontology's primary (default {@code :}) namespace IRI, e.g. {@code
   * https://example.org/ns/riepr#}. Used to remind generation workers which exact namespace new
   * instances must be typed in, since models otherwise tend to invent an unrelated placeholder
   * namespace (like {@code http://example.org/}) that never matches any shape's target class.
   * Returns null if the ontology declares no default prefix.
   */
  public String resolvePrimaryNamespace() {
    if (ontology == null) {
      return null;
    }
    String defaultNamespace = ontology.getNsPrefixURI("");
    return (defaultNamespace == null || defaultNamespace.isBlank()) ? null : defaultNamespace;
  }

  /**
   * Collects every class URI this ontology/SHACL model considers legitimate: classes explicitly
   * declared as owl:Class/rdfs:Class in the ontology, classes referenced only as an rdfs:subClassOf
   * object (covers externally-imported vocabulary classes used purely as a parent, e.g. prov:Agent,
   * foaf:Person), and every shape's sh:targetClass. Used by {@link #findUnknownClasses(Model)} to
   * flag instances typed with an invented or namespace-mismatched class, which would otherwise
   * evade every SHACL shape silently - no shape targets an unknown class, so validation never
   * reports anything wrong with it.
   */
  public Set<String> collectKnownClassUris() {
    Set<String> known = new HashSet<>();
    if (ontology != null) {
      ontology
          .listSubjectsWithProperty(RDF.type, OWL.Class)
          .forEachRemaining(r -> addUri(known, r));
      ontology
          .listSubjectsWithProperty(RDF.type, RDFS.Class)
          .forEachRemaining(r -> addUri(known, r));
      ontology
          .listObjectsOfProperty(RDFS.subClassOf)
          .forEachRemaining(
              node -> {
                if (node.isURIResource()) {
                  addUri(known, node.asResource());
                }
              });
    }
    for (Shape shape : shapes) {
      collectTargetClassUri(shape, known);
    }
    return known;
  }

  /**
   * Finds distinct rdf:type values used in {@code data} that are not part of {@link
   * #collectKnownClassUris()}. A non-empty result means some instances are typed with an invented
   * or namespace-mismatched class - real SHACL validation reports zero violations for these
   * instances not because they conform, but because no shape targets them at all.
   */
  public Set<String> findUnknownClasses(Model data) {
    Set<String> known = collectKnownClassUris();
    Set<String> unknown = new HashSet<>();
    data.listStatements(null, RDF.type, (RDFNode) null)
        .forEachRemaining(
            stmt -> {
              RDFNode object = stmt.getObject();
              if (object.isURIResource() && !known.contains(object.asResource().getURI())) {
                unknown.add(object.asResource().getURI());
              }
            });
    return unknown;
  }

  /**
   * Collects every predicate URI this ontology/SHACL model considers legitimate: properties
   * referenced via owl:onProperty in the ontology's class restrictions, every shape's sh:path
   * value, and properties explicitly typed owl:ObjectProperty/owl:DatatypeProperty/rdf:Property.
   * Also allows a small set of structural predicates every instance may reasonably use (rdf:type,
   * rdfs:label, rdfs:comment). Used by {@link #findUnknownPredicates(Model)} to flag invented
   * predicates in a placeholder namespace instead of reusing existing ontology vocabulary.
   */
  public Set<String> collectKnownPropertyUris() {
    Set<String> known = new HashSet<>();
    if (ontology != null) {
      ontology
          .listSubjectsWithProperty(RDF.type, OWL.ObjectProperty)
          .forEachRemaining(r -> addUri(known, r));
      ontology
          .listSubjectsWithProperty(RDF.type, OWL.DatatypeProperty)
          .forEachRemaining(r -> addUri(known, r));
      ontology
          .listSubjectsWithProperty(RDF.type, RDF.Property)
          .forEachRemaining(r -> addUri(known, r));
      ontology
          .listObjectsOfProperty(OWL.onProperty)
          .forEachRemaining(
              node -> {
                if (node.isURIResource()) {
                  addUri(known, node.asResource());
                }
              });
    }
    for (Shape shape : shapes) {
      collectShapePathUris(shape, known);
    }
    known.add(RDF.type.getURI());
    known.add(RDFS.label.getURI());
    known.add(RDFS.comment.getURI());
    return known;
  }

  /**
   * Finds distinct predicate URIs used in {@code data} that are not part of {@link
   * #collectKnownPropertyUris()}. A non-empty result means some triples use an invented predicate
   * instead of reusing an existing ontology/vocabulary property.
   */
  public Set<String> findUnknownPredicates(Model data) {
    Set<String> known = collectKnownPropertyUris();
    Set<String> unknown = new HashSet<>();
    data.listStatements()
        .forEachRemaining(
            stmt -> {
              String uri = stmt.getPredicate().getURI();
              if (uri != null && !known.contains(uri)) {
                unknown.add(uri);
              }
            });
    return unknown;
  }

  private void collectShapePathUris(Shape shape, Set<String> known) {
    Model shapeModel = shape.getModel();
    if (shapeModel == null) {
      return;
    }
    shapeModel
        .listObjectsOfProperty(shapeModel.createProperty(SH + "path"))
        .forEachRemaining(
            node -> {
              if (node.isURIResource()) {
                addUri(known, node.asResource());
              }
            });
  }

  private void collectTargetClassUri(Shape shape, Set<String> known) {
    Model shapeModel = shape.getModel();
    if (shapeModel == null) {
      return;
    }
    Resource nodeShapeType = shapeModel.createResource(SH + "NodeShape");
    var shapeResources = shapeModel.listResourcesWithProperty(RDF.type, nodeShapeType);
    if (!shapeResources.hasNext()) {
      return;
    }
    Statement targetClassStmt =
        shapeResources.nextResource().getProperty(shapeModel.createProperty(SH + "targetClass"));
    if (targetClassStmt != null && targetClassStmt.getObject().isResource()) {
      addUri(known, targetClassStmt.getResource());
    }
  }

  private static void addUri(Set<String> set, Resource resource) {
    String uri = resource.getURI();
    if (uri != null && !uri.isBlank()) {
      set.add(uri);
    }
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
    shapes.clear();

    // Collect all NodeShape resources and extract each shape's model
    Iterator<Resource> it =
        model.listResourcesWithProperty(RDF.type, model.createResource(SH + "NodeShape"));
    while (it.hasNext()) {
      Resource shapeRes = it.next();
      Model shapeModel = ModelFactory.createDefaultModel();
      shapeModel.setNsPrefixes(model.getNsPrefixMap());
      extractShapeWithBlankNodes(shapeRes, shapeModel);
      shapes.add(new Shape(shapeModel));
    }
    // Sort shapes by constraint count (most constrained first)
    shapes.sort(
        (s1, s2) -> {
          long count1 = s1.getModel().listStatements(null, null, (RDFNode) null).toList().size();
          long count2 = s2.getModel().listStatements(null, null, (RDFNode) null).toList().size();
          return Long.compare(count2, count1);
        });
    jenaShapes = Shapes.parse(model);
    logger.info("Loaded {} SHACL shapes", shapes.size());
  }

  /**
   * Recursively extracts all statements related to a resource, including nested blank nodes. This
   * ensures that the entire shape structure (property shapes, constraints, etc.) is captured.
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

  /** Generate SHACL shapes from the ontology */
  public void generate() {
    Iterator<Resource> classes = ontology.listResourcesWithProperty(RDF.type, OWL.Class);
    while (classes.hasNext()) {
      Resource cls = classes.next();
      if (!cls.isURIResource()) {
        continue;
      }
      logger.info("Generating SHACL shape for class {}", cls.getURI());
      Shape shape = new Shape(cls, ontology);
      model
          .getNsPrefixMap()
          .forEach((prefix, value) -> shape.getModel().setNsPrefix(prefix, value));
      model.add(shape.getModel());
    }
    refresh();
  }

  private Property shaclProp(String local) {
    return model.createProperty(SH + local);
  }

  private boolean isDatatype(Resource res) {
    return res != null
        && res.isURIResource()
        && res.getURI().startsWith("http://www.w3.org/2001/XMLSchema#");
  }

  @Getter
  public class Shape {

    private final Model model;
    private final String name;
    private final String comment;
    @Setter private boolean processed = false;

    public Shape(Model model) {
      this.model = model;
      Resource nodeShape = model.createResource(SH + "NodeShape");
      Resource shape = model.listResourcesWithProperty(RDF.type, nodeShape).toList().getFirst();
      name = shape.getLocalName();
      // Get rdfs comment from shape
      comment =
          shape.hasProperty(RDFS.comment) ? shape.getProperty(RDFS.comment).getString() : null;
    }

    public Shape(Resource cls, Model ontology) {
      this.model = ModelFactory.createDefaultModel();
      Resource ns = model.createResource(cls.getURI() + "Shape");
      ns.addProperty(RDF.type, model.createResource(SH + "NodeShape"));
      ns.addProperty(shaclProp("targetClass"), cls);
      name = cls.getLocalName();

      // Get comment from ontology targetClass (cls)
      comment = cls.hasProperty(RDFS.comment) ? cls.getProperty(RDFS.comment).getString() : null;
      if (comment != null) {
        ns.addProperty(RDFS.comment, comment);
      }

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

    /** Get this shape as TURTLE string. */
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
