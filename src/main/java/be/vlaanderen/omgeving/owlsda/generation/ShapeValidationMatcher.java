package be.vlaanderen.omgeving.owlsda.generation;

import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.apache.jena.graph.Node;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

final class ShapeValidationMatcher {

  private static final String SH_NODE_SHAPE = "http://www.w3.org/ns/shacl#NodeShape";
  private static final String SH_TARGET_CLASS = "http://www.w3.org/ns/shacl#targetClass";

  private ShapeValidationMatcher() {
  }

  static Optional<String> getTargetClassUri(Shacl.Shape shape) {
    try {
      Model shapeModel = shape.getModel();
      if (shapeModel == null) {
        return Optional.empty();
      }

      Resource nodeShapeType = shapeModel.createResource(SH_NODE_SHAPE);
      var shapeResources = shapeModel.listResourcesWithProperty(RDF.type, nodeShapeType);
      if (!shapeResources.hasNext()) {
        return Optional.empty();
      }
      Resource shapeResource = shapeResources.nextResource();

      Property targetClassProperty = shapeModel.createProperty(SH_TARGET_CLASS);
      Statement targetClassStmt = shapeResource.getProperty(targetClassProperty);
      if (targetClassStmt == null || !targetClassStmt.getObject().isResource()) {
        return Optional.empty();
      }

      String targetClassUri = targetClassStmt.getResource().getURI();
      return (targetClassUri == null || targetClassUri.isBlank())
          ? Optional.empty()
          : Optional.of(targetClassUri);
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  static boolean hasInstancesForTargetClass(Model dataModel, Model ontologyModel, Property rdfType,
      Resource targetClass) {
    if (dataModel.contains(null, rdfType, targetClass)) {
      return true;
    }

    return dataModel.listStatements(null, rdfType, (Resource) null)
        .toList()
        .stream()
        .map(Statement::getObject)
        .filter(RDFNode::isResource)
        .map(RDFNode::asResource)
        .anyMatch(actualType -> isClassCompatible(actualType, targetClass, ontologyModel));
  }

  static boolean hasViolationsForTargetClass(Model dataModel, Model ontologyModel,
      ValidationReport report, Resource targetClass) {
    for (var entry : report.getEntries()) {
      Node focusNode = entry.focusNode();
      if (focusNode == null) {
        continue;
      }
      if (isFocusNodeOfTargetClass(dataModel, ontologyModel, focusNode, targetClass)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isFocusNodeOfTargetClass(Model dataModel, Model ontologyModel,
      Node focusNode, Resource targetClass) {
    RDFNode rdfNode = dataModel.asRDFNode(focusNode);
    if (!rdfNode.isResource()) {
      return false;
    }

    Resource focusResource = rdfNode.asResource();
    if (dataModel.contains(focusResource, RDF.type, targetClass)) {
      return true;
    }

    return dataModel.listStatements(focusResource, RDF.type, (Resource) null)
        .toList()
        .stream()
        .map(Statement::getObject)
        .filter(RDFNode::isResource)
        .map(RDFNode::asResource)
        .anyMatch(actualType -> isClassCompatible(actualType, targetClass, ontologyModel));
  }

  private static boolean isClassCompatible(Resource actualType, Resource expectedType,
      Model ontologyModel) {
    String expectedUri = expectedType.getURI();
    String actualUri = actualType.getURI();
    if (expectedUri != null && expectedUri.equals(actualUri)) {
      return true;
    }

    String expectedLocalName = expectedType.getLocalName();
    if (expectedLocalName != null && !expectedLocalName.isBlank()
        && expectedLocalName.equals(actualType.getLocalName())) {
      return true;
    }

    if (ontologyModel == null) {
      return false;
    }

    // Accept matches across subclass hierarchies in either direction.
    return isSubclassOf(ontologyModel, actualType, expectedType)
        || isSubclassOf(ontologyModel, expectedType, actualType);
  }

  private static boolean isSubclassOf(Model ontologyModel, Resource child, Resource parent) {
    if (child == null || parent == null || !child.isURIResource() || !parent.isURIResource()) {
      return false;
    }
    if (child.equals(parent)) {
      return true;
    }

    Set<String> visited = new HashSet<>();
    ArrayDeque<Resource> queue = new ArrayDeque<>();
    queue.add(child);

    while (!queue.isEmpty()) {
      Resource current = queue.removeFirst();
      String currentUri = current.getURI();
      if (currentUri == null || !visited.add(currentUri)) {
        continue;
      }

      var directParents = ontologyModel.listObjectsOfProperty(current, RDFS.subClassOf);
      while (directParents.hasNext()) {
        RDFNode parentNode = directParents.next();
        if (!parentNode.isResource()) {
          continue;
        }

        Resource parentResource = parentNode.asResource();
        if (parentResource.equals(parent)) {
          return true;
        }

        if (parentResource.isURIResource()) {
          queue.addLast(parentResource);
        }
      }
    }

    return false;
  }
}
