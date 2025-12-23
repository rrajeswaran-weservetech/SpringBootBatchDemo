# Spring Batch Fault Tolerance - Complete Guide

## Table of Contents
- [Overview](#overview)
- [Why Fault Tolerance is Used](#why-fault-tolerance-is-used)
- [Configuration](#configuration)
- [How It Works](#how-it-works)
- [Real World Example](#real-world-example)
- [Comparison: With vs Without](#comparison-with-vs-without)
- [Two Layers of Protection](#two-layers-of-protection)
- [Types of Exceptions Caught](#types-of-exceptions-caught)
- [Skip Limit Behavior](#skip-limit-behavior)
- [Best Practices](#best-practices)
- [Monitoring Skipped Records](#monitoring-skipped-records)
- [Summary](#summary)

---

## Overview

Fault tolerance in Spring Batch allows your batch jobs to continue processing even when individual records fail, preventing a single bad record from failing an entire batch job.

---

## Why Fault Tolerance is Used

### Problem Without Fault Tolerance

If you're importing a CSV with **10,000 records** and record **#5,432** has a data issue (bad format, constraint violation, etc.), the **entire job fails** and **none** of the 10,000 records get imported. You'd have to fix that one record and restart from the beginning.

### Solution With Fault Tolerance

The job skips the problematic record, logs it, and continues processing the remaining **9,999 records** successfully.

---

## Configuration

### Current Configuration (BatchConfig.java:125-127)

```java
.faultTolerant()
.skipLimit(100)
.skip(Exception.class)
```

### What This Means

- **Skip up to 100 exceptions** total during the entire job execution
- **Skip any type of Exception** (very broad catch-all)
- If the **101st exception** occurs → job fails completely
- Successfully processed records before failure are **still committed** to database

---

## How It Works

### Processing Flow with Chunks

**Chunk size = 1000** (configured in application.yml:38)

Spring Batch processes records in chunks and commits per chunk:

```
Chunk 1: Records 1-1000    → Read → Process → Write → Commit
Chunk 2: Records 1001-2000 → Read → Process → Write → Commit
Chunk 3: Records 2001-3000 → Read → Process → Write → Commit
...
```

### When Exception Occurs

**Scenario:** Record #2,345 throws an exception

```
1. Read record #2,345
2. Process record #2,345 → EXCEPTION!
3. Spring Batch catches it (because .skip(Exception.class))
4. Increments skip counter (1/100)
5. Logs the skipped record
6. Continues with record #2,346
7. Chunk still commits successfully (minus the failed record)
```

---

## Real World Example

### Sample CSV File (persons.csv)

```csv
firstName,lastName,email,age
John,Doe,john@example.com,30
Jane,Smith,jane@example.com,25
Bob,Wilson,invalid-email,35
Alice,Brown,alice@example.com,28
Charlie,Davis,charlie@example.com,abc
David,Miller,david@example.com,45
Eve,Taylor,eve@example.com,32
Frank,Anderson,john@example.com,40
Grace,Thomas,grace@example.com,29
```

### Record-by-Record Processing

| Record | Issue | Processor Result | Database Write | Fault Tolerance Action |
|--------|-------|------------------|----------------|------------------------|
| 1. John Doe | None | ✓ Valid | ✓ Inserted | - |
| 2. Jane Smith | None | ✓ Valid | ✓ Inserted | - |
| 3. Bob Wilson | No @ in email | ✗ Returns null | ⊘ Skipped | **Filtered by processor** |
| 4. Alice Brown | None | ✓ Valid | ✓ Inserted | - |
| 5. Charlie Davis | Age="abc" (not a number) | ✗ NumberFormatException | ⊘ Skipped | **Skip count: 1/100** |
| 6. David Miller | None | ✓ Valid | ✓ Inserted | - |
| 7. Eve Taylor | None | ✓ Valid | ✓ Inserted | - |
| 8. Frank Anderson | Duplicate email (john@) | ✗ SQL Constraint Violation | ⊘ Skipped | **Skip count: 2/100** |
| 9. Grace Thomas | None | ✓ Valid | ✓ Inserted | - |

### Final Result

- **✓ Successfully Inserted:** 6 records (John, Jane, Alice, David, Eve, Grace)
- **⊘ Skipped by Processor:** 1 record (Bob - invalid email format)
- **⊘ Skipped by Fault Tolerance:** 2 records (Charlie, Frank)
- **Skip Counter:** 2/100
- **Job Status:** ✓ COMPLETED

---

## Comparison: With vs Without

### WITHOUT Fault Tolerance

Remove these lines from configuration:
```java
// .faultTolerant()       ← Remove
// .skipLimit(100)        ← Remove
// .skip(Exception.class) ← Remove
```

#### Processing Result:

| Record | Processing | Result |
|--------|------------|--------|
| 1. John Doe | ✓ Inserted | Success |
| 2. Jane Smith | ✓ Inserted | Success |
| 3. Bob Wilson | Filtered by processor | Success |
| 4. Alice Brown | ✓ Inserted | Success |
| 5. Charlie Davis | NumberFormatException | **💥 JOB FAILS** |
| 6-9 (4 records) | Never processed | ⊘ Not inserted |

**Final Result:**
- Only **3 records** inserted (John, Jane, Alice)
- Job status: **❌ FAILED**
- Records 6-9 **never processed**
- Must fix the CSV and restart from beginning

### WITH Fault Tolerance (Current Configuration)

**Final Result:**
- **6 records** inserted successfully
- Job status: **✓ COMPLETED**
- Only problematic records skipped
- No manual intervention needed immediately

---

## Two Layers of Protection

### Layer 1: Processor Filtering (BatchConfig.java:81-91)

```java
@Bean
public ItemProcessor<Person, Person> personProcessor() {
    return item -> {
        // Filter invalid emails gracefully
        if (item.getEmail() == null || !item.getEmail().contains("@")) {
            return null; // ← Filtered, not counted as skip
        }

        // Sanitize data
        if (item.getFirstName() != null)
            item.setFirstName(item.getFirstName().trim());
        if (item.getLastName() != null)
            item.setLastName(item.getLastName().trim());
        if (item.getEmail() != null)
            item.setEmail(item.getEmail().trim().toLowerCase());

        return item;
    };
}
```

**Purpose:** Graceful filtering - returns `null` means "don't process this record further"
**Does NOT count** against skip limit

### Layer 2: Fault Tolerance (BatchConfig.java:125-127)

```java
.faultTolerant()
.skipLimit(100)
.skip(Exception.class)
```

**Purpose:** Handles unexpected exceptions that processor can't prevent
**COUNTS** against skip limit (100 max)

---

## Types of Exceptions Caught

### 1. Data Type Conversion Errors

**Example CSV:**
```csv
firstName,lastName,email,age
John,Doe,john@example.com,thirty  ← String instead of Integer
```

- **Exception:** `NumberFormatException`
- **Action:** Skipped, counter: 1/100
- **Reason:** Age field expects Integer, got String

### 2. Database Constraint Violations

**Example CSV:**
```csv
firstName,lastName,email,age
John,Doe,john@example.com,30
Jane,Smith,john@example.com,25  ← Duplicate email (unique index)
```

- **Exception:** `SQLIntegrityConstraintViolationException` / `DataIntegrityViolationException`
- **Action:** Skipped, counter: 1/100
- **Reason:** Email has unique index (schema-person.sql:13)

### 3. Malformed CSV Lines

**Example CSV:**
```csv
firstName,lastName,email,age
John,Doe,john@example.com,30
Jane,"Smith,jane@example.com  ← Missing closing quote
```

- **Exception:** `FlatFileParseException`
- **Action:** Skipped, counter: 1/100
- **Reason:** CSV parsing error - malformed line

### 4. Null Pointer or Unexpected Data

**Example CSV:**
```csv
firstName,lastName,email,age
,,,  ← All nulls
```

- **Exception:** `NullPointerException`
- **Action:** Skipped, counter: 1/100
- **Reason:** Unexpected null values causing NPE in processing

---

## Skip Limit Behavior

### Scenario: CSV with 150 Bad Records

```
Processing record 1...
Processing record 2...
...
Record 100 failed → Skip count: 1/100
Record 245 failed → Skip count: 2/100
Record 389 failed → Skip count: 3/100
...
Record 8234 failed → Skip count: 99/100
Record 8567 failed → Skip count: 100/100  ← Limit reached
Record 9012 failed → Skip count: 101/100  ← 💥 JOB FAILS!
```

### Behavior When Limit Exceeded

- First **100 errors** → skipped and logged
- **101st error** → job fails immediately
- All records processed before failure are **still committed** to database
- Job status changes to **FAILED**
- Records after the 101st failure are **not processed**

---

## Best Practices

### Current Configuration (Too Permissive)

```java
.faultTolerant()
.skipLimit(100)                  ← High limit
.skip(Exception.class)           ← Catches EVERYTHING (too broad)
```

**Issues:**
- Skipping 100 exceptions might hide serious data quality issues
- Catching all `Exception` types is too broad
- Could mask actual bugs (NPE, programming errors)

### Recommended Improvement

```java
.faultTolerant()
.skipLimit(10)  ← Lower limit for better data quality control

// Be specific about what to skip
.skip(FlatFileParseException.class)          ← Malformed CSV lines
.skip(DataIntegrityViolationException.class) ← Duplicate keys, constraint violations
.skip(NumberFormatException.class)           ← Data type conversion errors

// Don't skip critical errors
.noSkip(NullPointerException.class)          ← Indicates code bug, should fail
.noSkip(IllegalArgumentException.class)      ← Indicates code issue
```

**Benefits:**
- Lower skip limit forces better data quality
- Specific exception types make debugging easier
- Critical errors (NPE) fail the job - indicates code problems
- Better audit trail

### Additional Recommendations

#### 1. Add Retry Policy (for transient errors)

```java
.faultTolerant()
.skipLimit(10)
.skip(DataIntegrityViolationException.class)
.retryLimit(3)                               ← Retry 3 times
.retry(DeadlockLoserDataAccessException.class) ← For DB deadlocks
```

#### 2. Write Skipped Records to Error File

```java
@Bean
public ItemWriter<Person> errorWriter() {
    return new FlatFileItemWriterBuilder<Person>()
        .name("errorWriter")
        .resource(new FileSystemResource("errors.csv"))
        .delimited()
        .names("firstName", "lastName", "email", "age")
        .build();
}
```

#### 3. Add Custom Skip Policy

```java
public class CustomSkipPolicy implements SkipPolicy {
    @Override
    public boolean shouldSkip(Throwable t, long skipCount) {
        if (t instanceof DataIntegrityViolationException) {
            return skipCount < 10; // Skip up to 10 duplicates
        }
        if (t instanceof FlatFileParseException) {
            return skipCount < 5;  // Only skip 5 parse errors
        }
        return false; // Don't skip other exceptions
    }
}
```

---

## Monitoring Skipped Records

### Add Skip Listener to Track Errors

```java
package com.example.batch.listener;

import com.example.batch.model.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;

public class PersonSkipListener implements SkipListener<Person, Person> {

    private static final Logger log = LoggerFactory.getLogger(PersonSkipListener.class);

    @Override
    public void onSkipInRead(Throwable t) {
        log.error("Skipped record during READ phase: {}", t.getMessage(), t);
    }

    @Override
    public void onSkipInProcess(Person item, Throwable t) {
        log.error("Skipped record during PROCESS phase: {} - Error: {}",
                  item, t.getMessage(), t);
    }

    @Override
    public void onSkipInWrite(Person item, Throwable t) {
        log.error("Skipped record during WRITE phase: {} - Error: {}",
                  item, t.getMessage(), t);
    }
}
```

### Register Listener in Step Configuration

```java
@Bean
public Step csvToDbStep(JobRepository jobRepository,
                        PlatformTransactionManager transactionManager,
                        ...) {
    return new StepBuilder("csvToDbStep", jobRepository)
            .<Person, Person>chunk(properties.getChunkSize(), transactionManager)
            .reader(synchronizedReader)
            .processor(personProcessor)
            .writer(personWriter)
            .faultTolerant()
            .skipLimit(10)
            .skip(Exception.class)
            .listener(new PersonSkipListener())  ← Add listener
            .build();
}
```

### Sample Log Output

```
2025-12-23 10:15:23.456 ERROR Skipped record during PROCESS:
  Person(firstName=Charlie, lastName=Davis, email=charlie@example.com, age=abc)
  - Error: java.lang.NumberFormatException: For input string: "abc"

2025-12-23 10:15:23.789 ERROR Skipped record during WRITE:
  Person(firstName=Frank, lastName=Anderson, email=john@example.com, age=40)
  - Error: Duplicate entry 'john@example.com' for key 'IX_persons_email'
```

---

## Summary

### Fault Tolerance = Production Safety

| Aspect | Without Fault Tolerance | With Fault Tolerance |
|--------|-------------------------|----------------------|
| **One bad record** | Entire job fails | Only that record skipped |
| **Data imported** | 0 out of 10,000 | 9,999 out of 10,000 |
| **Manual intervention** | Must fix CSV and restart | Can review skipped records later |
| **Production readiness** | ❌ Fragile | ✓ Resilient |
| **Data quality visibility** | Immediate failure (good) | Requires monitoring (check logs) |

### Key Takeaways

1. **Fault tolerance prevents single bad records from failing entire jobs**
2. **Current configuration**: Allows 100 skips, catches all exceptions
3. **Very forgiving** for data quality issues but keeps job running
4. **Best for production** where business prefers partial success over complete failure
5. **Requires monitoring** - check logs for skipped records regularly
6. **Consider lowering skip limit** to maintain data quality standards

### When to Use Fault Tolerance

✅ **Use when:**
- Processing large datasets where some failures are acceptable
- Data quality issues are common
- Business prefers partial success over complete failure
- You have monitoring in place to track skipped records

❌ **Don't use when:**
- Data integrity is critical (financial, medical, legal)
- Every record must be processed
- Skipping records could cause downstream issues
- You want immediate failure notification

---

## Related Files

- **BatchConfig.java** (src/main/java/com/example/batch/config/BatchConfig.java:125-127) - Fault tolerance configuration
- **BatchProperties.java** (src/main/java/com/example/batch/config/BatchProperties.java) - Chunk size and concurrency settings
- **application.yml** (src/main/resources/application.yml:38-41) - Batch properties configuration
- **Person.java** (src/main/java/com/example/batch/model/Person.java) - Data model
- **schema-person.sql** (src/main/resources/schema-person.sql:13) - Unique index on email

---

## Additional Resources

- [Spring Batch Documentation - Fault Tolerance](https://docs.spring.io/spring-batch/docs/current/reference/html/step.html#faultTolerant)
- [Spring Batch Skip Logic](https://docs.spring.io/spring-batch/docs/current/reference/html/step.html#skip)
- [Spring Batch Listeners](https://docs.spring.io/spring-batch/docs/current/reference/html/step.html#stepExecutionListeners)
