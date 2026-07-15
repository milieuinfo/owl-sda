/**
 * Loads and prepares the domain ontology and its SHACL shapes. {@link
 * be.vlaanderen.omgeving.owlsda.ontology.Ontology} wraps the base Jena {@code Model}; {@link
 * be.vlaanderen.omgeving.owlsda.ontology.OntologyExtractor} resolves {@code owl:imports}/namespace
 * references over HTTP with mirror fallback and file caching; {@link
 * be.vlaanderen.omgeving.owlsda.ontology.OntologyReasoner} applies OWL/RDFS/rule-based inference;
 * {@link be.vlaanderen.omgeving.owlsda.ontology.Shacl} loads (or generates) and evaluates the SHACL
 * shapes that define what the generation workflow must produce.
 */
package be.vlaanderen.omgeving.owlsda.ontology;
