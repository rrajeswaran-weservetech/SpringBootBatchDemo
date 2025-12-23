# Spring Batch + SQL Server: "Invalid object name 'BATCH_JOB_INSTANCE'" — Issue & Fix

Date: 2025-12-23 00:46 (local)

## Summary
While invoking the upload endpoint to launch a Spring Batch job, the application returned:

```
PreparedStatementCallback; uncategorized SQLException for SQL [SELECT JOB_INSTANCE_ID, JOB_NAME
FROM BATCH_JOB_INSTANCE
WHERE JOB_NAME = ?
 and JOB_KEY = ?]; SQL state [S0002]; error code [208]; Invalid object name 'BATCH_JOB_INSTANCE'.
```

Root cause: Spring Batch queried unqualified metadata table names (no schema prefix). Although `spring.batch.jdbc.table-prefix: dbo.BATCH_` was set, it was not applied because `@EnableBatchProcessing` overrides Spring Boot auto-configuration, which is responsible for reading that property.

Removing `@EnableBatchProcessing` restored Boot’s Batch auto-config so the configured table prefix is honored (queries target `dbo.BATCH_*`), and the metadata tables are auto-created when missing.

---

## Affected components
- Spring Batch metadata (JobRepository / `BATCH_*` tables)
- SQL Server (MSSQL)
- Spring Boot 3.3 application using HikariCP

## Symptoms
- API call to `POST /api/upload` fails with HTTP 500 and message containing: `Invalid object name 'BATCH_JOB_INSTANCE'`.
- Database has no `BATCH_*` tables in the expected schema, or queries are executed without schema qualification.

## Diagnosis checklist
1. Check application properties:
   - `spring.batch.jdbc.table-prefix` is set to `dbo.BATCH_` (or your schema):
     ```yaml
     spring:
       batch:
         jdbc:
           table-prefix: dbo.BATCH_
           initialize-schema: always
           platform: sqlserver
     ```
2. Confirm whether `@EnableBatchProcessing` is present in your configuration. If present, Boot’s auto-config is bypassed and the above property will be ignored for infrastructure beans.
3. Inspect your SQL Server for tables:
   ```sql
   SELECT s.name AS schema_name, t.name
   FROM sys.tables t
   JOIN sys.schemas s ON s.schema_id = t.schema_id
   WHERE t.name LIKE 'BATCH_%';
   ```
   - If none exist, they must be created (auto or manual).

## Resolution
1. Ensure the app relies on Spring Boot auto-configuration for Batch:
   - Remove `@EnableBatchProcessing` from your Batch configuration class(es).
   - Keep `@StepScope` annotations where needed (e.g., reader beans that use job parameters).

2. Keep Spring Batch schema initialization enabled for SQL Server during development:
   ```yaml
   spring:
     batch:
       jdbc:
         initialize-schema: always
         platform: sqlserver
         table-prefix: dbo.BATCH_
   ```

3. Ensure the domain DDL and writer use the same schema (here `dbo`):
   - `src/main/resources/schema-person.sql` creates `dbo.persons`.
   - Writer SQL uses `INSERT INTO dbo.persons (...)`.

4. Restart the application and verify creation of metadata tables under the correct schema.

## Verification steps
1. Clean restart the app:
   - `mvn spring-boot:run` or run the built JAR.
2. Verify tables exist in SQL Server under `dbo`:
   ```sql
   SELECT s.name AS schema_name, t.name
   FROM sys.tables t
   JOIN sys.schemas s ON s.schema_id = t.schema_id
   WHERE t.name LIKE 'BATCH_%';
   ```
3. Call the endpoint:
   ```powershell
   curl -F "file=@C:\\path\\to\\sample.csv" http://localhost:8084/api/upload
   ```
   - Expect HTTP 202 with JSON body including `jobId`, `executionId`, `status`, and `file`.

## Alternative fixes / Notes
- If your default schema is not `dbo`, set:
  ```yaml
  spring.batch.jdbc.table-prefix: yourSchema.BATCH_
  ```
  and create your domain table in `yourSchema` or fully-qualify writer SQL.
- If metadata tables already exist in a different schema, either:
  - Switch `table-prefix` to match that schema, or
  - Recreate them in `dbo` and point `table-prefix` accordingly.
- Manual fallback: run Spring Batch’s SQL Server DDL (`org/springframework/batch/core/schema-sqlserver.sql`) after replacing `BATCH_` with your schema-qualified prefix (e.g., `dbo.BATCH_`).
- For production, consider changing `initialize-schema` to `never` once objects are created.

## Current project state (post-fix)
- `@EnableBatchProcessing` is not used; Boot auto-config builds Batch infrastructure.
- `application.yml` contains:
  ```yaml
  spring:
    batch:
      job:
        enabled: false
      jdbc:
        initialize-schema: always
        platform: sqlserver
        table-prefix: dbo.BATCH_
  sql:
    init:
      mode: always
      schema-locations: classpath:schema-person.sql
  ```
- CSV writer targets `dbo.persons`.
- Build successful; endpoint launches jobs as expected.
