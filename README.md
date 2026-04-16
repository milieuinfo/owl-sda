# OWL-SDA: A Multi-Agent System for Generating Synthetic Data to Support Ontology Evaluation

## Installation

> Currently only GitHub Copilot is supported as a large language model

1. Install GitHub Copilot CLI: https://github.com/features/copilot/cli
2. Sign-in to GitHub Copilot CLI: `gh copilot auth`

## Configuration

### User Context

You can provide custom context files to the language model by adding them to the configuration:

```yaml
user-context:
  - name: 'example file'
    path: 'example/input-example.txt'
  - name: 'additional context'
    path: 'path/to/another-file.ttl'
  - name: 'web reference'
    url: 'https://example.org/specification'
```

These context sources can be local files (`path`) or web pages (`url`). They are loaded and provided to both the generator and reviewer sessions, allowing you to give examples or additional information to guide the data generation process.

### Client Configuration

Configure the models and timeouts for each agent role:

```yaml
client:
  worker:
    model: "gpt-5-mini"
    timeout-ms: 350000
    between-message-timeout-ms: 200000
    batch-size: 1
    pool-count: 5
  supervisor:
    model: "gpt-5.4"
    timeout-ms: 360000
  reviewer:
    model: "claude-4.6-sonnet"
    timeout-ms: 360000
    max-review-attempts: 3
```


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

(c) 2025-2026: Maxim Van de Wynckel, Emmelien De Roock, and contributors. All rights reserved. See [LICENSE](./LICENSE) for details.

