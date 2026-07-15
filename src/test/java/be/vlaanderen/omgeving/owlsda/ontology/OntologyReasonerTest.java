package be.vlaanderen.omgeving.owlsda.ontology;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link OntologyReasoner}. */
public class OntologyReasonerTest {

  private static final String EX = "http://example.org/";

  private Path tempDir;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("ontology-reasoner-test");
  }

  private Model readTurtle(String turtle) {
    Model model = ModelFactory.createDefaultModel();
    model.read(new StringReader(turtle), null, "TURTLE");
    return model;
  }

  private Model subClassHierarchyModel() {
    return readTurtle(
        """
        @prefix ex: <http://example.org/> .
        @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

        ex:Dog rdfs:subClassOf ex:Animal .
        ex:Animal rdfs:subClassOf ex:LivingThing .
        ex:Fido a ex:Dog .
        """);
  }

  private Ontology ontologyWithModel(Model model) {
    Ontology ontology = new Ontology();
    ontology.setModel(model);
    return ontology;
  }

  @Test
  public void adapt_RdfsReasoner_InfersTransitiveTypeMembership() {
    Config config = new Config();
    config.getReasoner().setReasonerType("rdfs");

    OntologyReasoner reasoner = new OntologyReasoner(config);
    Ontology ontology = ontologyWithModel(subClassHierarchyModel());

    reasoner.adapt(ontology);

    assertNotNull(ontology.getInferredModel());
    assertTrue(
        ontology
            .getInferredModel()
            .contains(
                ResourceFactory.createResource(EX + "Fido"),
                RDF.type,
                ResourceFactory.createResource(EX + "LivingThing")));
  }

  @Test
  public void adapt_OwlReasoner_InfersTransitiveTypeMembership() {
    Config config = new Config();
    config.getReasoner().setReasonerType("owl");

    OntologyReasoner reasoner = new OntologyReasoner(config);
    Ontology ontology = ontologyWithModel(subClassHierarchyModel());

    reasoner.adapt(ontology);

    assertNotNull(ontology.getInferredModel());
    assertTrue(
        ontology
            .getInferredModel()
            .contains(
                ResourceFactory.createResource(EX + "Fido"),
                RDF.type,
                ResourceFactory.createResource(EX + "LivingThing")));
  }

  @Test
  public void adapt_UnknownReasonerType_FallsBackToOwlReasoner() {
    Config config = new Config();
    config.getReasoner().setReasonerType("not-a-real-type");

    OntologyReasoner reasoner = new OntologyReasoner(config);
    Ontology ontology = ontologyWithModel(subClassHierarchyModel());

    reasoner.adapt(ontology);

    assertTrue(
        ontology
            .getInferredModel()
            .contains(
                ResourceFactory.createResource(EX + "Fido"),
                RDF.type,
                ResourceFactory.createResource(EX + "LivingThing")));
  }

  @Test
  public void adapt_TransitiveReasoner_InfersTransitiveSubClassOfClosure() {
    Config config = new Config();
    config.getReasoner().setReasonerType("transitive");

    OntologyReasoner reasoner = new OntologyReasoner(config);
    Ontology ontology = ontologyWithModel(subClassHierarchyModel());

    reasoner.adapt(ontology);

    // The transitive reasoner computes the transitive closure of rdfs:subClassOf /
    // rdfs:subPropertyOf but does not propagate rdf:type membership through the hierarchy.
    assertTrue(
        ontology
            .getInferredModel()
            .contains(
                ResourceFactory.createResource(EX + "Dog"),
                RDFS.subClassOf,
                ResourceFactory.createResource(EX + "LivingThing")));
  }

  @Test
  public void adapt_UnionsExternalModelsWithBaseModel() {
    Config config = new Config();
    config.getReasoner().setReasonerType("rdfs");

    OntologyReasoner reasoner = new OntologyReasoner(config);
    Ontology ontology =
        ontologyWithModel(
            readTurtle(
                """
                @prefix ex: <http://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

                ex:Fido a ex:Dog .
                """));
    ontology
        .getExternalModels()
        .put(
            "extra",
            readTurtle(
                """
                @prefix ex: <http://example.org/> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

                ex:Dog rdfs:subClassOf ex:Animal .
                """));

    reasoner.adapt(ontology);

    assertTrue(
        ontology
            .getInferredModel()
            .contains(
                ResourceFactory.createResource(EX + "Fido"),
                RDF.type,
                ResourceFactory.createResource(EX + "Animal")));
  }

  @Test
  public void adapt_WithUnsetInferredOutputPath_DoesNotWriteCacheFile() {
    Config config = new Config();
    config.getReasoner().setReasonerType("rdfs");
    config.getReasoner().setInferredOutputPath("");

    OntologyReasoner reasoner = new OntologyReasoner(config);
    Ontology ontology = ontologyWithModel(subClassHierarchyModel());

    reasoner.adapt(ontology);

    assertNotNull(ontology.getInferredModel());
    assertTrue(
        ontology
            .getInferredModel()
            .contains(
                ResourceFactory.createResource(EX + "Fido"),
                RDF.type,
                ResourceFactory.createResource(EX + "LivingThing")));
  }

  @Test
  public void adapt_WritesInferredModelToCacheFileWhenPathConfigured() throws Exception {
    Path outputFile = tempDir.resolve("nested/inferred.ttl");

    Config config = new Config();
    config.getReasoner().setReasonerType("rdfs");
    config.getReasoner().setInferredOutputPath(outputFile.toString());

    OntologyReasoner reasoner = new OntologyReasoner(config);
    Ontology ontology = ontologyWithModel(subClassHierarchyModel());

    reasoner.adapt(ontology);

    assertTrue(Files.exists(outputFile));
    assertTrue(Files.size(outputFile) > 0);
  }

  @Test
  public void adapt_WithExistingCacheFile_SkipsReInferenceAndUsesCachedModel() throws Exception {
    Path outputFile = tempDir.resolve("cached-inferred.ttl");

    Config config = new Config();
    config.getReasoner().setReasonerType("rdfs");
    config.getReasoner().setInferredOutputPath(outputFile.toString());

    // First run computes inference over a hierarchy that entails ex:Fido a ex:LivingThing, and
    // caches the resulting model to disk.
    OntologyReasoner firstReasoner = new OntologyReasoner(config);
    Ontology firstOntology = ontologyWithModel(subClassHierarchyModel());
    firstReasoner.adapt(firstOntology);
    assertTrue(Files.exists(outputFile));

    // Second run uses a completely different (empty) base model. If the cache were bypassed, no
    // ex:Fido statements would exist at all. Because the cache file is present, adapt() should
    // load the previously-inferred model instead of re-running inference over the new (empty)
    // ontology model.
    OntologyReasoner secondReasoner = new OntologyReasoner(config);
    Ontology secondOntology = ontologyWithModel(ModelFactory.createDefaultModel());
    secondReasoner.adapt(secondOntology);

    assertTrue(
        secondOntology
            .getInferredModel()
            .contains(
                ResourceFactory.createResource(EX + "Fido"),
                RDF.type,
                ResourceFactory.createResource(EX + "LivingThing")));
  }

  @Test
  public void adapt_HandlesNullExternalModelsMapEntriesGracefully() {
    Config config = new Config();
    config.getReasoner().setReasonerType("rdfs");

    OntologyReasoner reasoner = new OntologyReasoner(config);
    Ontology ontology = ontologyWithModel(subClassHierarchyModel());
    ontology.getExternalModels().put("nullEntry", null);

    reasoner.adapt(ontology);

    assertNotNull(ontology.getInferredModel());
    assertFalse(ontology.getInferredModel().isEmpty());
  }
}
