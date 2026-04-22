# Contributing to Boomerang

Thanks for your interest. Contributions of all kinds are welcome — bug fixes, new features, SDK improvements, docs, and tests.

## What we're working on

Check the [open issues](https://github.com/sameerchereddy/boomerang/issues) for things that need help. Issues labelled `good first issue` are a good starting point.

## Local setup

### Prerequisites

- Java 17+
- Maven 3.8+
- Docker (for Redis via Testcontainers)
- Node.js 18+ (for `boomerang-node`)
- Python 3.9+ (for `boomerang-python`)
- Go 1.21+ (for `boomerang-go`)
- .NET 8+ (for `boomerang-dotnet`)

You only need the runtimes for the part you're working on.

### Running the Java starter

```bash
cd boomerang-starter
mvn verify
```

Tests spin up Redis via Testcontainers automatically — no manual Redis setup needed.

### Running the standalone server locally

```bash
docker compose -f boomerang-standalone/docker-compose.yml up
```

Server starts on `http://localhost:8080`.

### Running SDK tests

```bash
# Node.js
cd boomerang-node && npm install && npm test

# Python
cd boomerang-python && pip install -e ".[dev]" && pytest

# Go
cd boomerang-go && go test ./...

# .NET
cd boomerang-dotnet && dotnet test
```

## Making a change

1. Fork the repo and create a branch: `git checkout -b your-feature`
2. Make your changes
3. Run the tests for the module you changed
4. Open a PR — fill in the PR template

## PR guidelines

- Keep PRs focused. One thing per PR.
- If you're fixing a bug, add a test that would have caught it.
- If you're adding a feature, update the relevant README.
- Don't bump version numbers — maintainers handle releases.

## Questions?

Open a [Discussion](https://github.com/sameerchereddy/boomerang/discussions) or comment on the relevant issue.
