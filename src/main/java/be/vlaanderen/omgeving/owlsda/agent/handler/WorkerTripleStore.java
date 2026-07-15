package be.vlaanderen.omgeving.owlsda.agent.handler;

import be.vlaanderen.omgeving.owlsda.ontology.Shacl;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared triple store for all workers to collaborate on RDF triple generation. */
public class WorkerTripleStore {
  private static final Logger logger = LoggerFactory.getLogger(WorkerTripleStore.class);

  private final Model model;
  private final String outputPath;
  private boolean dirty;
  // Bumped on every successful mutation; used to cache SHACL validation results so repeated
  // validation calls within the same round (no intervening mutation) don't re-copy the model or
  // re-run validation.
  private long version;
  private ValidationSnapshot cachedValidation;

  /**
   * Create a triple store that writes triples to an output file as they are added.
   *
   * @param outputPath Path to write triples to (in TURTLE format)
   */
  public WorkerTripleStore(String outputPath) {
    this.model = ModelFactory.createDefaultModel();
    this.outputPath = outputPath;
    this.dirty = false;
  }

  /**
   * Add triples from TURTLE data to the shared store. Returns information about duplicates to help
   * workers coordinate. If an output path is configured, writes the complete merged model to the
   * output file.
   *
   * @param turtleData TURTLE formatted data to add
   * @param workerId ID of the worker adding the triples
   * @return Result containing triples added, duplicates detected, and any errors
   */
  public synchronized AddResult addTriples(String turtleData, String workerId) {
    try {
      Model tempModel = ModelFactory.createDefaultModel();
      RDFDataMgr.read(tempModel, new StringReader(turtleData), null, Lang.TURTLE);

      int sizeBefore = (int) model.size();

      // Check for duplicates before adding
      List<Statement> duplicates = new ArrayList<>();
      StmtIterator iter = tempModel.listStatements();
      while (iter.hasNext()) {
        Statement stmt = iter.nextStatement();
        if (model.contains(stmt)) {
          duplicates.add(stmt);
        }
      }

      // Add all triples (Jena handles duplicates automatically)
      model.add(tempModel);
      int triplesAdded = (int) model.size() - sizeBefore;

      logger.info(
          "[{}] Added {} triples, {} duplicates ignored (total: {})",
          workerId,
          triplesAdded,
          duplicates.size(),
          model.size());

      if (triplesAdded > 0) {
        dirty = true;
        version++;
      }

      return new AddResult(triplesAdded, duplicates, (int) model.size());
    } catch (Exception e) {
      logger.error("[{}] Failed to add triples: {}", workerId, e.getMessage());
      return AddResult.error("Invalid TURTLE data: " + e.getMessage());
    }
  }

  /**
   * Remove specific triples from the shared store.
   *
   * @param subject Subject URI or null for wildcard
   * @param predicate Predicate URI or null for wildcard
   * @param object Object URI/literal or null for wildcard
   * @param workerId ID of the worker removing the triples
   * @return Number of triples removed
   */
  public synchronized int removeTriples(
      String subject, String predicate, String object, String workerId) {
    int sizeBefore = (int) model.size();

    Resource subjectRes = subject != null ? model.getResource(subject) : null;
    Property predicateRes = predicate != null ? model.getProperty(predicate) : null;
    RDFNode objectNode = object != null ? parseObject(object) : null;

    model.removeAll(subjectRes, predicateRes, objectNode);

    int triplesRemoved = sizeBefore - (int) model.size();
    if (triplesRemoved > 0) {
      dirty = true;
      version++;
    }
    logger.info("[{}] Removed {} triples (total: {})", workerId, triplesRemoved, model.size());
    return triplesRemoved;
  }

  /**
   * Remove triples from the shared store and recursively remove any orphaned blank nodes. This
   * prevents floating blank nodes that are no longer referenced by any named resource.
   *
   * @param subject Subject URI or null for wildcard
   * @param predicate Predicate URI or null for wildcard
   * @param object Object URI/literal or null for wildcard
   * @param workerId ID of the worker removing the triples
   * @return Number of triples removed (including cascaded blank node triples)
   */
  public synchronized int removeTriplesWithBlankNodes(
      String subject, String predicate, String object, String workerId) {
    int sizeBefore = (int) model.size();

    Resource subjectRes = subject != null ? model.getResource(subject) : null;
    Property predicateRes = predicate != null ? model.getProperty(predicate) : null;
    RDFNode objectNode = object != null ? parseObject(object) : null;

    // Collect blank nodes that will be affected by this removal
    List<Resource> blankNodesToCheck = new ArrayList<>();

    StmtIterator stmtIter = model.listStatements(subjectRes, predicateRes, objectNode);
    while (stmtIter.hasNext()) {
      Statement stmt = stmtIter.next();

      // If the subject being removed is a blank node, track it
      if (stmt.getSubject().isAnon()) {
        blankNodesToCheck.add(stmt.getSubject());
      }

      // If the object is a blank node, track it
      if (stmt.getObject().isResource() && stmt.getObject().asResource().isAnon()) {
        blankNodesToCheck.add(stmt.getObject().asResource());
      }
    }

    // Remove the matched triples
    model.removeAll(subjectRes, predicateRes, objectNode);

    // Recursively remove orphaned blank nodes
    int cascadedRemovals = removeOrphanedBlankNodes(blankNodesToCheck, workerId);

    int totalRemoved = sizeBefore - (int) model.size();
    if (totalRemoved > 0) {
      dirty = true;
      version++;
    }

    if (cascadedRemovals > 0) {
      logger.info(
          "[{}] Removed {} triples ({} direct, {} from orphaned blank nodes) (total: {})",
          workerId,
          totalRemoved,
          totalRemoved - cascadedRemovals,
          cascadedRemovals,
          model.size());
    } else {
      logger.info("[{}] Removed {} triples (total: {})", workerId, totalRemoved, model.size());
    }

    return totalRemoved;
  }

