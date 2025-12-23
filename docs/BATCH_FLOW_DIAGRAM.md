# Spring Batch Job Flow Diagram

## Complete Batch Process Flow

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           BATCH JOB: importPersonJob                         │
│                          (BatchConfig.java:212-220)                          │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              STEP 1: csvToDbStep                             │
│                          (BatchConfig.java:127-149)                          │
│                         Chunk Size: 1000, Threads: 4                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
        ▼                             ▼                             ▼
┌──────────────┐            ┌──────────────────┐          ┌──────────────────┐
│   READER     │            │    PROCESSOR     │          │     WRITER       │
│              │            │                  │          │                  │
│ FlatFile     │───────────▶│ personProcessor  │─────────▶│ Versioning       │
│ ItemReader   │            │                  │          │ PersonItem       │
│              │            │ (validates &     │          │ Writer           │
│              │            │  sanitizes)      │          │                  │
└──────────────┘            └──────────────────┘          └──────────────────┘
        │                             │                             │
        │                             │                             │
        ▼                             ▼                             ▼
┌──────────────┐            ┌──────────────────┐          ┌──────────────────┐
│ Input CSV    │            │ Validation:      │          │ SQL Server DB    │
│              │            │ • email has @    │          │                  │
│ ~/uploads/   │            │ • trim names     │          │ dbo.persons      │
│ batch-csv/   │            │ • lowercase      │          │ table            │
│ *_data.csv   │            │   email          │          │                  │
│              │            │                  │          │                  │
│ 10,000       │            │ Filter invalid   │          │ Versioning       │
│ records      │            │ → return null    │          │ Logic Applied    │
└──────────────┘            └──────────────────┘          └──────────────────┘
                                                                    │
                                                                    ▼
                                        ┌────────────────────────────────────┐
                                        │  VERSIONING LOGIC                  │
                                        │  (upsertVersioned method)          │
                                        │  VersioningPersonItemWriter:31-55  │
                                        └────────────────────────────────────┘
                                                        │
                                                        ▼
                            ┌───────────────────────────────────────────────┐
                            │  Find current record by email                 │
                            │  personRepository.findCurrentByEmail()        │
                            └───────────────────────────────────────────────┘
                                                        │
                        ┌───────────────────────────────┴─────────────────────────────┐
                        │                                                             │
                        ▼ Record NOT found                                            ▼ Record EXISTS
            ┌─────────────────────────┐                               ┌──────────────────────────┐
            │  INSERT new record      │                               │  Compare all fields:     │
            │  version = 1            │                               │  • firstName             │
            │  is_current = 1         │                               │  • lastName              │
            └─────────────────────────┘                               │  • email                 │
                                                                      │  • age                   │
                                                                      └──────────────────────────┘
                                                                                  │
                                                    ┌─────────────────────────────┴──────────────────────────┐
                                                    │                                                        │
                                                    ▼ All fields SAME                                        ▼ Fields DIFFERENT
                                        ┌─────────────────────────┐                          ┌──────────────────────────────┐
                                        │  No action              │                          │  1. Mark old as not current  │
                                        │  Keep current version   │                          │     (is_current = 0)         │
                                        │  (Idempotent)           │                          │                              │
                                        └─────────────────────────┘                          │  2. Insert new version       │
                                                                                             │     version = old + 1        │
                                                                                             │     is_current = 1           │
                                                                                             └──────────────────────────────┘
                                                                    │
                                    ┌───────────────────────────────┴────────────────────────────┐
                                    │          STEP 1 COMPLETE                                   │
                                    │   Database contains versioned person records               │
                                    │   Only current versions will be used for comparison        │
                                    └────────────────────────────────────────────────────────────┘
                                                                    │
                                                                    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          STEP 2: restCompareStep                             │
│                          (BatchConfig.java:195-209)                          │
│                         Chunk Size: 1000, Threads: 4                         │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
        ▼                             ▼                             ▼
┌──────────────┐            ┌──────────────────┐          ┌──────────────────┐
│   READER     │            │    PROCESSOR     │          │     WRITER       │
│              │            │                  │          │                  │
│ RestPaged    │───────────▶│ personMatch      │─────────▶│ FlatFileItem     │
│ PersonItem   │            │ Processor        │          │ Writer           │
│ Reader       │            │                  │          │                  │
│              │            │ (filters to      │          │ (CSV output)     │
│              │            │  exact matches)  │          │                  │
└──────────────┘            └──────────────────┘          └──────────────────┘
        │                             │                             │
        │                             │                             │
        ▼                             ▼                             ▼
