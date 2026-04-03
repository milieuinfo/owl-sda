# Installation

## Prerequisites

- Java 21 or later
- Maven 3.9+
- A [GitHub Copilot](https://github.com/features/copilot) subscription with the Copilot CLI installed

## Install GitHub Copilot CLI

```bash
# Install the GitHub CLI
brew install gh          # macOS
# or follow https://cli.github.com/manual/installation

# Install the Copilot extension
gh extension install github/gh-copilot

# Authenticate
gh auth login
```

## Build from Source

```bash
git clone https://github.com/your-org/OWL-SDA.git
cd OWL-SDA
mvn package -DskipTests
```

The resulting JAR will be at `target/OWL-SDA-*.jar`.

## Running

```bash
java -jar target/OWL-SDA-*.jar --config examples/project-1/config.yml
```

The `--config` option accepts:

| Prefix | Example | Behaviour |
|--------|---------|-----------|
| *(none)* | `config.yml` | Tries classpath first, then filesystem |
| `file:` | `file:config.yml` | Filesystem only |
| `classpath:` | `classpath:config/app.yml` | Classpath only |