  /**
   * Recursively remove blank nodes that are no longer referenced by any named resource. A blank
   * node is considered orphaned if: - It has no incoming references from named resources - It has
   * no outgoing references to named resources (except through other blank nodes)
   *
   * @param blankNodesToCheck List of blank nodes to check for orphan status
   * @param workerId ID of the worker performing the cleanup
   * @return Number of triples removed from orphaned blank nodes
   */
  private int removeOrphanedBlankNodes(List<Resource> blankNodesToCheck, String workerId) {
    int removedCount = 0;
    boolean changed = true;

    while (changed) {
      changed = false;
      List<Resource> orphanedNodes = new ArrayList<>();

      for (Resource blankNode : blankNodesToCheck) {
        if (!blankNode.isAnon()) {
          continue; // Skip if not a blank node
        }

        // Check if this blank node is still in the model
        if (!model.containsResource(blankNode)) {
          continue;
        }

        // Check if blank node is referenced by any named resource
        boolean hasNamedReference = false;
        StmtIterator incomingIter = model.listStatements(null, null, blankNode);
        while (incomingIter.hasNext()) {
          Statement stmt = incomingIter.next();
          if (!stmt.getSubject().isAnon()) {
            hasNamedReference = true;
            break;
          }
        }

        // If no named resource references this blank node, it's orphaned
        if (!hasNamedReference) {
          orphanedNodes.add(blankNode);
        }
      }

      // Remove all triples involving orphaned blank nodes
      for (Resource orphan : orphanedNodes) {
        // Collect new blank nodes to check (objects of the orphan)
        List<Resource> newBlankNodesToCheck = new ArrayList<>();
        StmtIterator orphanStmts = model.listStatements(orphan, null, (RDFNode) null);
        while (orphanStmts.hasNext()) {
          Statement stmt = orphanStmts.next();
          if (stmt.getObject().isResource() && stmt.getObject().asResource().isAnon()) {
            newBlankNodesToCheck.add(stmt.getObject().asResource());
          }
        }

        // Remove all triples with orphan as subject
        int beforeSize = (int) model.size();
        model.removeAll(orphan, null, null);

        // Remove all triples with orphan as object
        model.removeAll(null, null, orphan);

        int removed = beforeSize - (int) model.size();
        removedCount += removed;

        if (removed > 0) {
          changed = true;
          logger.debug(
              "[{}] Removed {} triples from orphaned blank node {}",
              workerId,
              removed,
              orphan.getId());
        }

        // Add newly exposed blank nodes to the check list
        blankNodesToCheck.addAll(newBlankNodesToCheck);
      }

      // Remove already processed nodes
      blankNodesToCheck.removeAll(orphanedNodes);
    }

    return removedCount;
  }

  /**
   * Query triples from the shared store.
   *
   * @param subject Subject URI or null for wildcard
   * @param predicate Predicate URI or null for wildcard
   * @param object Object URI/literal or null for wildcard
   * @param workerId ID of the worker querying
   * @return List of matching statements in TURTLE format
   */
  public synchronized List<String> queryTriples(
      String subject, String predicate, String object, String workerId) {
    Resource subjectRes = subject != null ? model.getResource(subject) : null;
    Property predicateRes = predicate != null ? model.getProperty(predicate) : null;
    RDFNode objectNode = object != null ? parseObject(object) : null;

    List<String> results = new ArrayList<>();
    StmtIterator iter = model.listStatements(subjectRes, predicateRes, objectNode);

    while (iter.hasNext()) {
      Statement stmt = iter.nextStatement();
      results.add(formatStatement(stmt));
    }

    logger.debug("[{}] Query returned {} triples", workerId, results.size());
    return results;
  }

  /**
   * Get all triples as TURTLE formatted string.
   *
   * @return TURTLE representation of all triples
   */
  public synchronized String getAllTriples() {
    if (model.isEmpty()) {
      return "";
    }
    StringWriter writer = new StringWriter();
    RDFDataMgr.write(writer, model, Lang.TURTLE);
    return writer.toString();
  }

