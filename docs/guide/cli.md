# Command Line Interface

OWL-SDA ships as a single executable jar at `target/owlsda.jar` (see [Installation](./installation) for how to build it). Everything the tool does is driven by one command with a handful of options - there are no subcommands and no positional arguments.

Run it with a Java 25 runtime:

```bash
java -jar target/owlsda.jar --config examples/project-1/config.yml
```

## Synopsis

```
Usage: owlsda [-hV] [--web-ui] -c=<configLocation> [--web-ui-port=<webUiPort>]
```

| Option | Default | Description |
|--------|---------|-------------|
| `-c`, `--config=<location>` | *(none - required)* | Location of the YAML configuration file. Accepts a plain path or an explicit `file:` / `classpath:` prefix. |
| `--web-ui` | `false` | Start the local web dashboard alongside the run. |
| `--web-ui-port=<port>` | `8080` | Port the dashboard listens on. |
| `-h`, `--help` | | Print the usage message and exit. |
| `-V`, `--version` | | Print the version (`1.0-SNAPSHOT`) and exit. |

```bash
java -jar target/owlsda.jar --help
java -jar target/owlsda.jar --version
```

## `--config` Is Required

There is no default configuration file. Running the jar with no arguments is a usage error - it prints the missing-option message plus the usage block and exits with code `2` without contacting any model:

```bash
$ java -jar target/owlsda.jar
Missing required option: '--config=<configLocation>'
Usage: owlsda [-hV] [--web-ui] -c=<configLocation> [--web-ui-port=<webUiPort>]
...
```

This is deliberate. Earlier versions defaulted to `examples/project-1/config.yml`, so a bare `java -jar target/owlsda.jar` silently started a real run: it overwrote that example's outputs and spent real LLM API tokens. Always name the configuration you mean to run.

## How the Configuration Location Is Resolved

The value of `--config` is resolved in one of three ways, depending on its prefix:

| Prefix | Example | Behaviour |
|--------|---------|-----------|
| *(none)* | `examples/project-1/config.yml` | Tries the classpath first; if no such resource exists, falls back to the filesystem path. |
| `file:` | `file:examples/project-1/config.yml` | Filesystem only. Use this when a classpath resource of the same name might shadow your file. |
| `classpath:` | `classpath:config/app.yml` | Classpath only. Fails if the resource is not bundled. |

Relative filesystem paths are resolved against the current working directory, so run the jar from the repository root when using the paths in `examples/`.

See [Configuration](./configuration) for everything the file itself can contain.

## Web Dashboard

`--web-ui` starts a local dashboard that shows the run as it happens - messages, tool calls, stages, output, and the triple store:

```bash
java -jar target/owlsda.jar -c examples/project-2/config.yml --web-ui
java -jar target/owlsda.jar -c examples/project-2/config.yml --web-ui --web-ui-port 9090
```

The dashboard reads the benchmark snapshot directory from disk, so it needs `benchmark.enabled: true` in your configuration; without it there is nothing to show. The server stays up after the pipeline finishes so you can review the completed run - stop it with Ctrl+C. See [Web UI](./web-ui) for what the dashboard exposes, and [Benchmarking](./benchmarking) for the data behind it.

## Environment Variables

OWL-SDA itself reads exactly one environment variable:

| Variable | Used by | Description |
|----------|---------|-------------|
| `OPENAI_API_KEY` | `client.provider: "openai-compatible"` | Fallback API key when `client.openai-compatible.api-key` is unset or blank. |

Nothing else is read from the environment. When you use the default `copilot` provider, authentication is handled by the GitHub Copilot CLI itself (which has its own token variables) - see [Installation](./installation).

## Exit Codes

| Code | Meaning |
|------|---------|
| `0` | The run completed, or `--help` / `--version` was printed. |
| `1` | The run failed - for example the configuration file could not be read. |
| `2` | Usage error - a missing or unknown option. |
