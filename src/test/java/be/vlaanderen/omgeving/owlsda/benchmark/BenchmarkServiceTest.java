package be.vlaanderen.omgeving.owlsda.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import be.vlaanderen.omgeving.owlsda.agent.RequestMessage;
import be.vlaanderen.omgeving.owlsda.agent.ResponseMessage;
import be.vlaanderen.omgeving.owlsda.agent.Session;
import be.vlaanderen.omgeving.owlsda.agent.context.Context;
import be.vlaanderen.omgeving.owlsda.agent.handler.WorkerTripleStore;
import be.vlaanderen.omgeving.owlsda.config.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.Before;
import org.junit.Test;

public class BenchmarkServiceTest {

  private Path tempDir;
  private Config config;
  private BenchmarkService benchmarkService;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("benchmark-service-test");
    config = new Config();
    config.setOutputPath(tempDir.resolve("output.ttl").toString());

    Config.BenchmarkProperties benchmarkProps = new Config.BenchmarkProperties();
    benchmarkProps.setEnabled(true);
    benchmarkProps.setOutputDir(tempDir.toString());
    config.setBenchmark(benchmarkProps);

    benchmarkService = new BenchmarkService(config);
  }

  @Test
  public void isEnabled_ReflectsConfiguredBenchmarkFlag() {
    assertTrue(benchmarkService.isEnabled());

    Config disabledConfig = new Config();
    Config.BenchmarkProperties disabledProps = new Config.BenchmarkProperties();
    disabledProps.setEnabled(false);
    disabledConfig.setBenchmark(disabledProps);
    assertFalse(new BenchmarkService(disabledConfig).isEnabled());
  }

  @Test
  public void isEnabled_NullBenchmarkConfig_IsFalse() {
    Config noBenchmarkConfig = new Config();
    assertFalse(new BenchmarkService(noBenchmarkConfig).isEnabled());
  }

  @Test
  public void createBatchSnapshot_Disabled_ReturnsNullAndCreatesNoFiles() throws Exception {
    Config.BenchmarkProperties disabledProps = new Config.BenchmarkProperties();
    disabledProps.setEnabled(false);
    disabledProps.setOutputDir(tempDir.toString());
    config.setBenchmark(disabledProps);
    BenchmarkService disabledService = new BenchmarkService(config);

    String id =
        disabledService.createBatchSnapshot(
            new DefaultBenchmarkSnapshotData("GENERATE", 0, 1, 10L, null, null, null, List.of()),
            0);

    assertNull(id);
    try (var stream = Files.list(tempDir)) {
      assertEquals(0, stream.count());
    }
  }

  @Test
  public void createBatchSnapshot_WritesMetadataFile() throws Exception {
    String snapshotId =
        benchmarkService.createBatchSnapshot(
            new DefaultBenchmarkSnapshotData("GENERATE", 0, 4, 250L, null, null, null, List.of()),
            2);

    assertNotNull(snapshotId);
    Path metadataFile = tempDir.resolve(snapshotId).resolve("metadata.txt");
    assertTrue(Files.exists(metadataFile));

    String metadata = Files.readString(metadataFile);
    assertTrue(metadata.contains("stage=GENERATE"));
    assertTrue(metadata.contains("shapes_processed=4"));
    assertTrue(metadata.contains("duration_ms=250"));
    assertTrue(metadata.contains("current_violations=2"));
  }

  @Test
  public void createBatchSnapshot_WritesTripleStoreSnapshot() throws Exception {
    Path tsFile = Files.createTempFile("bench-ts", ".ttl");
    WorkerTripleStore tripleStore = new WorkerTripleStore(tsFile.toString());
    tripleStore.addTriples(
        "@prefix ex: <http://example.org/> .\nex:s1 ex:name \"Alice\" .", "worker-0");

    String snapshotId =
        benchmarkService.createBatchSnapshot(
            new DefaultBenchmarkSnapshotData(
                "GENERATE", 0, 1, 10L, null, null, tripleStore, List.of()),
            0);

    Path ttlFile = tempDir.resolve(snapshotId).resolve("triplestore.ttl");
    assertTrue(Files.exists(ttlFile));
    assertTrue(Files.readString(ttlFile).contains("Alice"));

    Path summaryFile = tempDir.resolve(snapshotId).resolve("triplestore-summary.txt");
    assertTrue(Files.exists(summaryFile));
    assertTrue(Files.readString(summaryFile).contains("Total Triples: 1"));
  }

  @Test
  public void createBatchSnapshot_EmptyTripleStore_WritesEmptyMarker() throws Exception {
    Path tsFile = Files.createTempFile("bench-ts-empty", ".ttl");
    WorkerTripleStore emptyStore = new WorkerTripleStore(tsFile.toString());

    String snapshotId =
        benchmarkService.createBatchSnapshot(
            new DefaultBenchmarkSnapshotData(
                "GENERATE", 0, 1, 10L, null, null, emptyStore, List.of()),
            0);

    Path ttlFile = tempDir.resolve(snapshotId).resolve("triplestore.ttl");
    assertTrue(Files.exists(ttlFile));
    assertTrue(Files.readString(ttlFile).contains("empty"));
  }

  @Test
  public void createBatchSnapshot_WritesSessionContextsAndMessageLogs() throws Exception {
    Context context = new Context();
    context.setName("delegation");
    context.setContent("do the work");

    Session generator = new FakeSession(List.of(context));

    String snapshotId =
        benchmarkService.createBatchSnapshot(
            new DefaultBenchmarkSnapshotData(
                "GENERATE", 0, 1, 10L, generator, null, null, List.of()),
            0);

    Path contextFile =
        tempDir.resolve(snapshotId).resolve("supervisor_context").resolve("delegation.txt");
    assertTrue(Files.exists(contextFile));
    assertEquals("do the work", Files.readString(contextFile));

    Path messageLog =
        tempDir.resolve(snapshotId).resolve("message_logs").resolve("supervisor-messages.json");
    assertTrue(Files.exists(messageLog));
    String content = Files.readString(messageLog);
    assertTrue(content.contains("hello"));
  }

  @Test
  public void createBatchSnapshot_UnchangedState_SkipsSecondSnapshot() throws Exception {
    DefaultBenchmarkSnapshotData snapshotData =
        new DefaultBenchmarkSnapshotData("GENERATE", 0, 1, 10L, null, null, null, List.of());

    String first = benchmarkService.createBatchSnapshot(snapshotData, 0);
    assertNotNull(first);

    String second = benchmarkService.createBatchSnapshot(snapshotData, 0);
    assertNull("Repeated snapshot with unchanged state should be skipped", second);
  }

  @Test
  public void createBatchSnapshot_DifferentStage_AlwaysCreatesNewSnapshot() throws Exception {
    DefaultBenchmarkSnapshotData generate =
        new DefaultBenchmarkSnapshotData("GENERATE", 0, 1, 10L, null, null, null, List.of());
    DefaultBenchmarkSnapshotData review =
        new DefaultBenchmarkSnapshotData("REVIEW", 0, 1, 10L, null, null, null, List.of());

    String first = benchmarkService.createBatchSnapshot(generate, 0);
    String second = benchmarkService.createBatchSnapshot(review, 0);

    assertNotNull(first);
    assertNotNull(second);
  }

  @Test
  public void generateJsonSummary_Disabled_ReturnsNull() {
    Config.BenchmarkProperties disabledProps = new Config.BenchmarkProperties();
    disabledProps.setEnabled(false);
    disabledProps.setOutputDir(tempDir.toString());
    config.setBenchmark(disabledProps);
    BenchmarkService disabledService = new BenchmarkService(config);

    assertNull(disabledService.generateJsonSummary());
  }

  @Test
  public void generateJsonSummary_NoSnapshots_ReturnsNull() {
    Path jsonFile = benchmarkService.generateJsonSummary();
    assertNull(jsonFile);
  }

  @Test
  public void generateJsonSummary_WithSnapshots_WritesJsonFileWithAllEntries() throws Exception {
    benchmarkService.createBatchSnapshot(
        new DefaultBenchmarkSnapshotData("GENERATE", 0, 1, 10L, null, null, null, List.of()), 3);
    benchmarkService.createBatchSnapshot(
        new DefaultBenchmarkSnapshotData("REVIEW", 0, 2, 20L, null, null, null, List.of()), 0);

    Path jsonFile = benchmarkService.generateJsonSummary();

    assertNotNull(jsonFile);
    assertTrue(Files.exists(jsonFile));
    assertEquals("benchmark-summary.json", jsonFile.getFileName().toString());

    String content = Files.readString(jsonFile);
    assertTrue(content.contains("GENERATE"));
    assertTrue(content.contains("REVIEW"));
  }

  private static final class FakeSession implements Session {
    private final List<Context> contexts;

    private FakeSession(List<Context> contexts) {
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
    public List<be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry> getMessageLog() {
      return List.of(
          new be.vlaanderen.omgeving.owlsda.agent.SessionMessageLogEntry(
              "2026-07-13T12:00:00Z", "outbound", "msg-1", "hello"));
    }

    @Override
    public void close() {}
  }
}
