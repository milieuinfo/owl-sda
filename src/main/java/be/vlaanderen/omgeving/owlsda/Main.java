package be.vlaanderen.omgeving.owlsda;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
    name = "owlsda",
    description = "OWL-SDA | OWL Synthetic Data AI-Agent",
    version = "1.0-SNAPSHOT"
)
public class Main implements Runnable {

  static {
    // Configure noisy FontBox logger before any logger backend initializes.
    System.setProperty(
        "org.slf4j.simpleLogger.log.org.apache.fontbox.ttf.CmapSubtable",
        "error"
    );
    System.setProperty(
        "org.apache.commons.logging.simplelog.log.org.apache.fontbox.ttf.CmapSubtable",
        "error"
    );
  }

  @Option(
      names = {"-c", "--config"},
      description = "Configuration file path",
      required = true,
      defaultValue = "examples/project-2/config.yml"
  )
  private String configLocation;

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
      if (config.isLogToFile() && config.getLogFilePath() != null && !config.getLogFilePath().isEmpty()) {
        System.setProperty("org.slf4j.simpleLogger.logFile", config.getLogFilePath());
      }

      Logger logger = LoggerFactory.getLogger(Main.class);
      logger.info("Configuration loaded from: {}", configLocation);
      OWLSDA app = new OWLSDA(config);
      app.run();
    } catch (IOException e) {
      Logger logger = LoggerFactory.getLogger(Main.class);
      logger.error("Failed to load configuration", e);
      throw new RuntimeException(e);
    }
  }
}
