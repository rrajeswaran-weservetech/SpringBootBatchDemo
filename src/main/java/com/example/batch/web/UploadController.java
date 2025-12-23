package com.example.batch.web;

import com.example.batch.service.StorageService;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final StorageService storageService;
    private final JobLauncher jobLauncher;
    private final Job importPersonJob;

    public UploadController(StorageService storageService, JobLauncher jobLauncher, Job importPersonJob) {
        this.storageService = storageService;
        this.jobLauncher = jobLauncher;
        this.importPersonJob = importPersonJob;
    }

    @PostMapping(value = "/upload", consumes = {"multipart/form-data"})
    public ResponseEntity<?> uploadAndStart(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "CSV file is required"));
        }
        try {
            Path saved = storageService.save(file.getOriginalFilename(), file.getBytes());

            JobParameters params = new JobParametersBuilder()
                    .addString("file", saved.toString())
                    .addLong("ts", Instant.now().toEpochMilli())
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(importPersonJob, params);

            return ResponseEntity.accepted().body(Map.of(
                    "message", "Upload successful. Job started.",
                    "jobId", execution.getJobId(),
                    "executionId", execution.getId(),
                    "status", String.valueOf(execution.getStatus()),
                    "file", saved.toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