  /**
   * Get the internal Jena Model for validation.
   *
   * @return The internal RDF model
   */
  public synchronized Model getModel() {
    return ModelFactory.createDefaultModel().add(model);
  }

  /**
   * Get the number of triples in the shared store.
   *
   * @return Triple count
   */
  public synchronized long size() {
    return model.size();
  }

  /**
   * Clear all triples from the shared store. Should only be called by initialization or cleanup
   * routines.
   */
  public synchronized void clear() {
    int sizeBefore = (int) model.size();
    model.removeAll();
    if (sizeBefore > 0) {
      dirty = true;
      version++;
    }
    logger.info("Cleared {} triples from shared store", sizeBefore);
  }

  /**
   * Validates the current store contents against the given SHACL shapes, returning a
   * (model-snapshot, report) pair. Repeated calls with no intervening mutation return the cached
   * result instead of re-copying the model and re-running validation, since supervisor-side round
   * bookkeeping validates the same unchanged store several times per round.
   */
  public ValidationSnapshot getValidationSnapshot(Shacl shacl) {
    if (shacl == null) {
      return null;
    }

    long versionAtSnapshot;
    Model snapshot;
    synchronized (this) {
      if (cachedValidation != null && cachedValidation.version() == version) {
        return cachedValidation;
      }
      versionAtSnapshot = version;
      snapshot = ModelFactory.createDefaultModel().add(model);
    }

    ValidationReport report = shacl.validate(snapshot);
    ValidationSnapshot result = new ValidationSnapshot(snapshot, report, versionAtSnapshot);

    synchronized (this) {
      if (cachedValidation == null || versionAtSnapshot >= cachedValidation.version()) {
        cachedValidation = result;
      }
    }
    return result;
  }

  /**
   * Snapshot of a SHACL validation run: the model as it existed at validation time, the resulting
   * report, and the store version it corresponds to (for cache invalidation).
   */
  public record ValidationSnapshot(Model model, ValidationReport report, long version) {}

  /** Flush current shared model to output file if there are pending changes. */
  public synchronized void flushToOutputFile(String actorId) {
    if (!dirty || outputPath == null) {
      return;
    }

    try {
      writeCompleteModelToOutputFile(actorId);
      dirty = false;
    } catch (Exception e) {
      logger.error("[{}] Failed to flush model to output file: {}", actorId, e.getMessage(), e);
    }
  }

  /** Parse object string to RDFNode. */
  private RDFNode parseObject(String object) {
    if (object.startsWith("http://")
        || object.startsWith("https://")
        || object.startsWith("urn:")) {
      return model.getResource(object);
    }
    return model.createLiteral(object);
  }

  /**
   * Write the complete merged model to the output file. Overwrites the file with the current state
   * of all triples in the shared store.
   */
  private void writeCompleteModelToOutputFile(String workerId) {
    if (outputPath == null) {
      return;
    }

    try {
      Path outputFile = Path.of(outputPath);

      // Create parent directories if they don't exist
      Files.createDirectories(outputFile.getParent());

      // Convert complete model to TURTLE format
      StringWriter writer = new StringWriter();
      RDFDataMgr.write(writer, model, Lang.TURTLE);
      String turtleData = writer.toString();

      // Write complete model to file (overwrite, not append)
      Files.writeString(
          outputFile, turtleData, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

      logger.debug(
          "[{}] Wrote complete model ({} triples) to output file: {}",
          workerId,
          model.size(),
          outputFile);
    } catch (Exception e) {
      logger.error("[{}] Failed to write model to output file: {}", workerId, e.getMessage(), e);
    }
  }

  /** Format a statement as a string for display. */
  private String formatStatement(Statement stmt) {
    return String.format(
        "<%s> <%s> %s .",
        stmt.getSubject().getURI(), stmt.getPredicate().getURI(), formatNode(stmt.getObject()));
  }

  /** Format an RDF node for display. */
  private String formatNode(RDFNode node) {
    if (node.isResource()) {
      return "<" + node.asResource().getURI() + ">";
    } else if (node.isLiteral()) {
      return "\"" + node.asLiteral().getString() + "\"";
    }
    return node.toString();
  }

  /** Result of adding triples, including duplicate detection. */
  @Getter
  public static class AddResult {
    private final int triplesAdded;
    private final List<Statement> duplicates;
    private final int totalTriples;
    private final String error;

    public AddResult(int triplesAdded, List<Statement> duplicates, int totalTriples) {
      this.triplesAdded = triplesAdded;
      this.duplicates = duplicates;
      this.totalTriples = totalTriples;
      this.error = null;
    }

    private AddResult(String error) {
      this.triplesAdded = 0;
      this.duplicates = List.of();
      this.totalTriples = 0;
      this.error = error;
    }

    public static AddResult error(String error) {
      return new AddResult(error);
    }

    public boolean hasError() {
      return error != null;
    }

    public boolean hasDuplicates() {
      return !duplicates.isEmpty();
    }
  }
}
