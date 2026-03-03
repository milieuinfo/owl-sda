package be.vlaanderen.omgeving.owlsda.ontology;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OntologyReasoner {
  private static final Logger logger = LoggerFactory.getLogger(OntologyReasoner.class);

  private final Config config;
  private Reasoner reasoner;

  public OntologyReasoner(Config config) {
    this.config = config;
    // initialize reasoner
    initialize();
  }

  protected void initialize() {
    // If a rule file is configured, create a GenericRuleReasoner from it.
    String rulesFile = config.getReasoner().getRulesFile();
    if (rulesFile != null && !rulesFile.isBlank()) {
      try {
        Path p = Paths.get(rulesFile);
        List<Rule> rules = Rule.rulesFromURL(p.toUri().toString());
        GenericRuleReasoner grr = new GenericRuleReasoner(rules);
        grr.setDerivationLogging(false);
        this.reasoner = grr;
        logger.info("Using custom rules reasoner from {}", rulesFile);
        return;
      } catch (Exception e) {
        logger.warn("Failed to load rules from {}: {}. Falling back to configured reasoner.", rulesFile, e.getMessage());
      }
    }

    // Choose configured reasoner type to improve performance where possible
    String type = config.getReasoner().getReasonerType();
    if (type != null && type.equalsIgnoreCase("rdfs")) {
      this.reasoner = ReasonerRegistry.getRDFSReasoner();
      logger.info("Using RDFS reasoner (configured) for faster, lighter-weight reasoning");
    } else if (type != null && type.equalsIgnoreCase("owl")) {
      this.reasoner = ReasonerRegistry.getOWLMicroReasoner();
      logger.info("Using OWL reasoner (configured) for more complete OWL reasoning");
    } else if (type != null && type.equalsIgnoreCase("transitive")) {
      this.reasoner = ReasonerRegistry.getTransitiveReasoner();
      logger.info("Using transitive rule reasoner (configured) without rules");
    } else {
      this.reasoner = ReasonerRegistry.getOWLMicroReasoner();
      logger.info("Using OWL reasoner (configured) for OWL reasoning");
    }
  }

  public void adapt(Ontology info) {
    Model base = info.getModel();
    Model union = (base != null) ? base : ModelFactory.createDefaultModel();

    if (info.getExternalModels() != null) {
      for (Model external : info.getExternalModels().values()) {
        if (external == null) continue;
        union = ModelFactory.createUnion(union, external);
      }
    }

    InfModel inf = null;

    // Check if inferred output already exists
    try {
      var outPath = config.getReasoner().getInferredOutputPath();
      if (outPath != null && !outPath.isBlank()) {
        Path outputFile = Paths.get(outPath);
        if (Files.exists(outputFile)) {
          logger.info("Inferred model output already exists at {}, skipping inference", outPath);
          // Load the existing inferred model
          inf = ModelFactory.createInfModel(reasoner, union);
          info.setInferredModel(inf);
          return;
        }
      }
    } catch (Exception e) {
      logger.warn("Failed to check inferred output file: {}", e.getMessage());
    }

    // Create the InfModel once over the union model. This avoids copying data and speeds up reasoning.
    inf = ModelFactory.createInfModel(reasoner, union);
    logger.info("Preparing inferred model (this may take some time for large ontologies)...");
    inf.prepare();

    // Only compute and log sizes when debug enabled to avoid expensive operations.
    if (logger.isDebugEnabled()) {
      try {
        long s = inf.size();
        logger.debug("Inferred model has {} statements", s);
      } catch (Exception ex) {
        logger.debug("Computing inferred model size failed: {}", ex.getMessage());
      }
    }

    info.setInferredModel(inf);

    // Write inferred model to output file if enabled
    try {
      var outPath = config.getReasoner().getInferredOutputPath();
      if (outPath != null && !outPath.isBlank()) {
        writeInferredModel(info.getInferredModel(), outPath);
      }
    } catch (Exception e) {
      logger.warn("Failed to write inferred model to {}: {}", config.getReasoner().getInferredOutputPath(), e.getMessage());
    }

  }

  private void writeInferredModel(Model model, String outputPath) throws Exception {
    logger.info("Writing inferred model to {}", outputPath);
    Path output = Paths.get(outputPath);
    Path dir = output.getParent();
    if (dir != null) Files.createDirectories(dir);
    Path tmp = output.resolveSibling(output.getFileName().toString() + ".tmp");
    try {
      try (var out = Files.newOutputStream(tmp)) {
        model.write(out, "TURTLE");
      }
      try {
        Files.move(tmp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException amnse) {
        Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      if (Files.exists(tmp)) Files.deleteIfExists(tmp);
    }
  }
}
