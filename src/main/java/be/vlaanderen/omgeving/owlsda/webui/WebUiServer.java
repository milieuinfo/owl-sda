package be.vlaanderen.omgeving.owlsda.webui;

import be.vlaanderen.omgeving.owlsda.config.Config;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;

/**
 * Opt-in local web dashboard for viewing benchmark messages, tool calls, stages, and output.
 * Started with {@code --web-ui} (see {@code Main}); it reads the benchmark output directory live so
 * the dashboard reflects the run as it happens, and keeps serving it after the run finishes.
 */
@Slf4j
public class WebUiServer {

  private final Config config;
  private HttpServer server;
  private ExecutorService executor;

  public WebUiServer(Config config) {
    this.config = config;
  }

  /** Starts the dashboard HTTP server on {@code port}. */
  public void start(int port) throws IOException {
    RunDataReader reader = new RunDataReader(config);

    server = HttpServer.create(new InetSocketAddress(port), 0);
    executor = Executors.newFixedThreadPool(4);
    server.setExecutor(executor);

    server.createContext("/", new StaticAssetHandler());
    server.createContext("/api/state", new RunStateHandler(reader));
    server.createContext("/api/history", new HistoryHandler(reader));
    server.createContext("/api/messages", new MessageLogHandler(reader));
    server.createContext("/api/triplestore", new FileContentHandler(reader::readTriplestore));
    server.createContext("/api/output", new FileContentHandler(reader::readFinalOutput));

    server.start();
    log.info("Web UI dashboard listening on http://localhost:{}", port);

    if (!reader.benchmarkEnabled()) {
      log.warn(
          "Web UI started, but benchmark.enabled is not set in the config -- the dashboard will"
              + " stay empty until benchmarking is enabled.");
    }
  }

  /** Stops the dashboard HTTP server, if running. */
  public void stop() {
    if (server != null) {
      server.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
  }
}
