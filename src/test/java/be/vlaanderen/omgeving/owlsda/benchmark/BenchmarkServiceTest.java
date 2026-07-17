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
  private Path liveDir;
  private Config config;
  private BenchmarkService benchmarkService;

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("benchmark-service-test");
    liveDir = tempDir.resolve("live");
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
    Path metadataFile = liveDir.resolve("metadata.txt");
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

    Path ttlFile = liveDir.resolve("triplestore.ttl");
    assertTrue(Files.exists(ttlFile));
    assertTrue(Files.readString(ttlFile).contains("Alice"));

    Path summaryFile = liveDir.resolve("triplestore-summary.txt");
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

    Path ttlFile = liveDir.resolve("triplestore.ttl");
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

    Path contextFile = liveDir.resolve("supervisor_context").resolve("delegation.txt");
    assertTrue(Files.exists(contextFile));
    assertEquals("do the work", Files.readString(contextFile));

    Path messageLog = liveDir.resolve("message_logs").resolve("supervisor-messages.json");
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
  public void createBatchSnapshot_DifferentStage_AlwaysWritesAndAppendsHistory() throws Exception {
    DefaultBenchmarkSnapshotData generate =
        new DefaultBenchmarkSnapshotData("GENERATE", 0, 1, 10L, null, null, null, List.of());
    DefaultBenchmarkSnapshotData review =
        new DefaultBenchmarkSnapshotData("REVIEW", 0, 1, 10L, null, null, null, List.of());

    String first = benchmarkService.createBatchSnapshot(generate, 0);
    String second = benchmarkService.createBatchSnapshot(review, 0);

    assertNotNull(first);
    assertNotNull(second);

    // Both stages land in the same run directory - metadata.txt reflects only the latest write -
    // but each is preserved as its own entry in the history file.
    assertTrue(Files.exists(liveDir.resolve("metadata.txt")));
    String metadata = Files.readString(liveDir.resolve("metadata.txt"));
    assertTrue(metadata.contains("stage=REVIEW"));

    String history = Files.readString(liveDir.resolve("benchmark-summary.json"));
    assertTrue(history.contains("GENERATE"));
    assertTrue(history.contains("REVIEW"));
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

  @Test
  public void archivePreviousRunIfPresent_NoLiveData_ReturnsNullAndCreatesNoFiles()
      throws Exception {
    assertNull(benchmarkService.archivePreviousRunIfPresent());
    try (var stream = Files.list(tempDir)) {
      assertEquals(0, stream.count());
    }
  }

  @Test
  public void archivePreviousRunIfPresent_Disabled_ReturnsNull() {
    Config.BenchmarkProperties disabledProps = new Config.BenchmarkProperties();
    disabledProps.setEnabled(false);
    disabledProps.setOutputDir(tempDir.toString());
    config.setBenchmark(disabledProps);

    assertNull(new BenchmarkService(config).archivePreviousRunIfPresent());
  }

  @Test
  public void archivePreviousRunIfPresent_MovesLiveDataOutOfTheWay() throws Exception {
    benchmarkService.createBatchSnapshot(
        new DefaultBenchmarkSnapshotData("GENERATE", 0, 3, 100L, null, null, null, List.of()), 0);
    assertTrue(Files.exists(liveDir.resolve("metadata.txt")));

    Path archiveDir = benchmarkService.archivePreviousRunIfPresent();

    assertNotNull(archiveDir);
    assertTrue(
        "Archive directory should live under output-dir/archive",
        archiveDir.startsWith(tempDir.resolve("archive")));
    assertTrue(Files.exists(archiveDir.resolve("metadata.txt")));
    assertTrue(Files.readString(archiveDir.resolve("metadata.txt")).contains("shapes_processed=3"));

    // The live directory is gone - a fresh run can start clean without clobbering the archive.
    assertFalse(
        "live/ should no longer exist after archiving", Files.exists(liveDir));
  }

  @Test
  public void archivePreviousRunIfPresent_NextRunStartsFreshAfterArchiving() throws Exception {
    benchmarkService.createBatchSnapshot(
        new DefaultBenchmarkSnapshotData("GENERATE", 0, 1, 10L, null, null, null, List.of()), 0);

    // Simulate a brand new process/run: archiving is always followed by a freshly constructed
    // BenchmarkService in production (OWLSDA builds one per run), not a reused instance whose
    // ChangeDetector still remembers the old run's last-written state.
    BenchmarkService nextRunService = new BenchmarkService(config);
    nextRunService.archivePreviousRunIfPresent();

    String snapshotId =
        nextRunService.createBatchSnapshot(
            new DefaultBenchmarkSnapshotData("GENERATE", 0, 1, 10L, null, null, null, List.of()),
            0);

    assertNotNull("New run's first snapshot should not be blocked by the archived one", snapshotId);
    assertTrue(Files.exists(liveDir.resolve("metadata.txt")));
    String history = Files.readString(liveDir.resolve("benchmark-summary.json"));
    assertEquals(1, history.split("\"stage\"").length - 1);
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
