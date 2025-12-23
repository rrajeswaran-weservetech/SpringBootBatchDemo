# Logging Configuration Guide

## Overview

This Spring Boot Batch application uses **Logback** for advanced logging with multiple log files for different purposes.

---

## Log File Locations

All log files are stored in: **`C:\temp\SpringBootBatchDemo_logs\`**

### Active Log Files

| Log File | Purpose | Log Level | Content |
|----------|---------|-----------|---------|
| **spring-batch-app.log** | Main application log | INFO | All application events, startup, shutdown, general operations |
| **batch-processing.log** | Batch-specific operations | INFO/DEBUG | Spring Batch job execution, step execution, chunk processing |
| **errors.log** | Error tracking | ERROR only | All errors and exceptions with full stack traces |
| **skipped-records.log** | Skipped records tracking | ERROR | Records skipped during batch processing (fault tolerance) |
| **sql-queries.log** | Database operations | DEBUG | SQL queries, JDBC operations, database connections |

### Archived Log Files

Location: **`C:\temp\SpringBootBatchDemo_logs\archived\`**

Old log files are automatically:
- **Compressed** to `.gz` format
- **Rotated** when they reach 10MB
- **Archived** with date stamp (e.g., `spring-batch-app-2025-12-23.0.log.gz`)
- **Deleted** after retention period

---

## Log Rotation & Retention Policy

| Log File | Max Size | Retention Period | Total Size Cap |
|----------|----------|------------------|----------------|
| spring-batch-app.log | 10 MB | 30 days | 1 GB |
| batch-processing.log | 10 MB | 30 days | 500 MB |
| errors.log | 10 MB | 90 days | 500 MB |
| skipped-records.log | 10 MB | 90 days | 500 MB |
| sql-queries.log | 10 MB | 7 days | 100 MB |

**How it works:**
1. When a log file reaches 10MB, it's archived with a timestamp
2. Archived files are compressed to `.gz` format
3. After retention period, oldest files are automatically deleted
4. If total size exceeds cap, oldest files are deleted first

---

## Log Format

### Console Output (Colored)
```
2025-12-23 10:15:23.456 INFO  [main] com.example.batch.SpringBatchMssqlCsvApplication - Starting application...
```

### File Output
```
2025-12-23 10:15:23.456 [main] INFO  com.example.batch.config.BatchConfig - Configuring batch job...
```

### Skipped Records Log
```
2025-12-23 10:15:23.456 - WRITE_PHASE_SKIP: firstName=John, lastName=Doe, email=duplicate@example.com, age=30 | Reason: Duplicate entry 'duplicate@example.com' for key 'IX_persons_email'
```

---

## What Gets Logged

### 1. Main Application Log (spring-batch-app.log)

**Contains:**
- Application startup and shutdown
- Configuration loading
- Bean initialization
- REST API requests
- General application flow
- HikariCP connection pool events

**Example:**
```
2025-12-23 10:15:20.123 [main] INFO  com.example.batch.SpringBatchMssqlCsvApplication - Starting SpringBatchMssqlCsvApplication
2025-12-23 10:15:21.456 [main] INFO  com.zaxxer.hikari.HikariDataSource - HikariBatchPool - Starting...
2025-12-23 10:15:22.789 [main] INFO  org.springframework.boot.web.embedded.tomcat.TomcatWebServer - Tomcat started on port(s): 8084 (http)
```

### 2. Batch Processing Log (batch-processing.log)

**Contains:**
- Job execution start/end
- Step execution details
- Chunk processing
- Read/Write counts
- Skip counts
- Job status (COMPLETED, FAILED, etc.)

**Example:**
```
2025-12-23 10:20:15.123 [batch-exec-1] INFO  o.s.batch.core.job.SimpleStepHandler - Executing step: [csvToDbStep]
2025-12-23 10:20:15.456 [batch-exec-1] INFO  o.s.batch.core.step.AbstractStep - Step: [csvToDbStep] executed in 5s234ms
2025-12-23 10:20:15.789 [batch-exec-1] INFO  o.s.batch.core.launch.support.SimpleJobLauncher - Job: [importPersonJob] completed with status: [COMPLETED]
```

### 3. Error Log (errors.log)

**Contains:**
- All ERROR level messages
- Full stack traces
- Exception details
- Failed operations

**Example:**
```
2025-12-23 10:25:30.123 [batch-exec-2] ERROR com.example.batch.listener.PersonSkipListener - Skipped record during WRITE phase: Person(firstName=Jane, lastName=Smith, email=jane@example.com, age=25)
java.sql.SQLIntegrityConstraintViolationException: Duplicate entry 'jane@example.com' for key 'IX_persons_email'
    at com.microsoft.sqlserver.jdbc.SQLServerException.makeFromDatabaseError(SQLServerException.java:262)
    at com.microsoft.sqlserver.jdbc.SQLServerStatement.getNextResult(SQLServerStatement.java:1624)
    ...
