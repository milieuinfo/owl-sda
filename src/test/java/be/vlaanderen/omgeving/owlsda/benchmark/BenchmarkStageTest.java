package be.vlaanderen.omgeving.owlsda.benchmark;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;

/**
 * Tests for benchmark stage tracking.
 */
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
    be.vlaanderen.omgeving.owlsda.config.Config config = new be.vlaanderen.omgeving.owlsda.config.Config();
    config.setOutputPath(tempDir.resolve("output.ttl").toString());

    // Enable benchmarking
    be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties benchmarkProps =
        new be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties();
    benchmarkProps.setEnabled(true);
    benchmarkProps.setOutputDir(tempDir.toString());
    config.setBenchmark(benchmarkProps);

    benchmarkService = new BenchmarkService(config);

    // Create snapshots for each stage
    DefaultBenchmarkSnapshotData generateSnapshot = new DefaultBenchmarkSnapshotData(
        "GENERATE",
        0,
        10,
        1000L,
        null,
        null,
        null,
        List.of()
    );
    benchmarkService.createBatchSnapshot(generateSnapshot, 5);

    DefaultBenchmarkSnapshotData finalizingSnapshot = new DefaultBenchmarkSnapshotData(
        "FINALIZING",
        0,
        10,
        500L,
        null,
        null,
        null,
        List.of()
    );
    benchmarkService.createBatchSnapshot(finalizingSnapshot, 2);

    DefaultBenchmarkSnapshotData reviewSnapshot = new DefaultBenchmarkSnapshotData(
        "REVIEW",
        0,
        10,
        300L,
        null,
        null,
        null,
        List.of()
    );
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
    be.vlaanderen.omgeving.owlsda.config.Config config = new be.vlaanderen.omgeving.owlsda.config.Config();
    config.setOutputPath(tempDir.resolve("output.ttl").toString());

    // Enable benchmarking
    be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties benchmarkProps =
        new be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties();
    benchmarkProps.setEnabled(true);
    benchmarkProps.setOutputDir(tempDir.toString());
    config.setBenchmark(benchmarkProps);

    benchmarkService = new BenchmarkService(config);

    // Create a snapshot
    DefaultBenchmarkSnapshotData snapshot = new DefaultBenchmarkSnapshotData(
        "GENERATE",
        0,
        5,
        1500L,
        null,
        null,
        null,
        List.of()
    );
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
    String oldFormatMetadata = """
        shapes_processed=10
        duration_ms=2000
        timestamp=20260304_120000_000
        triplestore_size=100
        triplestore_empty=false
        current_violations=2
        """;
    Files.writeString(metadataFile, oldFormatMetadata);

    // Create config and service
    be.vlaanderen.omgeving.owlsda.config.Config config = new be.vlaanderen.omgeving.owlsda.config.Config();
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
    assertTrue("Should default to GENERATE stage for old snapshots", jsonContent.contains("GENERATE"));
  }

  @Test
  public void testJsonContainsTokenUsageBreakdown() throws Exception {
    be.vlaanderen.omgeving.owlsda.config.Config config = new be.vlaanderen.omgeving.owlsda.config.Config();
    config.setOutputPath(tempDir.resolve("output.ttl").toString());

    be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties benchmarkProps =
        new be.vlaanderen.omgeving.owlsda.config.Config.BenchmarkProperties();
    benchmarkProps.setEnabled(true);
    benchmarkProps.setOutputDir(tempDir.toString());
    config.setBenchmark(benchmarkProps);

    benchmarkService = new BenchmarkService(config);

    Session supervisor = new TokenOnlySession(987L);
    Session reviewer = new TokenOnlySession(654L);
    List<Session> workers = List.of(new TokenOnlySession(1515L), new TokenOnlySession(2222L));

    String snapshotId = benchmarkService.createBatchSnapshot(
        new DefaultBenchmarkSnapshotData(
            "GENERATE",
            0,
            10,
            1200L,
            supervisor,
            reviewer,
            null,
            workers
        ),
        0
    );

    assertNotNull("Snapshot should be created", snapshotId);

    Path jsonFile = benchmarkService.generateJsonSummary();
    assertNotNull("JSON summary should be generated", jsonFile);

    String jsonContent = Files.readString(jsonFile);
    assertTrue("Should include tokens object", jsonContent.contains("\"tokens\""));
    assertTrue("Should include supervisor tokens", jsonContent.contains("\"supervisor\": 987"));
    assertTrue("Should include reviewer tokens", jsonContent.contains("\"reviewer\": 654"));
    assertTrue("Should include worker_0 tokens", jsonContent.contains("\"worker_0\": 1515"));
    assertTrue("Should include worker_1 tokens", jsonContent.contains("\"worker_1\": 2222"));
  }

  private static class TokenOnlySession implements Session {
    private final long tokens;

    private TokenOnlySession(long tokens) {
      this.tokens = tokens;
    }

    @Override
    public void addContext(Context context) {
    }

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
    public List<SessionMessageLogEntry> getMessageLog() {
      return List.of();
    }

    @Override
    public long getTotalTokensUsed() {
      return tokens;
    }

    @Override
    public void close() {
    }
  }
}
