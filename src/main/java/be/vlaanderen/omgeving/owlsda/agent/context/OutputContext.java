package be.vlaanderen.omgeving.owlsda.agent.context;

public class OutputContext extends Context {
  public OutputContext(String content) {
    super();
    this.setType("text/turtle");
    this.setName("Generated output data");
    this.setContent(content);
  }
}
