-- Create table if it does not exist (single-statement IF for Spring ScriptUtils)
IF OBJECT_ID(N'[dbo].[persons]', N'U') IS NULL
    CREATE TABLE [dbo].[persons] (
        [id] INT IDENTITY(1,1) NOT NULL PRIMARY KEY,
        [first_name] NVARCHAR(100) NULL,
        [last_name]  NVARCHAR(100) NULL,
        [email]      NVARCHAR(320) NOT NULL,
        [age]        INT NULL,
        [version]    INT NOT NULL DEFAULT(1),
        [is_current] BIT NOT NULL DEFAULT(1),
        [updated_at] DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME()
    );

-- Add columns if table already exists (idempotent)
IF COL_LENGTH('dbo.persons', 'version') IS NULL
    ALTER TABLE dbo.persons ADD [version] INT NOT NULL DEFAULT(1);
IF COL_LENGTH('dbo.persons', 'is_current') IS NULL
    ALTER TABLE dbo.persons ADD [is_current] BIT NOT NULL DEFAULT(1);
IF COL_LENGTH('dbo.persons', 'updated_at') IS NULL
    ALTER TABLE dbo.persons ADD [updated_at] DATETIME2 NOT NULL DEFAULT SYSUTCDATETIME();

-- Drop old unique index if exists
IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_persons_email' AND object_id = OBJECT_ID(N'[dbo].[persons]'))
    DROP INDEX [IX_persons_email] ON [dbo].[persons];

-- Create filtered unique index on current version
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_persons_email_current' AND object_id = OBJECT_ID(N'[dbo].[persons]'))
    CREATE UNIQUE INDEX [UX_persons_email_current] ON [dbo].[persons]([email]) WHERE [is_current] = 1;