```

### 4. Skipped Records Log (skipped-records.log)

**Contains:**
- Only records that were skipped due to exceptions
- Clean format for easy parsing/reporting
- Skip phase (READ, PROCESS, WRITE)
- Record details
- Reason for skip

**Example:**
```
2025-12-23 10:25:30.123 - PROCESS_PHASE_SKIP: firstName=Charlie, lastName=Davis, email=charlie@example.com, age=abc | Reason: For input string: "abc"
2025-12-23 10:25:30.456 - WRITE_PHASE_SKIP: firstName=Frank, lastName=Anderson, email=john@example.com, age=40 | Reason: Duplicate entry 'john@example.com' for key 'IX_persons_email'
```

### 5. SQL Queries Log (sql-queries.log)

**Contains:**
- SQL statements executed
- JDBC operations
- Database connection events
- Query parameters (if enabled)

**Example:**
```
2025-12-23 10:20:16.123 - Executing prepared SQL statement [INSERT INTO dbo.persons (first_name, last_name, email, age) VALUES (?, ?, ?, ?)]
2025-12-23 10:20:16.456 - Batch update returned unexpected row count from update [0]; actual row count: 1
```

---

## Log Levels by Component

| Component | Development | Production |
|-----------|-------------|------------|
| com.example.batch | DEBUG | INFO |
| org.springframework.batch | DEBUG | WARN |
| org.springframework.jdbc | DEBUG | INFO |
| org.springframework.web | INFO | INFO |
| com.zaxxer.hikari | INFO | INFO |
| com.microsoft.sqlserver | WARN | WARN |

**Switch profiles:**
```bash
# Development mode (more verbose)
java -jar app.jar --spring.profiles.active=dev

# Production mode (less verbose)
java -jar app.jar --spring.profiles.active=prod
```

---

## How to View Logs

### Option 1: Real-time Console Monitoring
Watch logs in real-time while application runs:
```bash
# Run application
mvn spring-boot:run
```

### Option 2: Tail Active Log Files
Monitor specific log files in real-time:
```bash
# Windows PowerShell
Get-Content C:\temp\SpringBootBatchDemo_logs\spring-batch-app.log -Wait

# Or using tail (if Git Bash installed)
tail -f C:\temp\SpringBootBatchDemo_logs\spring-batch-app.log
```

### Option 3: Open in Text Editor
```
C:\temp\SpringBootBatchDemo_logs\spring-batch-app.log
```

### Option 4: Search Logs
```bash
# Windows PowerShell - Search for errors
Select-String -Path "C:\temp\SpringBootBatchDemo_logs\*.log" -Pattern "ERROR"

