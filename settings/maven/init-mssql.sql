IF DB_ID(N'process-engine') IS NULL
    CREATE DATABASE [process-engine] COLLATE SQL_Latin1_General_CP1_CS_AS;
GO
ALTER DATABASE [process-engine] SET READ_COMMITTED_SNAPSHOT ON;
GO
USE [process-engine];
GO
IF SUSER_ID(N'camunda') IS NULL
    CREATE LOGIN camunda WITH PASSWORD = N'Camunda-BPM123', DEFAULT_DATABASE = [process-engine], CHECK_POLICY = OFF;
GO
IF USER_ID(N'camunda') IS NULL
BEGIN
    CREATE USER camunda FOR LOGIN camunda;
    ALTER ROLE db_owner ADD MEMBER camunda;
END
GO
