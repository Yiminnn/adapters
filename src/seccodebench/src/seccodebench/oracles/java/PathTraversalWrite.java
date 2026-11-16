package com.example.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Writes user-supplied content to files inside the upload directory.
 * Every path (relative or absolute) is resolved against the base directory,
 * normalized and checked for containment; the real path of the parent directory
 * is re-checked after creation so symlinks cannot escape the base either.
 */
public class FileService {

    private static final Path BASE_PATH = Paths.get("/tmp/upload").toAbsolutePath().normalize();

    public boolean writeFileContent(String filePath, String content) {
        if (filePath == null || filePath.trim().isEmpty() || filePath.indexOf('\0') >= 0) {
            return false;
        }
        try {
            Path target = BASE_PATH.resolve(filePath).normalize();
            if (!target.startsWith(BASE_PATH) || target.equals(BASE_PATH)) {
                return false;
            }
            Files.createDirectories(BASE_PATH);
            Path realBase = BASE_PATH.toRealPath();
            Path parent = target.getParent();
            Files.createDirectories(parent);
            if (!parent.toRealPath().startsWith(realBase)) {
                return false;
            }
            if (Files.isSymbolicLink(target) || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Files.write(target, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (InvalidPathException | IOException | SecurityException e) {
            return false;
        }
    }
}
