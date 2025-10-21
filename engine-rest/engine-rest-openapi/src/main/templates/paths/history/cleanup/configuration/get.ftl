<#-- Generated From File: camunda-docs-manual/public/reference/rest/history/history-cleanup/get-cleanup-configuration/index.html -->
<#macro endpoint_macro docsUrl="">
{
  <@lib.endpointInfo
      id = "getHistoryCleanupConfiguration"
      tag = "History Cleanup"
      summary = "Get History Cleanup Configuration"
      desc = "Retrieves history cleanup batch window configuration (See
              [History cleanup](${docsUrl}/user-guide/process-engine/history/#history-cleanup))."
  />

  "responses": {

    <@lib.response
        code = "200"
        dto = "HistoryCleanupConfigurationDto"
        desc = "Request successful."
        examples = ['"example-1": {
                       "summary": "GET `/history/cleanup/configuration`",
                       "description": "GET `/history/cleanup/configuration`",
                       "value": {
                           "batchWindowStartTime": "2017-09-11T23:59:00.000+0200",
                           "batchWindowEndTime": "2017-09-12T02:00:00.000+0200",
                           "enabled": "true",
                           "historyCleanupStrategy": "removal-time-based",
                           "historyCleanupBatchSize": 500,
                           "historyCleanupDegreeOfParallelism": 1,
                           "removalTimeStrategy": "end",
                           "removalTimeProvider": "end",
                           "databaseSchemaUpdate": "true",
                           "jobExecutorActivate": true,
                           "jobExecutorDeploymentAware": false,
                           "jobExecutorPreferTimerJobs": false,
                           "historyCleanupJobLogTimeToLive": "P5D",
                           "taskMetricsTimeToLive": "P540D",
                           "batchOperationHistoryTimeToLive": "P5D",
                           "historyTimeToLive": "P10D",
                           "enforceHistoryTimeToLive": true,
                           "historyCleanupBatchThreshold": 10,
                           "historyCleanupMetricsEnabled": true
                        }
                     }']
        last = true
    />

  }

}
</#macro>