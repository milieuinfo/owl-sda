# Installation

## Prerequisites

- **JDK 25 or later.** The project compiles at source/target 25 with `--enable-preview`, and its entry point is a non-public `static void main`, which only launches on Java 25 (JEP 512). Java 21 will not build or run it.
- **Maven 3.9+**
- **An LLM provider**: a GitHub Copilot subscription with the Copilot CLI (the default), a running Ollama server, or any OpenAI-compatible endpoint. See [Choosing a Provider](#choosing-a-provider) below.

Check your toolchain:

```bash
java -version    # must report 25 or later
mvn -version
```

## Build from Source

```bash
git clone https://github.com/milieuinfo/owl-sda.git
cd owl-sda
mvn -DskipTests clean package
```

The build produces a single self-contained jar with all dependencies bundled:

```
target/owlsda.jar
```

Run it with:

```bash
java -jar target/owlsda.jar --config examples/project-1/config.yml
```

`--config` is required and accepts a plain path or a `file:` / `classpath:` prefix - see [Command Line Interface](./cli) for the full option list and how the location is resolved.

## Choosing a Provider

`client.provider` selects which backend the agents talk to. It can be set globally and overridden per role (`client.worker`, `client.supervisor`, `client.reviewer`), so you can, for example, run workers on a local model and the reviewer on Copilot. Full key reference in [Configuration](./configuration).

| Provider | What you need |
|----------|---------------|
| `copilot` *(default)* | The standalone GitHub Copilot CLI installed, on your `PATH`, and signed in. |
| `ollama` | A reachable Ollama server and the models you reference pulled on it. |
| `openai-compatible` | A base URL for the endpoint plus an API key. |

### GitHub Copilot CLI (default)

OWL-SDA drives the **standalone GitHub Copilot CLI** through the `copilot-sdk` library. This is the `copilot` binary from <https://github.com/features/copilot/cli> - *not* the older `gh-copilot` extension for the GitHub CLI. The SDK launches `copilot` as a subprocess, so the binary must be on your `PATH`.

Install it with any one of these:

```bash
npm install -g @github/copilot          # any platform; needs Node.js 22+
curl -fsSL https://gh.io/copilot-install | bash   # macOS / Linux
brew install --cask copilot-cli         # macOS / Linux (Homebrew)
winget install GitHub.Copilot           # Windows
```

Then sign in and confirm the CLI works:

```bash
copilot login     # OAuth flow; opens a browser, falls back to a device code
copilot --version
```

`copilot login` stores the token in your system credential store, falling back to a plaintext file under `~/.copilot/` when no credential store is available. For headless or CI use you can skip the interactive login and export a token instead - the CLI checks `COPILOT_GITHUB_TOKEN`, then `GH_TOKEN`, then `GITHUB_TOKEN`. Fine-grained personal access tokens need the "Copilot Requests" permission; classic tokens are not supported.

Then point OWL-SDA at it:

```yaml
client:
  provider: "copilot"
  worker:
    model: "gpt-5.4"
```

### Ollama

Run an Ollama server (locally or elsewhere on your network), pull the models you plan to reference, and set the base URL:

```yaml
client:
  provider: "ollama"
  ollama:
    base-url: "http://localhost:11434"
    think: true
  worker:
    model: "qwen3.5:9b"
```

No API key is involved. `base-url` defaults to `http://localhost:11434`, so you only need it when the server lives elsewhere.

### OpenAI-Compatible Endpoints

Anything that speaks the OpenAI chat completions API - OpenAI itself, Azure OpenAI, or a self-hosted gateway - works with `openai-compatible`. Supply the base URL and a key:

```yaml
client:
  provider: "openai-compatible"
  openai-compatible:
    base-url: "https://api.openai.com/v1"
    api-key: ""            # leave blank to use the OPENAI_API_KEY environment variable
  worker:
    model: "gpt-5.4"
```

When `api-key` is unset or blank, the key is read from the `OPENAI_API_KEY` environment variable. This is the only environment variable OWL-SDA itself reads.

## Next Steps

- [Quickstart](./quickstart) - run your first generation.
- [Configuration](./configuration) - the full configuration reference.
- [Command Line Interface](./cli) - options, exit codes, and config resolution.
