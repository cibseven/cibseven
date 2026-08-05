<#macro endpoint_macro docsUrl="">
{
  <@lib.endpointInfo
      id = "deleteDeploymentsAsyncOperation"
      tag = "Deployment"
      summary = "Delete Async (POST)"
      desc = "Deletes multiple deployments asynchronously (batch)." />

  <@lib.requestBody
      mediaType = "application/json"
      dto = "DeleteDeploymentsDto"
      examples = [
                  '"example-1": {
                     "summary": "POST `/deployment/delete` Request Body 1",
                     "value": {
                       "deploymentIds": ["aDeployment", "anotherDeployment"],
                       "cascade": true,
                       "skipCustomListeners": true,
                       "skipIoMappings": false
                     }
                   }'
                ] />

  "responses": {

    <@lib.response
        code = "200"
        dto = "BatchDto"
        desc = "Request successful."
        examples = ['"example-1": {
                       "summary": "Status 200 Response 1",
                       "value": {
                         "id": "aBatchId",
                         "type": "deployment-deletion",
                         "totalJobs": 10,
                         "jobsCreated": 10,
                         "batchJobsPerSeed": 100,
                         "invocationsPerBatchJob": 1,
                         "seedJobDefinitionId": "aSeedJobDefinitionId",
                         "monitorJobDefinitionId": "aMonitorJobDefinitionId",
                         "batchJobDefinitionId": "aBatchJobDefinitionId",
                         "tenantId": "aTenantId",
                         "suspended": false,
                         "createUserId": "demo"
                       }
                     }'] />

    <@lib.response
        code = "400"
        dto = "ExceptionDto"
        last = true
        desc = "Bad Request.
                Returned if neither `deploymentIds` nor `deploymentQuery` resolves to any deployment id." />

  }
}
</#macro>
