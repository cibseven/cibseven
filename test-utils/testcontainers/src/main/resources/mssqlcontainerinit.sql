-- idempotent: Testcontainers runs the script again whenever a reusable container is reused (QA tests)
IF DB_ID('camengine') IS NULL CREATE DATABASE camengine collate SQL_Latin1_General_CP1_CS_AS;
IF (SELECT is_read_committed_snapshot_on FROM sys.databases WHERE name = 'camengine') = 0 ALTER DATABASE camengine SET READ_COMMITTED_SNAPSHOT ON;
ALTER LOGIN sa WITH DEFAULT_DATABASE = camengine;
USE camengine;