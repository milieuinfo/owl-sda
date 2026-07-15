package be.vlaanderen.omgeving.owlsda.ontology;

import be.vlaanderen.omgeving.owlsda.config.Config;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.vocabulary.OWL2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves and loads external ontology references (via {@code owl:imports} or namespace prefixes)
 * into the {@link Ontology}'s external model map. Supports HTTP fetching with optional mirroring,
 * file-based caching, and TTL-based cache expiry.
 */
public class OntologyExtractor {

  private static final Logger logger = LoggerFactory.getLogger(OntologyExtractor.class);
  private final HttpClient httpClient;
  private final Path cacheDir;
  private final Config config;
  private final Map<String, Model> inMemoryResolvedModels = new HashMap<>();
  private final Set<String> inMemoryFailedReferences = new HashSet<>();

  public OntologyExtractor(Config config) {
    this.config = config;
    var builder =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.getExtract().getConnectTimeoutMs()));
    if (config.getExtract().isFollowRedirects()) {
      builder.followRedirects(HttpClient.Redirect.NORMAL);
    }
    this.httpClient = builder.build();

    // initialize file cache directory if caching enabled
    Path dirPath = null;
    if (config.getExtract().isCacheEnabled()
        && config.getExtract().getCacheDir() != null
        && !config.getExtract().getCacheDir().isBlank()) {
      Path tmp = Paths.get(config.getExtract().getCacheDir());
      try {
        Files.createDirectories(tmp);
        dirPath = tmp;
      } catch (Exception e) {
        logger.warn(
            "Unable to create cache directory {}: {}",
            config.getExtract().getCacheDir(),
            e.getMessage());
      }
    }
    this.cacheDir = dirPath;
  }

  public void adapt(Ontology info) {
    if (info == null || info.getModel() == null) {
      return;
    }

    var externalReferences = collectExternalReferences(info);
    if (externalReferences.isEmpty()) {
      return;
    }

    var resolvedByNormalizedReference = new HashMap<String, Model>();
    var failedNormalizedReferences = new HashSet<String>();

    for (var reference : externalReferences) {
      resolveReference(info, reference, resolvedByNormalizedReference, failedNormalizedReferences);
    }
  }

  private Set<String> collectExternalReferences(Ontology info) {
    var externalReferences = new LinkedHashSet<String>();
    info.getModel().listStatements().toList().stream()
        .filter(statement -> statement.getPredicate().equals(OWL2.imports))
        .map(statement -> statement.getObject().toString())
        .forEach(externalReferences::add);
    externalReferences.addAll(info.getModel().getNsPrefixMap().values());
    return externalReferences;
  }

  private void resolveReference(
      Ontology info,
      String reference,
      Map<String, Model> resolvedByNormalizedReference,
      Set<String> failedNormalizedReferences) {
    if (reference == null
        || reference.isBlank()
        || info.getExternalModels().containsKey(reference)) {
      return;
    }

    var normalizedReference = normalizeReference(reference);
    if (failedNormalizedReferences.contains(normalizedReference)
        || inMemoryFailedReferences.contains(normalizedReference)) {
      return;
    }

    var model = resolveModel(info, reference, normalizedReference, resolvedByNormalizedReference);
    if (model != null) {
      rememberResolved(normalizedReference, model, resolvedByNormalizedReference);
      info.getExternalModels().put(reference, model);
      return;
    }

    logger.warn(
        "Could not resolve external ontology reference {} after exhausting cache, candidates,"
            + " and mirrors",
        reference);
    failedNormalizedReferences.add(normalizedReference);
    inMemoryFailedReferences.add(normalizedReference);
  }

  private Model resolveModel(
      Ontology info,
      String reference,
      String normalizedReference,
      Map<String, Model> resolvedByNormalizedReference) {
    var model = lookupResolvedModel(normalizedReference, resolvedByNormalizedReference);
    if (model != null) {
      return model;
    }

    model = findAlreadyLoadedModel(info.getExternalModels(), normalizedReference);
    if (model != null) {
      return model;
    }

    model = loadFromFileCache(reference);
    if (model != null) {
      return model;
    }

    return resolveFromCandidates(reference, normalizedReference, resolvedByNormalizedReference);
  }

  private Model lookupResolvedModel(
      String normalizedReference, Map<String, Model> resolvedByNormalizedReference) {
    var model = resolvedByNormalizedReference.get(normalizedReference);
    if (model != null) {
      return model;
    }
    return inMemoryResolvedModels.get(normalizedReference);
  }

  private Model resolveFromCandidates(
      String reference,
      String normalizedReference,
      Map<String, Model> resolvedByNormalizedReference) {
    var candidates = new LinkedHashSet<String>();
    candidates.add(reference);
    var mirrors = findMirrorsFor(reference);
    if (mirrors != null && !mirrors.isEmpty()) {
      candidates.addAll(mirrors);
    }

    for (var candidate : candidates) {
      var candidateNormalized = normalizeReference(candidate);
      var candidateModel = lookupResolvedModel(candidateNormalized, resolvedByNormalizedReference);
      if (candidateModel == null) {
        candidateModel = loadFromFileCache(candidate);
      }
      if (candidateModel != null) {
        rememberResolved(candidateNormalized, candidateModel, resolvedByNormalizedReference);
        return candidateModel;
      }

      var fetched = fetchExternalOntology(candidate);
      if (fetched != null) {
        rememberResolved(candidateNormalized, fetched, resolvedByNormalizedReference);
        putInFileCacheQuietly(
            reference, normalizedReference, candidate, candidateNormalized, fetched);
        return fetched;
      }

      logger.debug("Failed to fetch candidate {} for original reference {}", candidate, reference);
    }

    return null;
  }

  private void rememberResolved(
      String normalizedReference, Model model, Map<String, Model> resolvedByNormalizedReference) {
    resolvedByNormalizedReference.put(normalizedReference, model);
    inMemoryResolvedModels.put(normalizedReference, model);
    inMemoryFailedReferences.remove(normalizedReference);
  }

  private void putInFileCacheQuietly(
      String reference,
      String normalizedReference,
      String candidate,
      String candidateNormalized,
      Model model) {
    if (!config.getExtract().isCacheEnabled() || cacheDir == null) {
      return;
    }
    try {
      // Persist under both keys so future runs can hit cache regardless of which URI appears.
      putInFileCache(reference, model);
      if (!candidateNormalized.equals(normalizedReference)) {
        putInFileCache(candidate, model);
      }
    } catch (Exception e) {
      logger.warn("Failed to write file cache for {}: {}", reference, e.getMessage());
    }
  }

  private List<String> findMirrorsFor(String reference) {
    var list = config.getExtract().getMirrors();
    if (list == null || list.isEmpty()) {
      return List.of();
    }

    var normalizedReference = normalizeReference(reference);

    for (var entry : list) {
      var uri = entry.getUri();
      if (uri == null || uri.isBlank()) {
        continue;
      }
      if (normalizeReference(uri).equals(normalizedReference)) {
        return entry.getResolvedMirrors();
      }
    }

    for (var entry : list) {
      var uri = entry.getUri();
      if (uri == null || uri.isBlank()) {
        continue;
      }
      if (normalizedReference.startsWith(normalizeReference(uri))) {
        return entry.getResolvedMirrors();
      }
    }

    return List.of();
  }

  private void putInFileCache(String reference, Model model) throws Exception {
    if (cacheDir == null) {
      return;
    }
    var file = cacheFileFor(reference);
    var tmp = cacheDir.resolve(file.getFileName().toString() + ".tmp");
    try {
      try (var out = Files.newOutputStream(tmp)) {
        model.write(out, config.getExtract().getCacheFormat());
      }
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException amnse) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
      try {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
      } catch (Exception e) {
        /* ignore */
      }
    } finally {
      try {
        if (Files.exists(tmp)) {
          Files.deleteIfExists(tmp);
        }
      } catch (Exception e) {
        /* ignore */
      }
    }
  }

  private Model loadModelFromFile(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      var model = ModelFactory.createDefaultModel();
      try {
        model.read(in, null, config.getExtract().getCacheFormat());
        if (!model.isEmpty()) {
          return model;
        }
      } catch (Exception ex) {
        logger.debug(
            "Failed to parse cached model {} with format {}: {}",
            file,
            config.getExtract().getCacheFormat(),
            ex.getMessage());
      }
    } catch (Exception e) {
      logger.warn("Failed to read cached model file {}: {}", file, e.getMessage());
    }
    return null;
  }

  private Path cacheFileFor(String reference) throws Exception {
    var hash = sha256Hex(normalizeReference(reference));
    var ext = ".ttl";
    return cacheDir.resolve(hash + ext);
  }

  private Path legacyCacheFileFor(String reference) throws Exception {
    var hash = sha256Hex(reference);
    var ext = ".ttl";
    return cacheDir.resolve(hash + ext);
  }

  private boolean isCacheEntryFresh(Path file) throws IOException {
    var ttlMs = config.getExtract().getCacheTtlMs();
    if (ttlMs <= 0) {
      return true;
    }
    var lastModified = Files.getLastModifiedTime(file).toMillis();
    if ((System.currentTimeMillis() - lastModified) <= ttlMs) {
      return true;
    }
    logger.debug("File cache expired for {}", file);
    return false;
  }

  private Model findAlreadyLoadedModel(
      Map<String, Model> externalModels, String normalizedReference) {
    for (var entry : externalModels.entrySet()) {
      if (normalizeReference(entry.getKey()).equals(normalizedReference)) {
        return entry.getValue();
      }
    }
    return null;
  }

  private String normalizeReference(String uri) {
    if (uri == null) {
      return "";
    }
    return stripTrailingSlash(stripFragment(uri.trim()));
  }

  private static String stripFragment(String uri) {
    var idx = uri.indexOf('#');
    return idx > 0 ? uri.substring(0, idx) : uri;
  }

  private static String stripTrailingSlash(String uri) {
    return uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
  }

  private static String sha256Hex(String input) throws Exception {
    var md = MessageDigest.getInstance("SHA-256");
    var digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
    var sb = new StringBuilder();
    for (byte b : digest) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private Model fetchExternalOntology(String reference) {
    logger.info("Fetching external ontology {}", reference);
    String lastError = null;
    int maxAttempts = Math.max(1, config.getExtract().getMaxRetries() + 1);
    final int maxRedirects = 5;

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      String current = reference;
      int redirects = 0;
      try {
        while (true) {
          var request =
              HttpRequest.newBuilder()
                  .uri(URI.create(current))
                  .timeout(Duration.ofMillis(config.getExtract().getReadTimeoutMs()))
                  .header("User-Agent", config.getExtract().getUserAgent())
                  .GET()
                  .build();

          HttpResponse<byte[]> response =
              httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
          int status = response.statusCode();

          if (status >= 200 && status < 300) {
            var body = response.body();
            var contentType = response.headers().firstValue("content-type");
            var model = parseModelFromBytes(reference, body, contentType.orElse(null));
            if (model != null) {
              return model;
            }
            lastError =
                String.format(
                    "Unable to parse external ontology content from %s (content-type=%s)",
                    current, contentType.orElse("<none>"));
            break; // parsing failed, don't retry this candidate further
          }

          if (status >= 300 && status < 400) {
            if (redirects >= maxRedirects) {
              lastError = "Too many redirects";
              break;
            }
            var loc = response.headers().firstValue("location");
            if (loc.isPresent()) {
              try {
                current = URI.create(current).resolve(loc.get()).toString();
                redirects++;
                // debug log only for redirects
                logger.debug("Redirecting to {} ({} of {})", current, redirects, maxRedirects);
                continue; // follow redirect
              } catch (Exception ex) {
                lastError = "Invalid redirect location: " + ex.getMessage();
                break;
              }
            } else {
              lastError = "Redirect response with no Location header";
              break;
            }
          }

          // other non-success status -> don't retry
          lastError = "Non-success HTTP status " + status;
          break;
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        logger.warn("Interrupted while fetching {}", reference);
        return null;
      } catch (IOException ioe) {
        lastError = ioe.getMessage();
        logger.debug(
            "Network error fetching {} (attempt {}): {}", reference, attempt, ioe.getMessage());
        // try next attempt if any
        if (attempt == maxAttempts) {
          break;
        } else {
          continue;
        }
      }
      // if we reach here without returning, parsing or redirects failed => don't retry further
      break;
    }

    logger.warn(
        "Failed fetching external ontology {}: {}",
        reference,
        lastError == null ? "unknown error" : lastError);
    return null;
  }

  private Model parseModelFromBytes(String reference, byte[] body, String contentType) {
    // Prefer explicit content-type mapping
    String mime = contentType == null ? null : contentType.split(";")[0].trim().toLowerCase();
    String lang = mapMimeToLang(mime);
    if (lang != null) {
      var parsed = parseWithLang(body, lang);
      if (parsed != null) {
        return parsed;
      }
    }
    // If content-type is missing or unrecognized, infer from file extension
    lang =
        switch (reference) {
          case String r when r.endsWith(".json") || r.endsWith(".jsonld") -> "JSON-LD";
          case String r when r.endsWith(".rdf") || r.endsWith(".xml") -> "RDF/XML";
          case String r when r.endsWith(".nt") || r.endsWith(".ntriples") -> "N-TRIPLES";
          case String r when r.endsWith(".n3") -> "N3";
          default -> "TURTLE";
        };
    return parseWithLang(body, lang);
  }

  private String mapMimeToLang(String mime) {
    if (mime == null) {
      return null;
    }
    return switch (mime) {
      case "text/turtle", "application/x-turtle", "application/turtle", "text/ttl" -> "TURTLE";
      case "application/ld+json", "application/json" -> "JSON-LD";
      case "application/rdf+xml", "application/xml", "text/xml" -> "RDF/XML";
      case "application/n-triples", "application/ntriples", "text/plain" -> "N-TRIPLES";
      case "text/n3" -> "N3";
      default -> null;
    };
  }

  private Model parseWithLang(byte[] body, String lang) {
    try (var in = new ByteArrayInputStream(body)) {
      var model = ModelFactory.createDefaultModel();
      try {
        model.read(in, null, lang);
        if (!model.isEmpty()) {
          return model;
        }
      } catch (Exception ex) {
        logger.debug("Failed parsing as {}: {}", lang, ex.getMessage());
      }
    } catch (Exception ex) {
      // ignore
    }
    return null;
  }

  private Model loadFromFileCache(String reference) {
    if (!config.getExtract().isCacheEnabled() || cacheDir == null) {
      return null;
    }
    try {
      var file = cacheFileFor(reference);
      if (Files.exists(file) && isCacheEntryFresh(file)) {
        var model = loadModelFromFile(file);
        if (model != null) {
          logger.debug("Loaded external ontology from file cache for {}", reference);
          return model;
        }
      }

      // Backward compatibility with previous (non-normalized) keying.
      var legacyFile = legacyCacheFileFor(reference);
      if (Files.exists(legacyFile) && isCacheEntryFresh(legacyFile)) {
        var model = loadModelFromFile(legacyFile);
        if (model != null) {
          logger.debug("Loaded external ontology from legacy file cache for {}", reference);
          return model;
        }
      }
    } catch (Exception e) {
      logger.warn("Error reading file cache for {}: {}", reference, e.getMessage());
    }
    return null;
  }
}
