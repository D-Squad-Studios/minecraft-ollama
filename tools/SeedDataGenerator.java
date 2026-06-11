package net.kevinthedang.ollamamod.tools;

import net.kevinthedang.ollamamod.vectorstore.chunker.JsonChunker;
import net.kevinthedang.ollamamod.vectorstore.chunker.TextChunker;
import net.kevinthedang.ollamamod.vectorstore.embedding.EmbeddingService;
import net.kevinthedang.ollamamod.vectorstore.embedding.OllamaEmbeddingService;
import net.kevinthedang.ollamamod.vectorstore.model.VectorDocument;
import net.kevinthedang.ollamamod.vectorstore.model.VectorMetadata;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class SeedDataGenerator {
    private static final String DEFAULT_STORE_PATH =
        "src/main/resources/ollamamod/seed/documents.store";

    private static final String DEFAULT_MODEL = "nomic-embed-text";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";

    private final EmbeddingService embeddingService;
    private final TextChunker textChunker;
    private final JsonChunker jsonChunker;

    // Entry point for CLI usage (also used by the Gradle seedData task).
    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 0 || hasFlag(arguments, "--help")) {
            printUsage();
            return;
        }

        ParsedArguments parsedArguments = ParsedArguments.from(arguments);
        Path storePath = Paths.get(parsedArguments.outputPath);

        if (parsedArguments.shouldClear) {
            clearStore(storePath);
            System.out.println("Cleared store: " + storePath);
            return;
        }

        if (parsedArguments.shouldList) {
            listStore(storePath);
            return;
        }

        if (!parsedArguments.ingestTargets.isEmpty()) {
            SeedDataGenerator generator = new SeedDataGenerator(
                parsedArguments.ollamaBaseUrl,
                parsedArguments.embeddingModel
            );

            List<Path> ingestFiles = expandPaths(parsedArguments.ingestTargets);
            if (ingestFiles.isEmpty()) {
                System.err.println("No ingestable files found.");
                return;
            }

            List<VectorDocument> storeDocuments = loadStore(storePath);
            int totalChunksAdded = 0;
            for (Path ingestFile : ingestFiles) {
                totalChunksAdded += generator.ingestFile(ingestFile, storeDocuments);
            }

            persistStore(storePath, storeDocuments);
            System.out.println("Ingest complete. Added " + totalChunksAdded
                + " chunks. Store size=" + storeDocuments.size());
            return;
        }

        System.err.println("No action specified. Use --ingest, --clear, or --list.");
        printUsage();
    }

    // Creates a generator with the target Ollama base URL and embedding model.
    public SeedDataGenerator(String ollamaBaseUrl, String embeddingModel) {
        this.embeddingService = new OllamaEmbeddingService(ollamaBaseUrl, embeddingModel);
        this.textChunker = new TextChunker();
        this.jsonChunker = new JsonChunker();
    }

    // Reads a file, chunks it, embeds each chunk, and appends to the store list.
    private int ingestFile(Path inputFile, List<VectorDocument> storeDocuments) {
        String lowerCaseName = inputFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(lowerCaseName.endsWith(".txt") || lowerCaseName.endsWith(".json"))) {
            System.out.println("Skipping unsupported file: " + inputFile);
            return 0;
        }

        String fileContent;
        try {
            fileContent = Files.readString(inputFile, StandardCharsets.UTF_8);
        } catch (IOException ioException) {
            System.err.println("Failed to read file: " + inputFile + " - " + ioException.getMessage());
            return 0;
        }

        List<String> contentChunks = lowerCaseName.endsWith(".json")
            ? jsonChunker.chunk(fileContent)
            : textChunker.chunk(fileContent);

        int chunksAdded = 0;
        for (int chunkIndex = 0; chunkIndex < contentChunks.size(); chunkIndex++) {
            String chunkContent = contentChunks.get(chunkIndex);
            float[] embeddingVector;
            try {
                embeddingVector = embeddingService.embed(chunkContent).join();
            } catch (Exception exception) {
                System.err.println("Embedding failed for " + inputFile + " chunk "
                    + chunkIndex + ": " + exception.getMessage());
                continue;
            }

            VectorMetadata metadata = VectorMetadata.document()
                .withChunk(chunkIndex, contentChunks.size());
            VectorDocument seedDocument = new VectorDocument(
                UUID.randomUUID().toString(),
                chunkContent,
                embeddingVector,
                metadata
            );
            storeDocuments.add(seedDocument);
            chunksAdded++;
        }

        System.out.println("Ingested " + inputFile + " -> " + chunksAdded + " chunks");
        return chunksAdded;
    }

    // Writes the in-memory store list to disk in a compact binary format.
    private static void persistStore(Path storePath, List<VectorDocument> storeDocuments) throws IOException {
        Files.createDirectories(storePath.toAbsolutePath().getParent());
        try (DataOutputStream outputStream = new DataOutputStream(
            new BufferedOutputStream(Files.newOutputStream(storePath)))) {
            outputStream.writeInt(storeDocuments.size());
            for (VectorDocument document : storeDocuments) {
                document.writeTo(outputStream);
            }
        }
    }

    // Loads the store from disk if it exists, otherwise returns an empty list.
    private static List<VectorDocument> loadStore(Path storePath) {
        if (!Files.exists(storePath)) return new ArrayList<>();

        List<VectorDocument> storeDocuments = new ArrayList<>();
        try (DataInputStream inputStream = new DataInputStream(
            new BufferedInputStream(Files.newInputStream(storePath)))) {
            int documentCount = inputStream.readInt();
            for (int index = 0; index < documentCount; index++) {
                storeDocuments.add(VectorDocument.readFrom(inputStream));
            }
        } catch (EOFException eofException) {
            System.err.println("Store file appears truncated; loading what we can.");
        } catch (IOException ioException) {
            System.err.println("Failed to load store: " + ioException.getMessage());
        }
        return storeDocuments;
    }

    // Deletes the store file if it exists.
    private static void clearStore(Path storePath) throws IOException {
        if (Files.exists(storePath)) {
            Files.delete(storePath);
        }
    }

    // Prints store size and a small sample of contents.
    private static void listStore(Path storePath) {
        List<VectorDocument> storeDocuments = loadStore(storePath);
        System.out.println("Store: " + storePath + " (" + storeDocuments.size() + " documents)");
        int sampleCount = Math.min(3, storeDocuments.size());
        for (int index = 0; index < sampleCount; index++) {
            VectorDocument document = storeDocuments.get(index);
            System.out.println("- " + abbreviate(document.content(), 120));
        }
    }

    // Shortens long strings for logging.
    private static String abbreviate(String text, int maximumLength) {
        if (text == null) return "";
        if (text.length() <= maximumLength) return text;
        return text.substring(0, Math.max(0, maximumLength - 3)) + "...";
    }

    // Checks if a flag is present in the argument list.
    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) return true;
        }
        return false;
    }

    // Reads a single value after a named option.
    private static String getOptionValue(String[] args, String option) {
        for (int index = 0; index < args.length - 1; index++) {
            if (option.equals(args[index])) {
                return args[index + 1];
            }
        }
        return null;
    }

    // Reads all values after a named option until the next flag.
    private static List<String> getOptionValues(String[] args, String option) {
        List<String> values = new ArrayList<>();
        boolean collectingValues = false;
        for (String arg : args) {
            if (option.equals(arg)) {
                collectingValues = true;
                continue;
            }
            if (collectingValues) {
                if (arg.startsWith("--")) {
                    collectingValues = false;
                } else {
                    values.add(arg);
                }
            }
        }
        return values;
    }

    // Expands file and directory paths to a sorted list of files (directories are recursive).
    private static List<Path> expandPaths(List<String> rawPaths) {
        Set<Path> uniqueFiles = new HashSet<>();
        for (String rawPath : rawPaths) {
            Path inputPath = Paths.get(rawPath);
            if (!Files.exists(inputPath)) {
                System.err.println("Path does not exist: " + inputPath);
                continue;
            }
            if (Files.isDirectory(inputPath)) {
                try {
                    Files.walk(inputPath)
                        .filter(Files::isRegularFile)
                        .forEach(uniqueFiles::add);
                } catch (IOException ioException) {
                    System.err.println("Failed to walk directory: " + inputPath
                        + " - " + ioException.getMessage());
                }
            } else {
                uniqueFiles.add(inputPath);
            }
        }
        List<Path> sortedFiles = new ArrayList<>(uniqueFiles);
        sortedFiles.sort(Comparator.comparing(Path::toString));
        return sortedFiles;
    }

    // Prints CLI usage instructions (including the Gradle task wrapper).
    private static void printUsage() {
        System.out.println("SeedDataGenerator usage:");
        System.out.println("  --ingest <file|dir> [file|dir ...]  Ingest .txt/.json files (dirs are recursive)");
        System.out.println("  --clear                             Delete the store file");
        System.out.println("  --list                              List store contents");
        System.out.println("  --output <path>                     Override output path");
        System.out.println("  --model <name>                      Embedding model (default nomic-embed-text)");
        System.out.println("  --ollama <url>                      Ollama base URL (default http://localhost:11434)");
        System.out.println();
        System.out.println("Gradle wrapper:");
        System.out.println("  ./gradlew seedData --args=\"--ingest tools/seed-documents\"");
        System.out.println("  ./gradlew seedData -PseedArgs=\"--ingest tools/seed-documents/crafting_recipes.json\"");
    }

    private static class ParsedArguments {
        private final boolean shouldClear;
        private final boolean shouldList;
        private final String outputPath;
        private final String embeddingModel;
        private final String ollamaBaseUrl;
        private final List<String> ingestTargets;

        private ParsedArguments(boolean shouldClear, boolean shouldList, String outputPath,
                                String embeddingModel, String ollamaBaseUrl, List<String> ingestTargets) {
            this.shouldClear = shouldClear;
            this.shouldList = shouldList;
            this.outputPath = outputPath;
            this.embeddingModel = embeddingModel;
            this.ollamaBaseUrl = ollamaBaseUrl;
            this.ingestTargets = ingestTargets;
        }

        // Parses CLI arguments into a structured configuration object.
        private static ParsedArguments from(String[] arguments) {
            String outputPath = getOptionValue(arguments, "--output");
            if (outputPath == null) outputPath = DEFAULT_STORE_PATH;

            String embeddingModel = getOptionValue(arguments, "--model");
            if (embeddingModel == null) embeddingModel = DEFAULT_MODEL;

            String ollamaBaseUrl = getOptionValue(arguments, "--ollama");
            if (ollamaBaseUrl == null) ollamaBaseUrl = DEFAULT_OLLAMA_BASE_URL;

            List<String> ingestTargets = getOptionValues(arguments, "--ingest");

            boolean shouldClear = hasFlag(arguments, "--clear");
            boolean shouldList = hasFlag(arguments, "--list");

            return new ParsedArguments(
                shouldClear,
                shouldList,
                outputPath,
                embeddingModel,
                ollamaBaseUrl,
                ingestTargets
            );
        }
    }
}