# Windows PowerShell - Search for specific email
Select-String -Path "C:\temp\SpringBootBatchDemo_logs\skipped-records.log" -Pattern "john@example.com"
```

---

## Monitoring Specific Events

### Check if Job Completed Successfully
**File:** `batch-processing.log`
**Search for:**
```
Job: [importPersonJob] completed with status: [COMPLETED]
```

### Find Skipped Records Count
**File:** `batch-processing.log`
**Search for:**
```
Step: [csvToDbStep] executed
```
Look for: `skipCount=X`

### View All Skipped Records
**File:** `skipped-records.log`
**Contains:** One line per skipped record with full details

### Check Database Errors
**File:** `errors.log`
**Search for:**
```
SQLIntegrityConstraintViolationException
DataIntegrityViolationException
```

### Monitor Performance
**File:** `batch-processing.log`
**Look for:**
```
Step: [csvToDbStep] executed in Xs
```

---

## Customizing Logging

### Change Log Directory

**Edit:** `src/main/resources/logback-spring.xml`
```xml
<!-- Line 5 -->
<property name="LOG_DIR" value="D:/my-custom-logs"/>
```

### Change Log Levels

**Option 1: Edit logback-spring.xml**
```xml
<logger name="com.example.batch" level="DEBUG"/>
```

**Option 2: Add to application.yml**
```yaml
logging:
  level:
    com.example.batch: DEBUG
    org.springframework.batch: INFO
```

### Change Rotation Policy

**Edit:** `src/main/resources/logback-spring.xml`
```xml
<maxFileSize>50MB</maxFileSize>      <!-- Change from 10MB -->
<maxHistory>60</maxHistory>          <!-- Change from 30 days -->
<totalSizeCap>2GB</totalSizeCap>     <!-- Change from 1GB -->
```

---

## Troubleshooting

### Problem: No log files created

**Check:**
1. Directory permissions on `C:\temp\`
2. Application has write access
3. Logback configuration loaded correctly

**Solution:**
```bash
# Create directory manually
mkdir C:\temp\SpringBootBatchDemo_logs
```

### Problem: Logs not showing in files

**Check:**
1. Console shows logs? (If yes, file permissions issue)
2. Check for errors at startup
3. Verify logback-spring.xml syntax

### Problem: Log files too large

**Solution:** Reduce max file size or retention period in logback-spring.xml

### Problem: Can't find archived logs

**Location:** `C:\temp\SpringBootBatchDemo_logs\archived\`
**Format:** `.gz` compressed files
**Extract:** Use 7-Zip, WinRAR, or built-in Windows extraction

---

## Production Recommendations

### 1. Centralized Log Management
Consider using:
- **ELK Stack** (Elasticsearch, Logback, Kibana)
- **Splunk**
- **Datadog**
- **CloudWatch** (AWS)

### 2. Alert on Errors
Set up monitoring to alert when:
- ERROR count exceeds threshold
- Job status is FAILED
- Skip count is high

### 3. Regular Log Review
Schedule weekly review of:
- `errors.log` - Check for recurring issues
- `skipped-records.log` - Identify data quality problems
- `batch-processing.log` - Monitor job performance

### 4. Disk Space Monitoring
Monitor `C:\temp\SpringBootBatchDemo_logs\` disk usage:
- Set alerts at 80% capacity
- Archive old logs to network storage
- Adjust retention policies if needed

---

## Related Files

- **logback-spring.xml** - Main logging configuration
- **BatchConfig.java:130** - SkipListener registration
- **PersonSkipListener.java** - Skipped records logging logic
- **FAULT_TOLERANCE_GUIDE.md** - Fault tolerance documentation

---

## Quick Reference

### Log File Quick Access
```
Main logs:    C:\temp\SpringBootBatchDemo_logs\
Archives:     C:\temp\SpringBootBatchDemo_logs\archived\
```

### Most Important Logs for Troubleshooting
1. **errors.log** - Start here for failures
2. **skipped-records.log** - Data quality issues
3. **batch-processing.log** - Job execution details

### Log Retention Summary
- **Errors:** 90 days (critical for debugging)
- **Batch processing:** 30 days (performance tracking)
- **SQL queries:** 7 days (development only)

---

## Support

For questions about logging configuration:
- See Logback documentation: https://logback.qos.ch/
- See Spring Boot logging: https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.logging
