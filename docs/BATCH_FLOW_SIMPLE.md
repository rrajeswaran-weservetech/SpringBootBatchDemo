# Spring Batch Job - Simple Flow Diagram

## High-Level Overview

```
╔═══════════════════════════════════════════════════════════════════════════╗
║                      SPRING BATCH JOB: importPersonJob                     ║
╚═══════════════════════════════════════════════════════════════════════════╝

┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  ┌────────────┐         ┌──────────────┐         ┌───────────────┐        │
│  │ Input CSV  │────────▶│   STEP 1     │────────▶│   SQL Server  │        │
│  │ 10,000 rec │         │ csvToDbStep  │         │   Database    │        │
│  └────────────┘         │              │         │  (Versioned)  │        │
│                         │  Read        │         └───────┬───────┘        │
│                         │  Process     │                 │                │
│                         │  Write       │                 │                │
│                         └──────────────┘                 │                │
│                                                          │                │
│                                                          │                │
│                         ┌──────────────┐                 │                │
│  ┌────────────┐         │   STEP 2     │                 │                │
│  │ REST API   │────────▶│ restCompare  │◄────────────────┘                │
│  │ 11,000 rec │         │    Step      │                                  │
│  └────────────┘         │              │         ┌───────────────┐        │
│                         │  Read        │────────▶│  Output CSV   │        │
│                         │  Compare     │         │  ~5,974 rec   │        │
│                         │  Filter      │         │  (Matches)    │        │
│                         └──────────────┘         └───────────────┘        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Step-by-Step Execution

```
START
  │
  ├─▶ [STEP 1] Read CSV file (10,000 records)
  │     │
  │     ├─▶ Validate email (must contain @)
  │     │
  │     ├─▶ Sanitize data (trim, lowercase email)
  │     │
  │     └─▶ Write to Database (VersioningPersonItemWriter)
  │           │
  │           ├─▶ New email? → Insert version 1
  │           │
  │           ├─▶ Same data? → Keep current (no change)
  │           │
  │           └─▶ Changed data? → Create new version
  │
  ├─▶ [STEP 2] Read from REST API (11,000 records)
  │     │
  │     ├─▶ For each record, check database by email
  │     │
  │     ├─▶ Email found + All fields match? → INCLUDE in output
  │     │
  │     └─▶ Email not found OR fields differ? → FILTER OUT
  │
  └─▶ END
        │
        └─▶ Output: CSV file with ~5,974 matching records
```

## Quick Numbers

| Item | Count | Description |
|------|-------|-------------|
| **Input CSV** | 10,000 | Uploaded CSV file |
| **REST API** | 11,000 | Fixed data from REST endpoint |
| **Output CSV** | ~5,974 | Exact matches between REST & DB |
| **Filtered Out** | ~5,026 | Non-matching or missing records |

## Why Not All Match?

```
11,000 REST records
   │
   ├─ 1,000 records not in database (REST has extras)
   │
   ├─ ~4,026 records have field differences
   │   (email case, trimming, age, etc.)
   │
   └─ ~5,974 records match exactly ✓
         │
         └─▶ Written to output CSV
```

## Key Components

### Step 1: csvToDbStep
- **Reader**: FlatFileItemReader (CSV)
- **Processor**: personProcessor (validation)
- **Writer**: VersioningPersonItemWriter (versioning logic)

### Step 2: restCompareStep
- **Reader**: RestPagedPersonItemReader (REST API)
- **Processor**: personMatchProcessor (comparison filter)
- **Writer**: FlatFileItemWriter (CSV output)

## Idempotent Behavior

```
Upload Same CSV Again
         │
         ▼
   No changes detected
   (VersioningPersonItemWriter)
         │
         ▼
   Database unchanged
         │
         ▼
   Same comparison results
         │
         ▼
   Same output: ~5,974 records
```

---
*This batch job is designed for validation, not full data export*
