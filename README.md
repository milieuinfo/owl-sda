# OWL-SDA: A Multi-Agent System for Generating Synthetic Data to Support Ontology Evaluation

OWL-SDA generates synthetic RDF instance data from an OWL ontology using a supervisor/worker/reviewer team of LLM agents, validating the result against SHACL shapes derived from that ontology.

📖 **Documentation: <https://milieuinfo.github.io/owl-sda/>**

## Prerequisites

- **JDK 25 or later** (the project builds with `--enable-preview` and a JEP 512 entry point; Java 21 will not work)
- **Maven 3.9+**
- An LLM provider: the standalone [GitHub Copilot CLI](https://github.com/features/copilot/cli) (default), an Ollama server, or any OpenAI-compatible endpoint. Install and sign-in steps are in [docs/guide/installation.md](./docs/guide/installation.md).

## Build

```bash
mvn -DskipTests clean package
```

This produces a single runnable jar with all dependencies bundled at `target/owlsda.jar`.

## Run

```bash
java -jar target/owlsda.jar --config examples/project-1/config.yml
```

`--config` is required - there is no default. The example above generates instance data for a glass production facility ontology and writes it to the `output-path` set in `examples/project-1/config.yml`, which is also where you set the model provider, models, and timeouts.

Add `--web-ui` (optionally with `--web-ui-port`, default `8080`) to watch the run live in a browser - messages, tool calls, stages, output, and the triple store. It needs `benchmark.enabled: true` in the configuration:

```bash
java -jar target/owlsda.jar --config examples/project-2/config.yml --web-ui
```

## Documentation

- [Installation](./docs/guide/installation.md) - prerequisites, build, and provider setup
- [Quick Start](./docs/guide/quickstart.md)
- [CLI Reference](./docs/guide/cli.md)
- [Configuration Reference](./docs/guide/configuration.md)
- [Web UI](./docs/guide/web-ui.md) and [Benchmarking](./docs/guide/benchmarking.md)
- [Architecture](./docs/guide/architecture.md) and [Examples](./docs/guide/examples.md)

## Contributing

Contributions are welcome. To keep reviews fast and changes predictable, please follow this workflow:

1. Fork the repository and create a feature branch from `main`.
2. Keep changes scoped to one concern per pull request.
3. Use clear commit messages in the imperative mood (for example: `Add support for local agents`).
4. Run local checks before opening a pull request.
5. Write unit and integration tests for new features or bug fixes.
6. Open a pull request with a short problem statement, approach, and test evidence.

### Local Quality Checks

Run the full Maven validation pipeline before submitting:

```bash
mvn clean verify
```

If you are iterating quickly, you can run tests only:

```bash
mvn test
```

### Pull Request Checklist

- [ ] The build passes locally (`mvn clean verify`).
- [ ] New behavior is covered by tests where relevant.
- [ ] Existing tests remain green.
- [ ] Configuration or behavior changes are reflected in `examples/` when applicable.
- [ ] The pull request description explains why the change is needed.

## License

This project is released under the **GNU General Public License v3.0 (GPL-3.0)** and is further detailed in [LICENSE](./LICENSE). By contributing to this project, you agree that your contributions will be licensed under the same terms. Please review the license for more information on your rights and responsibilities when using or contributing to this software.

(c) 2025-2026: Vlaamse Overheid, Maxim Van de Wynckel, Emmelien De Roock, and contributors. All rights reserved. See [LICENSE](./LICENSE) for details.
