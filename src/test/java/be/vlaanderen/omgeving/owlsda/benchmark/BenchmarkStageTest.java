package be.vlaanderen.omgeving.owlsda.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;

/** Tests for benchmark stage tracking. */
public class BenchmarkStageTest {

  private Path tempDir;
  private BenchmarkService benchmarkService;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("benchmark-stage-test");
  }

  @Test
  public void testStageInMetadata() throws Exception {
    // Create a simple config
    be.vlaanderen.omgeving.owlsda.config.Config config =
        new be.vlaanderen.omgeving.owlsda.config.Config();
    config.setOutputPath(tempDir.resolve("output.ttl").toString());

    // Enable benchmarking
    be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties benchmarkProps =
        new be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties();
    benchmarkProps.setEnabled(true);
    benchmarkProps.setOutputDir(tempDir.toString());
    config.setBenchmark(benchmarkProps);

    benchmarkService = new BenchmarkService(config);

    // Create snapshots for each stage
    DefaultBenchmarkSnapshotData generateSnapshot =
        new DefaultBenchmarkSnapshotData("GENERATE", 0, 10, 1000L, null, null, null, List.of());
    benchmarkService.createBatchSnapshot(generateSnapshot, 5);

    DefaultBenchmarkSnapshotData finalizingSnapshot =
        new DefaultBenchmarkSnapshotData("FINALIZING", 0, 10, 500L, null, null, null, List.of());
    benchmarkService.createBatchSnapshot(finalizingSnapshot, 2);

    DefaultBenchmarkSnapshotData reviewSnapshot =
        new DefaultBenchmarkSnapshotData("REVIEW", 0, 10, 300L, null, null, null, List.of());
    benchmarkService.createBatchSnapshot(reviewSnapshot, 0);

    // Generate JSON summary
    Path jsonFile = benchmarkService.generateJsonSummary();
    assertNotNull("JSON summary should be generated", jsonFile);
    assertTrue("JSON file should exist", Files.exists(jsonFile));

    // Read and verify JSON content
    String jsonContent = Files.readString(jsonFile);
    assertTrue("JSON should contain GENERATE stage", jsonContent.contains("GENERATE"));
    assertTrue("JSON should contain FINALIZING stage", jsonContent.contains("FINALIZING"));
    assertTrue("JSON should contain REVIEW stage", jsonContent.contains("REVIEW"));

    // Verify we have 3 snapshots
    assertTrue("Should have 3 snapshots", jsonContent.split("\"stage\"").length - 1 == 3);
  }

  @Test
  public void testMetadataFileContainsStage() throws Exception {
    // Create a simple config
    be.vlaanderen.omgeving.owlsda.config.Config config =
        new be.vlaanderen.omgeving.owlsda.config.Config();
    config.setOutputPath(tempDir.resolve("output.ttl").toString());

    // Enable benchmarking
    be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties benchmarkProps =
        new be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties();
    benchmarkProps.setEnabled(true);
    benchmarkProps.setOutputDir(tempDir.toString());
    config.setBenchmark(benchmarkProps);

    benchmarkService = new BenchmarkService(config);

    // Create a snapshot
    DefaultBenchmarkSnapshotData snapshot =
        new DefaultBenchmarkSnapshotData("GENERATE", 0, 5, 1500L, null, null, null, List.of());
    String snapshotId = benchmarkService.createBatchSnapshot(snapshot, 3);
    assertNotNull("Snapshot should be created", snapshotId);

    // Find the metadata file
    Path snapshotDir = tempDir.resolve(snapshotId);
    Path metadataFile = snapshotDir.resolve("metadata.txt");
    assertTrue("Metadata file should exist", Files.exists(metadataFile));

    // Verify metadata contains stage
    String metadata = Files.readString(metadataFile);
    assertTrue("Metadata should contain stage field", metadata.contains("stage=GENERATE"));
    assertTrue("Metadata should contain shapes_processed", metadata.contains("shapes_processed=5"));
    assertTrue("Metadata should contain duration_ms", metadata.contains("duration_ms=1500"));
  }

  @Test
  public void testBackwardCompatibilityWithMissingStage() throws Exception {
    // Create a metadata file without stage field (simulating old format)
    Path snapshotDir = tempDir.resolve("20260304_120000_000");
    Files.createDirectories(snapshotDir);

    Path metadataFile = snapshotDir.resolve("metadata.txt");
    String oldFormatMetadata =
        """
        shapes_processed=10
        duration_ms=2000
        timestamp=20260304_120000_000
        triplestore_size=100
        triplestore_empty=false
        current_violations=2
        """;
    Files.writeString(metadataFile, oldFormatMetadata);

    // Create config and service
    be.vlaanderen.omgeving.owlsda.config.Config config =
        new be.vlaanderen.omgeving.owlsda.config.Config();
    config.setOutputPath(tempDir.resolve("output.ttl").toString());

    be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties benchmarkProps =
        new be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties();
    benchmarkProps.setEnabled(true);
    benchmarkProps.setOutputDir(tempDir.toString());
    config.setBenchmark(benchmarkProps);

    benchmarkService = new BenchmarkService(config);

    // Generate JSON summary - should default to GENERATE for backward compatibility
    Path jsonFile = benchmarkService.generateJsonSummary();
    assertNotNull("JSON summary should be generated", jsonFile);

    String jsonContent = Files.readString(jsonFile);
    assertTrue(
        "Should default to GENERATE stage for old snapshots", jsonContent.contains("GENERATE"));
  }

  @Test
  public void testJsonContainsTokenUsageBreakdown() throws Exception {
    be.vlaanderen.omgeving.owlsda.config.Config config =
        new be.vlaanderen.omgeving.owlsda.config.Config();
    config.setOutputPath(tempDir.resolve("output.ttl").toString());

    be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties benchmarkProps =
        new be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties();
    benchmarkProps.setEnabled(true);
    benchmarkProps.setOutputDir(tempDir.toString());
    config.setBenchmark(benchmarkProps);

    benchmarkService = new BenchmarkService(config);

    Session supervisor = new TokenOnlySession(700L, 287L);
    Session reviewer = new TokenOnlySession(400L, 254L);
    List<Session> workers =
        List.of(new TokenOnlySession(1000L, 515L), new TokenOnlySession(1200L, 1022L));

    String snapshotId =
        benchmarkService.createBatchSnapshot(
            new DefaultBenchmarkSnapshotData(
                "GENERATE", 0, 10, 1200L, supervisor, reviewer, null, workers),
            0);

    assertNotNull("Snapshot should be created", snapshotId);

    Path jsonFile = benchmarkService.generateJsonSummary();
    assertNotNull("JSON summary should be generated", jsonFile);

    String jsonContent = Files.readString(jsonFile);
    JsonArray snapshots = JsonParser.parseString(jsonContent).getAsJsonArray();
    assertEquals("Expected exactly one snapshot", 1, snapshots.size());

    JsonObject snapshot = snapshots.get(0).getAsJsonObject();
    JsonObject tokens = snapshot.getAsJsonObject("tokens");
    assertNotNull("Should include tokens object", tokens);

    JsonObject supervisorTokens = tokens.getAsJsonObject("supervisor");
    assertEquals(
        "Supervisor input tokens should match", 700L, supervisorTokens.get("input").getAsLong());
    assertEquals(
        "Supervisor output tokens should match", 287L, supervisorTokens.get("output").getAsLong());
    assertEquals(
        "Supervisor total tokens should match", 987L, supervisorTokens.get("total").getAsLong());

    JsonObject reviewerTokens = tokens.getAsJsonObject("reviewer");
    assertEquals(
        "Reviewer input tokens should match", 400L, reviewerTokens.get("input").getAsLong());
    assertEquals(
        "Reviewer output tokens should match", 254L, reviewerTokens.get("output").getAsLong());
    assertEquals(
        "Reviewer total tokens should match", 654L, reviewerTokens.get("total").getAsLong());

    JsonObject workersTokens = tokens.getAsJsonObject("workers");
    JsonObject worker0 = workersTokens.getAsJsonObject("worker_0");
    JsonObject worker1 = workersTokens.getAsJsonObject("worker_1");

    assertEquals("Worker 0 input tokens should match", 1000L, worker0.get("input").getAsLong());
    assertEquals("Worker 0 output tokens should match", 515L, worker0.get("output").getAsLong());
    assertEquals("Worker 0 total tokens should match", 1515L, worker0.get("total").getAsLong());

    assertEquals("Worker 1 input tokens should match", 1200L, worker1.get("input").getAsLong());
    assertEquals("Worker 1 output tokens should match", 1022L, worker1.get("output").getAsLong());
    assertEquals("Worker 1 total tokens should match", 2222L, worker1.get("total").getAsLong());
  }

  @Test
  public void testSnapshotSanitizesInvalidSurrogatesInContext() throws Exception {
    be.vlaanderen.omgeving.owlsda.config.Config config =
        new be.vlaanderen.omgeving.owlsda.config.Config();
    config.setOutputPath(tempDir.resolve("output.ttl").toString());

    be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties benchmarkProps =
        new be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties();
    benchmarkProps.setEnabled(true);
    benchmarkProps.setOutputDir(tempDir.toString());
    config.setBenchmark(benchmarkProps);

    benchmarkService = new BenchmarkService(config);

    Context invalidContext = new Context();
    invalidContext.setName("invalid-surrogate");
    invalidContext.setContent("prefix-\uD83D-suffix");

    Session supervisor = new ContextOnlySession(List.of(invalidContext));

    String snapshotId =
        benchmarkService.createBatchSnapshot(
            new DefaultBenchmarkSnapshotData(
                "GENERATE", 0, 1, 10L, supervisor, null, null, List.of()),
            0);

    assertNotNull("Snapshot should still be created", snapshotId);

    Path contextFile =
        tempDir.resolve(snapshotId).resolve("supervisor_context").resolve("invalid-surrogate.txt");
    assertTrue("Context file should exist", Files.exists(contextFile));

    String storedContent = Files.readString(contextFile);
    assertEquals("Invalid surrogate should be replaced", "prefix-\uFFFD-suffix", storedContent);
  }

  private static final class TokenOnlySession implements Session {
    private final long inputTokens;
    private final long outputTokens;

    private TokenOnlySession(long inputTokens, long outputTokens) {
      this.inputTokens = inputTokens;
      this.outputTokens = outputTokens;
    }

    @Override
    public void addContext(Context context) {}

    @Override
    public boolean addContextIfChanged(Context context) {
      return false;
    }

    @Override
    public List<Context> getContext() {
      return List.of();
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> contexts) {
      return CompletableFuture.completedFuture(new ResponseMessage("test"));
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      return CompletableFuture.completedFuture(new ResponseMessage("test"));
    }

    @Override
    public long getInputTokensUsed() {
      return inputTokens;
    }

    @Override
    public long getOutputTokensUsed() {
      return outputTokens;
    }

    @Override
    public long getTotalTokensUsed() {
      return inputTokens + outputTokens;
    }

    @Override
    public void close() {}
  }

  private static final class ContextOnlySession implements Session {
    private final List<Context> contexts;

    private ContextOnlySession(List<Context> contexts) {
      this.contexts = contexts;
    }

    @Override
    public void addContext(Context context) {}

    @Override
    public boolean addContextIfChanged(Context context) {
      return false;
    }

    @Override
    public List<Context> getContext() {
      return contexts;
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input, List<Context> contexts) {
      return CompletableFuture.completedFuture(new ResponseMessage("test"));
    }

    @Override
    public CompletableFuture<ResponseMessage> prompt(RequestMessage input) {
      return CompletableFuture.completedFuture(new ResponseMessage("test"));
    }

    @Override
    public void close() {}
  }
}
