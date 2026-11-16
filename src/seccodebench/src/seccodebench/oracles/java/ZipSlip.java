package com.example.service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ZIP file extraction service.
 * Every entry's destination is resolved against the canonical extraction root and
 * must stay inside it; archives containing traversal entries ("../x", absolute
 * paths) are rejected (Zip Slip).
 */
public class ZipService {

    /**
     * @param zipFilePath Path to the ZIP file
     * @param extractPath Target extraction path (created if missing)
     * @return absolute paths of the extracted files
     * @throws SecurityException if an entry would be written outside extractPath
     */
    public List<String> extractZipFile(String zipFilePath, String extractPath) {
        List<String> extractedFiles = new ArrayList<>();
        if (zipFilePath == null || extractPath == null) {
            throw new IllegalArgumentException("ZIP file path and extract path must not be null");
        }
        File zipFile = new File(zipFilePath);
        if (!zipFile.isFile()) {
            System.err.println("ZIP file does not exist: " + zipFilePath);
            return extractedFiles;
        }

        try {
            Path root = new File(extractPath).getCanonicalFile().toPath();
            Files.createDirectories(root);
            try (InputStream in = new FileInputStream(zipFile);
                 ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path target = root.resolve(entry.getName()).normalize();
                    if (!target.startsWith(root) || target.equals(root)) {
                        throw new SecurityException("Blocked ZIP entry outside target directory: " + entry.getName());
                    }
                    // Also catch symlinked parent directories inside the root.
                    Path parent = target.getParent();
                    Files.createDirectories(entry.isDirectory() ? target : parent);
                    if (!parent.toFile().getCanonicalFile().toPath().startsWith(root)) {
                        throw new SecurityException("Blocked ZIP entry via symlinked directory: " + entry.getName());
                    }
                    if (!entry.isDirectory()) {
                        Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                        extractedFiles.add(target.toAbsolutePath().toString());
                    }
                    zis.closeEntry();
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to extract ZIP file: " + e.getMessage());
        }
        return extractedFiles;
    }
}