┌──────────────┐            ┌──────────────────┐          ┌──────────────────┐
│ REST API     │            │ Match Logic:     │          │ Output CSV       │
│              │            │                  │          │                  │
│ http://      │            │ For each record: │          │ ~/uploads/       │
│ localhost:   │            │                  │          │ batch-output/    │
│ 8085/api/    │            │ 1. Find by email │          │ *_matches_       │
│ persons      │            │    in DB         │          │ data.csv         │
│              │            │                  │          │                  │
│ 11,000       │            │ 2. If NOT found  │          │ Only exact       │
│ records      │            │    → return null │          │ matches written  │
│              │            │                  │          │                  │
│ Page size:   │            │ 3. If found,     │          │ ~5,974           │
│ 500          │            │    compare:      │          │ records          │
│              │            │    • firstName   │          │                  │
│              │            │    • lastName    │          │                  │
│              │            │    • email       │          │                  │
│              │            │    • age         │          │                  │
│              │            │                  │          │                  │
│              │            │ 4. All match?    │          │                  │
│              │            │    → return item │          │                  │
│              │            │    (PASS)        │          │                  │
│              │            │                  │          │                  │
│              │            │ 5. Any differ?   │          │                  │
│              │            │    → return null │          │                  │
│              │            │    (FILTER OUT)  │          │                  │
└──────────────┘            └──────────────────┘          └──────────────────┘
                                      │
                                      ▼
                    ┌────────────────────────────────────┐
                    │  personMatchProcessor Logic        │
                    │  (BatchConfig.java:161-173)        │
                    └────────────────────────────────────┘
                                      │
                                      ▼
        ┌─────────────────────────────────────────────────────────┐
        │  SQL Query: findCurrentByEmail(email)                   │
        │  SELECT * FROM persons WHERE email = ? AND is_current=1 │
        └─────────────────────────────────────────────────────────┘
                                      │
                ┌─────────────────────┴─────────────────────┐
                │                                           │
                ▼ NOT FOUND                                 ▼ FOUND
    ┌─────────────────────┐               ┌────────────────────────────────┐
    │  Return null        │               │  Compare all fields:           │
    │  (Filter out)       │               │                                │
    │                     │               │  firstName == firstName ?      │
    │  Record not in DB   │               │  lastName  == lastName  ?      │
    └─────────────────────┘               │  email     == email     ?      │
                                          │  age       == age       ?      │
                                          └────────────────────────────────┘
                                                          │
                                ┌─────────────────────────┴──────────────────────┐
                                │                                                │
                                ▼ ALL MATCH                                      ▼ ANY DIFFERENT
                    ┌─────────────────────┐                      ┌─────────────────────┐
                    │  Return item        │                      │  Return null        │
                    │  (Include in output)│                      │  (Filter out)       │
                    │                     │                      │                     │
                    │  ✓ Written to CSV   │                      │  ✗ Not written      │
                    └─────────────────────┘                      └─────────────────────┘
                                      │
                                      ▼
                    ┌────────────────────────────────────┐
                    │        BATCH JOB COMPLETE          │
                    │                                    │
                    │  Final Results:                    │
                    │  • CSV records processed: 10,000   │
                    │  • DB records stored: ~10,000      │
                    │  • REST records read: 11,000       │
                    │  • Exact matches found: ~5,974     │
                    │  • Filtered out: ~5,026            │
                    └────────────────────────────────────┘
```

## Record Flow Summary

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         DATA FLOW SUMMARY                                 │
└──────────────────────────────────────────────────────────────────────────┘

Input CSV File (10,000 records)
        │
        │ STEP 1: csvToDbStep
        ▼
SQL Server Database (with versioning)
        │ is_current = 1 records
        │
        │ STEP 2: restCompareStep
        │ (used for comparison)
        │
        │◄─────────── REST API (11,000 records)
        │              │
        │              ├─ 6,000 from original data.csv
        │              └─ 5,000 generated unique records
        │
        ▼
Comparison Logic:
  • 11,000 REST records checked against DB
  • ~5,974 records match exactly
  • ~5,026 records filtered out because:
      - Not in DB (1,000 extra in REST)
      - Email not found
      - Fields don't match exactly
        │
        ▼
Output CSV (~5,974 matching records)
```

## Key Decision Points

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    KEY DECISION POINTS IN BATCH                         │
└─────────────────────────────────────────────────────────────────────────┘

1. personProcessor (Step 1)
   ├─ Has @ in email? ─── NO ──▶ SKIP (return null)
   └─ Has @ in email? ─── YES ──▶ PROCESS

2. upsertVersioned (Step 1 Writer)
   ├─ Email exists in DB? ─── NO ──▶ INSERT version 1
   └─ Email exists in DB? ─── YES ──▶ Compare fields
                                      ├─ Same? ──▶ KEEP current
                                      └─ Diff? ──▶ CREATE new version

3. personMatchProcessor (Step 2)
   ├─ Email in DB? ─── NO ──▶ FILTER OUT (return null)
   └─ Email in DB? ─── YES ──▶ Compare all fields
                               ├─ All match? ──▶ INCLUDE (return item)
                               └─ Any diff? ──▶ FILTER OUT (return null)
```

## Configuration Reference

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      CONFIGURATION (application.yml)                    │
└─────────────────────────────────────────────────────────────────────────┘

batch:
  chunk-size: 1000          ← Process 1000 records per chunk
  concurrency:
    enabled: true
    threads: 4              ← 4 parallel threads for processing
  rest:
    base-url: http://localhost:8085/api/persons
    page-size: 500          ← Fetch 500 records per REST call

file:
  upload:
    dir: ~/uploads/batch-csv      ← Input CSV location
  output:
    dir: ~/uploads/batch-output   ← Output CSV location
```

## Re-run Behavior

```
┌─────────────────────────────────────────────────────────────────────────┐
│                WHAT HAPPENS ON RE-UPLOADING SAME CSV?                   │
└─────────────────────────────────────────────────────────────────────────┘

Same CSV Upload → Step 1 (csvToDbStep)
                    │
                    ├─ VersioningPersonItemWriter detects: Data is SAME
                    ├─ No new versions created
                    └─ Database remains unchanged
                          │
                          ▼
                  Step 2 (restCompareStep)
                    │
                    ├─ REST API: Same 11,000 records (fixed data)
                    ├─ Database: Same records (unchanged)
                    └─ Comparison: Same logic
                          │
                          ▼
                  Output: Same ~5,974 matching records

RESULT: Idempotent behavior - same input produces same output
```

---
*Generated: 2025-12-23*
*Project: SpringBootBatchDemo*
*Batch Configuration: BatchConfig.java*
