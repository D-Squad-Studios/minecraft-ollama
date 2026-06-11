# Seed Data Generator

This guide covers pulling the Ollama embedding model and running the seed data generator tool.

## Pull the Embedding Model

The vector store uses the Ollama embedding model `nomic-embed-text`. Pull it once before running
seed generation or embeddings.

```bash
ollama pull nomic-embed-text
```

Verify Ollama is running:

```bash
curl http://localhost:11434/api/tags
```

## Run SeedDataGenerator

The tool can ingest individual files or directories (recursive). It supports `.txt` and `.json`.

### Gradle wrapper (recommended)

```bash
./gradlew seedData --args="--ingest tools/seed-documents"
```

Or using a project property:

```bash
./gradlew seedData -PseedArgs="--ingest tools/seed-documents/crafting_recipes.json"
```

### Direct Java (manual compile, optional)

Prefer the Gradle wrapper above. If you want to run it manually, compile against the main output
and use the correct classpath separator for your OS.

**Windows (PowerShell):**
```bash
./gradlew classes
# Note: manual compilation requires the full dependency classpath.
# This is brittle because any dependency change alters the classpath.
$deps = ./gradlew -q printSeedToolClasspath
$cp = "build/sourcesSets/main;$deps;tools"
javac -cp $cp tools/SeedDataGenerator.java
java -cp $cp net.kevinthedang.ollamamod.tools.SeedDataGenerator --ingest tools/seed-documents
```

**macOS/Linux (bash):**
```bash
./gradlew classes
# Note: manual compilation requires the full dependency classpath.
# This is brittle because any dependency change alters the classpath.
deps=$(./gradlew -q printSeedToolClasspath)
cp="build/sourcesSets/main:$deps:tools"
javac -cp "$cp" tools/SeedDataGenerator.java
java -cp "$cp" net.kevinthedang.ollamamod.tools.SeedDataGenerator --ingest tools/seed-documents
```

### Other commands

```bash
./gradlew seedData --args="--list"
./gradlew seedData --args="--clear"
./gradlew seedData --args="--ingest tools/seed-documents --output src/main/resources/ollamamod/seed/documents.store"
```

## Notes

- Output file default: `src/main/resources/ollamamod/seed/documents.store`
- Supported file types: `.txt`, `.json`
- Directories are ingested recursively.
