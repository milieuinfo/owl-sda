/**
 * Typed configuration loaded from a project's {@code config.yml}. {@link
 * be.vlaanderen.omgeving.owlsda.config.Config} is the root bean tree, populated by {@link
 * be.vlaanderen.omgeving.owlsda.config.YamlConfigLoader} via SnakeYAML with kebab-case YAML keys
 * mapped to camelCase fields. Field defaults double as the documented defaults in {@code
 * docs/guide/configuration.md}; keep the two in sync when changing either.
 */
package be.vlaanderen.omgeving.owlsda.config;
