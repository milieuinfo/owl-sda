package be.vlaanderen.omgeving.owlsda.ontology;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.Before;
import org.junit.Test;

/** Tests for {@link OntologyExtractor}'s file cache round-trip. */
public class OntologyExtractorTest {

  private static final String EX = "http://example.org/";

  private Path cacheDir;

  @Before
  public void setUp() throws Exception {
    cacheDir = Files.createTempDirectory("ontology-extractor-test");
  }

  private Config configWithCache(boolean cacheEnabled) {
    Config config = new Config();
    config.getExtract().setCacheEnabled(cacheEnabled);
    config.getExtract().setCacheDir(cacheDir.toString());
    return config;
  }

  private Model modelWithOneTriple() {
    Model model = ModelFactory.createDefaultModel();
    model.read(
        new StringReader(
            """
            @prefix ex: <http://example.org/> .
            ex:Widget a ex:Thing .
            """),
        null,
        "TURTLE");
    return model;
  }

  private void putInFileCache(OntologyExtractor extractor, String reference, Model model)
      throws Exception {
    Method method =
        OntologyExtractor.class.getDeclaredMethod("putInFileCache", String.class, Model.class);
    method.setAccessible(true);
    method.invoke(extractor, reference, model);
  }

  private Model loadFromFileCache(OntologyExtractor extractor, String reference) throws Exception {
    Method method = OntologyExtractor.class.getDeclaredMethod("loadFromFileCache", String.class);
    method.setAccessible(true);
    return (Model) method.invoke(extractor, reference);
  }

  @Test
  public void putThenLoad_RoundTripsModelThroughFileCache() throws Exception {
    OntologyExtractor extractor = new OntologyExtractor(configWithCache(true));
    String reference = "http://example.org/ontology.ttl";

    putInFileCache(extractor, reference, modelWithOneTriple());
    Model reloaded = loadFromFileCache(extractor, reference);

    assertTrue(
        reloaded.contains(
            ResourceFactory.createResource(EX + "Widget"),
            RDF.type,
            ResourceFactory.createResource(EX + "Thing")));
  }

  @Test
  public void load_NothingCachedForReference_ReturnsNull() throws Exception {
    OntologyExtractor extractor = new OntologyExtractor(configWithCache(true));

    assertNull(loadFromFileCache(extractor, "http://example.org/never-cached.ttl"));
  }

  @Test
  public void putThenLoad_CacheDisabled_NeverPersistsOrReads() throws Exception {
    OntologyExtractor extractor = new OntologyExtractor(configWithCache(false));
    String reference = "http://example.org/ontology.ttl";

    putInFileCache(extractor, reference, modelWithOneTriple());

    assertNull(loadFromFileCache(extractor, reference));
  }
}
