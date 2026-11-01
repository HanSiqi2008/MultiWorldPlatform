package org.multiworldplatform.world;

import org.multiworldplatform.config.ConfigStore;
import org.multiworldplatform.config.WorldDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Reads and writes portable world archives. These methods perform blocking I/O
 * and must be called from an asynchronous executor.
 */
public final class WorldArchiveService {

    public static final String FORMAT = "MultiWorldPlatform-World";
    private static final int FORMAT_VERSION = 1;
    private static final String MARKER = ".multiworldplatform-world.properties";
    private static final String TEMPLATE = "multiworldplatform-template.json";
    private static final String DEFINITION = "multiworldplatform-world.conf";
    private static final long MAX_ENTRIES = 500_000L;

    private final ConfigStore config;

    public WorldArchiveService(final ConfigStore config) {
        this.config = config;
    }

    public Path findImportArchive(final Path defaultWorldDirectory, final String worldName) {
        final Path inWorldRoot = defaultWorldDirectory.toAbsolutePath().normalize()
                .resolve(worldName + ".zip");
        if (Files.isRegularFile(inWorldRoot)) {
            return inWorldRoot;
        }
        final Path inConfig = this.config.importDirectory().resolve(worldName + ".zip");
        if (Files.isRegularFile(inConfig)) {
            return inConfig;
        }
        return null;
    }

    public Path exportPath(final String worldName) {
        return this.config.exportDirectory().resolve(worldName + ".zip");
    }

    public ExtractedWorld extract(final Path archive, final String worldName) throws IOException {
        final Path temporaryRoot = Files.createTempDirectory(this.config.directory(), ".import-");
        try {
            extractSafely(archive, temporaryRoot);
            final Path worldDirectory = temporaryRoot.resolve(worldName).normalize();
            if (!worldDirectory.startsWith(temporaryRoot) || !Files.isDirectory(worldDirectory)) {
                throw new IOException("Archive must contain a root folder named '" + worldName + "'");
            }

            final Path markerFile = worldDirectory.resolve(MARKER);
            final Path templateFile = worldDirectory.resolve(TEMPLATE);
            final Path definitionFile = worldDirectory.resolve(DEFINITION);
            if (!Files.isRegularFile(markerFile) || !Files.isRegularFile(templateFile)
                    || !Files.isRegularFile(definitionFile)) {
                throw new IOException("Archive is not a complete MultiWorldPlatform export");
            }

            final Properties marker = new Properties();
            try (InputStream input = Files.newInputStream(markerFile)) {
                marker.load(input);
            }
            if (!FORMAT.equals(marker.getProperty("format"))
                    || Integer.parseInt(marker.getProperty("format-version", "0"))
                            > FORMAT_VERSION) {
                throw new IOException("Unsupported archive marker");
            }
            if (!worldName.equals(marker.getProperty("world-name"))) {
                throw new IOException("Archive marker names a different world");
            }

            final WorldDefinition definition = this.config.load(definitionFile, worldName);
            if (!worldName.equals(definition.name())) {
                throw new IOException("World configuration names a different world");
            }
            final String template = Files.readString(templateFile, StandardCharsets.UTF_8);
            if (template.isBlank()) {
                throw new IOException("Archive template is empty");
            }

            Files.deleteIfExists(markerFile);
            Files.deleteIfExists(templateFile);
            Files.deleteIfExists(definitionFile);
            return new ExtractedWorld(temporaryRoot, worldDirectory, definition, template);
        } catch (final IOException | RuntimeException error) {
            deleteDirectory(temporaryRoot);
            throw error;
        }
    }

    public Path writeExport(final Path sourceDirectory, final String worldName,
            final WorldDefinition definition, final String template, final Path target)
            throws IOException {
        final Path normalizedSource = sourceDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedSource)) {
            throw new IOException("World directory does not exist: " + normalizedSource);
        }

        Files.createDirectories(target.getParent());
        final Path temporaryArchive = Files.createTempFile(target.getParent(),
                worldName + "-", ".zip.tmp");
        final Path temporaryDefinition = Files.createTempFile(this.config.directory(),
                worldName + "-", ".conf");
        this.config.save(definition, temporaryDefinition);

        try {
            try (OutputStream output = Files.newOutputStream(temporaryArchive,
                    StandardOpenOption.TRUNCATE_EXISTING);
                    ZipOutputStream zip = new ZipOutputStream(output)) {
                final String rootPrefix = worldName + "/";
                try (Stream<Path> paths = Files.walk(normalizedSource)) {
                    for (final Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                        if (path.equals(normalizedSource) || isTransient(path)) {
                            continue;
                        }
                        final String relative = normalizedSource.relativize(path).toString()
                                .replace('\\', '/');
                        final String archiveName = rootPrefix + relative;
                        if (Files.isDirectory(path)) {
                            zip.putNextEntry(new ZipEntry(archiveName + "/"));
                            zip.closeEntry();
                        } else {
                            zip.putNextEntry(new ZipEntry(archiveName));
                            Files.copy(path, zip);
                            zip.closeEntry();
                        }
                    }
                }

                final Properties marker = new Properties();
                marker.setProperty("format", FORMAT);
                marker.setProperty("format-version", Integer.toString(FORMAT_VERSION));
                marker.setProperty("world-name", worldName);
                marker.setProperty("key", definition.key().formatted());
                final byte[] markerBytes;
                try (var buffer = new java.io.ByteArrayOutputStream()) {
                    marker.store(buffer, "MultiWorldPlatform world export marker");
                    markerBytes = buffer.toByteArray();
                }
                putBytes(zip, rootPrefix + MARKER, markerBytes);
                putBytes(zip, rootPrefix + DEFINITION, Files.readAllBytes(temporaryDefinition));
                putBytes(zip, rootPrefix + TEMPLATE,
                        template.getBytes(StandardCharsets.UTF_8));
            }

            try {
                Files.move(temporaryArchive, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException atomicMoveFailure) {
                Files.move(temporaryArchive, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporaryArchive);
            Files.deleteIfExists(temporaryDefinition);
        }
    }

    private static void extractSafely(final Path archive, final Path targetRoot)
            throws IOException {
        final long[] count = {0};
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (++count[0] > MAX_ENTRIES) {
                    throw new IOException("Archive contains too many entries");
                }
                final ZipEntry entry = entries.nextElement();
                final String normalizedName = entry.getName().replace('\\', '/');
                if (normalizedName.startsWith("/") || normalizedName.contains("../")
                        || normalizedName.equals("..")) {
                    throw new IOException("Unsafe archive entry: " + entry.getName());
                }
                final Path destination = targetRoot.resolve(normalizedName).normalize();
                if (!destination.startsWith(targetRoot)) {
                    throw new IOException("Archive entry escapes its extraction directory");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void putBytes(final ZipOutputStream zip, final String name,
            final byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static boolean isTransient(final Path path) {
        final String name = path.getFileName().toString();
        return name.equals("session.lock") || name.equals(MARKER)
                || name.equals(TEMPLATE) || name.equals(DEFINITION);
    }

    public static void deleteDirectory(final Path directory) throws IOException {
        if (directory == null || Files.notExists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record ExtractedWorld(Path temporaryRoot, Path worldDirectory,
            WorldDefinition definition, String template) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            deleteDirectory(this.temporaryRoot);
        }

        public void closeQuietly() {
            try {
                this.close();
            } catch (final IOException ignored) {
                // Best-effort temporary extraction cleanup.
            }
        }
    }
}
