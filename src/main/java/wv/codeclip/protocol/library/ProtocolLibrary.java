package wv.codeclip.protocol.library;

import wv.codeclip.protocol.model.ProtocolFile;
import wv.codeclip.protocol.parser.ProtocolFileParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public final class ProtocolLibrary {

    private static final String EXTENSION = ".prtcl";
    private static final String MASTER_LOCK_FILE = ".masterlock";

    private final Path protocolsDir;
    private final ProtocolFileParser parser = new ProtocolFileParser();

    public ProtocolLibrary(Path protocolsDir) {
        // protocolsDir IS the folder containing .prtcl files — not a base that
        // gets "protocols" appended. The old resolve("protocols") is what
        // created the ".../protocols/protocols" nesting when the user picked
        // a folder that was already a protocols folder. No disk side effect
        // on construction either: folder is created lazily on first write.
        this.protocolsDir = protocolsDir;
    }

    public Path getProtocolsDir() { return protocolsDir; }

    public List<String> listFileNames() {
        if (!Files.isDirectory(protocolsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(protocolsDir)) {
            return stream
                .filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                .map(p -> p.getFileName().toString())
                .sorted()
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list protocols directory: " + protocolsDir, e);
        }
    }

    public boolean exists(String fileName) {
        return Files.exists(resolvePath(fileName));
    }

    public ProtocolFile load(String fileName) {
        Path path = resolvePath(fileName);
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return parser.parse(fileName, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read protocol file: " + path, e);
        }
    }

    public ProtocolFile loadSafely(String fileName, StringBuilder errorHolder) {
        try {
            return load(fileName);
        } catch (Exception e) {
            errorHolder.append(e.getMessage() != null ? e.getMessage() : e.toString());
            return new ProtocolFile(fileName, false, List.of(), new ArrayList<>());
        }
    }

    public ProtocolFile loadOrCreate(String fileName) {
        if (exists(fileName)) return load(fileName);
        return new ProtocolFile(fileName, false, List.of(), new ArrayList<>());
    }

    public void save(ProtocolFile file) {
        Path path = resolvePath(file.getFileName());
        try {
            Files.createDirectories(protocolsDir);
            Files.writeString(path, file.render(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write protocol file: " + path, e);
        }
    }

    public void delete(String fileName) {
        try {
            Files.deleteIfExists(resolvePath(fileName));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete protocol file: " + resolvePath(fileName), e);
        }
    }

    public void rename(String oldFileName, String newFileName) {
        Path oldPath = resolvePath(oldFileName);
        Path newPath = resolvePath(newFileName);
        try {
            Files.move(oldPath, newPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not rename protocol file", e);
        }
    }

    public boolean isMasterLocked() {
        return Files.exists(protocolsDir.resolve(MASTER_LOCK_FILE));
    }

    public void setMasterLocked(boolean locked) {
        Path lockPath = protocolsDir.resolve(MASTER_LOCK_FILE);
        try {
            if (locked) {
                Files.createDirectories(protocolsDir);
                Files.writeString(lockPath, "locked", StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(lockPath);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not update master lock", e);
        }
    }

    private Path resolvePath(String fileName) {
        String normalized = fileName.endsWith(EXTENSION) ? fileName : fileName + EXTENSION;
        return protocolsDir.resolve(normalized);
    }
}