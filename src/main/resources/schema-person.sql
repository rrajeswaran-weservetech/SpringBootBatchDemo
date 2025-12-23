-- Create table if it does not exist (single-statement IF for Spring ScriptUtils)
IF OBJECT_ID(N'[dbo].[persons]', N'U') IS NULL
    CREATE TABLE [dbo].[persons] (
        [id] INT IDENTITY(1,1) NOT NULL PRIMARY KEY,
        [first_name] NVARCHAR(100) NULL,
        [last_name]  NVARCHAR(100) NULL,
        [email]      NVARCHAR(320) NOT NULL,
        [age]        INT NULL
    );

-- Create unique index if it does not exist (single-statement IF)
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_persons_email' AND object_id = OBJECT_ID(N'[dbo].[persons]'))
    CREATE UNIQUE INDEX [IX_persons_email] ON [dbo].[persons]([email]);
