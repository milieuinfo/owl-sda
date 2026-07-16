package be.vlaanderen.omgeving.owlsda;

import be.vlaanderen.omgeving.owlsda.config.Config;
import be.vlaanderen.omgeving.owlsda.webui.WebUiServer;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "owlsda",
    description = "OWL-SDA | OWL Synthetic Data AI-Agent",
    version = "1.0-SNAPSHOT")
public class Main implements Runnable {

  static {
    // Configure noisy FontBox logger before any logger backend initializes.
    System.setProperty("org.slf4j.simpleLogger.log.org.apache.fontbox.ttf.CmapSubtable", "error");
    System.setProperty(
        "org.apache.commons.logging.simplelog.log.org.apache.fontbox.ttf.CmapSubtable", "error");
  }

  @Option(
      names = {"-c", "--config"},
      description = "Configuration file path",
      required = true,
      defaultValue = "examples/project-1/config.yml")
  private String configLocation;

  @Option(
      names = {"--web-ui"},
      description =
          "Start a local web dashboard for viewing benchmark messages, tool calls, stages, and"
              + " output. Requires benchmark.enabled: true in the config to show any data.")
  private boolean webUiEnabled;

  @Option(
      names = {"--web-ui-port"},
      description = "Port for the web dashboard (default: ${DEFAULT-VALUE}).",
      defaultValue = "8080")
  private int webUiPort;

  static void main(String[] args) {
    int exitCode = new CommandLine(new Main()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public void run() {
    try {
      Config config = Config.loadFile(configLocation);
      String ll = config.getLogLevel();
      if (ll != null && !ll.isEmpty()) {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", ll.toUpperCase());
      }

      // Set the log file if log-to-file is enabled
      if (config.isLogToFile()
          && config.getLogFilePath() != null
          && !config.getLogFilePath().isEmpty()) {
        System.setProperty("org.slf4j.simpleLogger.logFile", config.getLogFilePath());
      }

      Logger logger = LoggerFactory.getLogger(Main.class);
      logger.info("Configuration loaded from: {}", configLocation);

      WebUiServer webUiServer = webUiEnabled ? new WebUiServer(config) : null;
      if (webUiServer != null) {
        startWebUi(webUiServer, logger);
      }

      try {
        OWLSDA app = new OWLSDA(config);
        app.run();
      } finally {
        if (webUiServer != null) {
          logger.info(
              "Run finished. Web UI still available at http://localhost:{} -- press Ctrl+C to"
                  + " exit.",
              webUiPort);
          awaitShutdown(webUiServer);
        }
      }
    } catch (IOException e) {
      Logger logger = LoggerFactory.getLogger(Main.class);
      logger.error("Failed to load configuration", e);
      throw new RuntimeException(e);
    }
  }

  private void startWebUi(WebUiServer webUiServer, Logger logger) {
    try {
      webUiServer.start(webUiPort);
      logger.info("Web UI dashboard available at http://localhost:{}", webUiPort);
    } catch (IOException e) {
      logger.error("Failed to start web UI on port {}", webUiPort, e);
      throw new RuntimeException(e);
    }
  }

  private void awaitShutdown(WebUiServer webUiServer) {
    CountDownLatch latch = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  webUiServer.stop();
                  latch.countDown();
                }));
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
