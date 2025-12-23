package com.example.batch.service;

import com.example.batch.config.FileUploadProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class StorageService {
    private final Path uploadDir;

    public StorageService(FileUploadProperties properties) {
        this.uploadDir = Paths.get(resolveHome(properties.getDir())).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create upload directory: " + this.uploadDir, e);
        }
    }

    public Path save(String originalFilename, byte[] bytes) throws IOException {
        String cleanName = StringUtils.hasText(originalFilename) ? Path.of(originalFilename).getFileName().toString() : "file.csv";
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String savedName = timestamp + "_" + cleanName;
        Path target = uploadDir.resolve(savedName);
        Files.write(target, bytes);
        return target;
    }

    private static String resolveHome(String path) {
        if (path == null) return System.getProperty("user.home") + "/uploads";
        String userHome = System.getProperty("user.home");
        return path.replace("${USERPROFILE}", userHome).replace("~", userHome);
    }
}
