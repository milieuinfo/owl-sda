# OWL-SDA: A tool for OWL-based data generation using Large Language Models

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
```

These context files will be loaded and provided to both the generator and reviewer sessions, allowing you to give examples or additional information to guide the data generation process.

### Client Configuration

Configure the models and timeouts for the generator and reviewer:

```yaml
client:
  generator:
    model: "gpt-5.1-mini"
    timeout-ms: 360000
  reviewer:
    model: "claude-3.5-sonnet"
    timeout-ms: 60000
```


## Contributing

## License

This project is released under the **GNU General Public License v3.0 (GPL-3.0)**.

- Full text: `LICENSE`
- Documentation: `docs/guide/license.md`
