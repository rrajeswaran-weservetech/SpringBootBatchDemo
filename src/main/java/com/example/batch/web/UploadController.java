package com.example.batch.web;

import com.example.batch.config.BatchProperties;
import com.example.batch.config.FileOutputProperties;
import com.example.batch.service.StorageService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final StorageService storageService;
    private final JobLauncher jobLauncher;
    private final Job importPersonJob;
    private final FileOutputProperties outputProperties;
    private final BatchProperties batchProperties;

    public UploadController(StorageService storageService,
                            JobLauncher jobLauncher,
                            Job importPersonJob,
                            FileOutputProperties outputProperties,
                            BatchProperties batchProperties) {
        this.storageService = storageService;
        this.jobLauncher = jobLauncher;
        this.importPersonJob = importPersonJob;
        this.outputProperties = outputProperties;
        this.batchProperties = batchProperties;
    }

    @PostMapping(value = "/upload", consumes = {"multipart/form-data"})
    public ResponseEntity<?> uploadAndStart(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "CSV file is required"));
        }
        try {
            Path saved = storageService.save(file.getOriginalFilename(), file.getBytes());

            // Prepare output CSV path for matches
            String outDirConfig = outputProperties.getDir();
            String outDir = resolveHome(outDirConfig);
            Files.createDirectories(Paths.get(outDir));
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String baseName = StringUtils.hasText(file.getOriginalFilename()) ? Path.of(file.getOriginalFilename()).getFileName().toString() : "input.csv";
            String outFile = Paths.get(outDir, ts + "_matches_" + baseName).toString();

            JobParameters params = new JobParametersBuilder()
                    .addString("file", saved.toString())
                    .addString("outFile", outFile)
                    .addLong("ts", Instant.now().toEpochMilli())
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(importPersonJob, params);

            return ResponseEntity.accepted().body(Map.of(
                    "message", "Upload successful. Job started.",
                    "jobId", execution.getJobId(),
                    "executionId", execution.getId(),
                    "status", String.valueOf(execution.getStatus()),
                    "file", saved.toString(),
                    "outFile", outFile,
                    "restUrl", batchProperties.getRest().getBaseUrl(),
                    "pageSize", batchProperties.getRest().getPageSize()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private static String resolveHome(String path) {
        if (path == null) return System.getProperty("user.home") + "/uploads";
        String userHome = System.getProperty("user.home");
        return path.replace("${USERPROFILE}", userHome).replace("~", userHome);
    }
}
