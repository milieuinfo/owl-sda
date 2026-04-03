# External Ontologies

OWL ontologies frequently reference external vocabularies via `owl:imports` statements or namespace prefixes. OWL-SDA can automatically fetch, parse, and cache these external models so that the reasoner and SHACL generator have access to all imported definitions.

## Configuration

```yaml
extract:
  connect-timeout-ms: 2000
  read-timeout-ms: 5000
  max-retries: 1
  follow-redirects: true
  user-agent: "owlsda/1.0"
  cache-enabled: true
  cache-ttl-ms: 3600000      # 1 hour
  cache-max-entries: 100
  cache-dir: "target/cache/ontology-extract-external"
  cache-format: "TURTLE"
  mirrors: []
```

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `connect-timeout-ms` | int | `2000` | HTTP connection timeout in milliseconds. |
| `read-timeout-ms` | int | `5000` | HTTP read timeout in milliseconds. |
| `max-retries` | int | `1` | Number of additional attempts after the first failure. |
| `follow-redirects` | boolean | `true` | Whether HTTP redirects are followed automatically. |
| `user-agent` | string | `owlsda/1.0` | `User-Agent` header sent with HTTP requests. |
| `cache-enabled` | boolean | `true` | Enable on-disk caching of resolved ontologies. |
| `cache-ttl-ms` | long | `3600000` | Maximum age of a cached entry in milliseconds. `0` = never expire. |
| `cache-max-entries` | int | `100` | Maximum number of entries in the in-memory cache. |
| `cache-dir` | string | `target/cache/…` | Directory for on-disk cached ontology files. |
| `cache-format` | string | `TURTLE` | RDF serialisation format used for the on-disk cache. |
| `mirrors` | list | `[]` | URI-to-mirror mappings. See [Mirrors](#mirrors) below. |

## How It Works

When the ontology is loaded, OWL-SDA scans for:

1. **`owl:imports` triples** — direct ontology imports declared in the file.
2. **Namespace prefix URIs** — all namespace prefixes defined in the ontology.

For each URI it tries to resolve a model in this order:

1. In-memory cache (within the current run).
2. On-disk file cache (`cache-dir`).
3. HTTP fetch (with retry and redirect support).

Successfully fetched models are written to the on-disk cache for future runs.

## Mirrors

Some ontology URIs are unreliable or offline. You can map any URI to one or more mirror URLs:

```yaml
extract:
  mirrors:
    - uri: "http://www.w3.org/ns/prov#"
      mirrors:
        - "https://www.w3.org/ns/prov-o"
    - uri: "http://xmlns.com/foaf/0.1/"
      mirror: "https://xmlns.com/foaf/spec/index.rdf"
```

Both `mirrors` (list) and `mirror` (single string) are accepted. Prefix matching is also supported, so a mirror entry for `http://example.org/` applies to any URI starting with that prefix.

## Supported Formats

The extractor recognises the `Content-Type` header returned by the server and falls back to filename extension heuristics:

| Content-Type / extension | Format |
|--------------------------|--------|
| `text/turtle`, `.ttl` | Turtle |
| `application/ld+json`, `.jsonld` | JSON-LD |
| `application/rdf+xml`, `.rdf`, `.xml` | RDF/XML |
| `application/n-triples`, `.nt` | N-Triples |
| `text/n3`, `.n3` | N3 |

