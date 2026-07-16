<#macro endpoint_macro docsUrl="">
{
  <@lib.endpointInfo
      id = "queryHistoricActivityStatistics"
      tag = "Historic Process Definition"
      summary = "Get Historic Activity Statistics (POST)"
      desc = "Retrieves historic statistics of a given process definition, grouped by activities.
              These statistics include the number of running activity instances and,
              optionally, the number of canceled activity instances, finished
              activity instances and activity instances which completed a scope
              (i.e., in BPMN 2.0 manner: a scope is completed by an activity
              instance when the activity instance consumed a token but did not emit
              a new token).
              This method is slightly more powerful than the
              [Get Historic Activity Statistics](${docsUrl}/reference/rest/history/process-definition/get-historic-activity-statistics/)
              because it allows filtering by multiple process variables of types `String`, `Number` or `Boolean`.
              **Note:** This only includes historic data."
              />

  "parameters" : [
    <@lib.parameter
        name = "id"
        location = "path"
        type = "string"
        required = true
        last = true
        desc = "The id of the process definition."
    />
  ],
  <#assign requestMethod="POST"/>
  <@lib.requestBody
      mediaType = "application/json"
      dto = "HistoricActivityStatisticsPostQueryDto"
      examples = [
                  '"example-1": {
                     "summary": "POST `/history/process-definition/aProcessDefinitionId/statistics`",
                     "value": {
                                "finishedAfter": "2013-01-01T00:00:00.000+0200",
                                "finishedBefore": "2013-04-01T23:59:59.000+0200",
                                "executedActivityAfter": "2013-03-23T13:42:44.000+0200",
                                "variables": [
                                  {
                                    "name": "myVariable",
                                    "operator": "eq",
                                    "value": "camunda"
                                  },
                                  {
                                    "name": "mySecondVariable",
                                    "operator": "neq",
                                    "value": 124
                                  }
                                ]
                              }
                   }'
                ] />
  "responses" : {
    <@lib.response
        code = "200"
        dto = "HistoricActivityStatisticsDto"
        array = true
        desc = "Request successful."
        examples = ['"example-1": {
                       "summary": "Status 200 response",
                       "description": "Response for POST `/history/process-definition/aProcessDefinitionId/statistics`",
                       "value": [
                         {
                           "id": "anActivity",
                           "instances": 123,
                           "canceled": 50,
                           "finished": 0,
                           "completeScope": 0,
                           "openIncidents": 0,
                           "resolvedIncidents": 0,
                           "deletedIncidents": 0
                         },
                         {
                           "id": "anotherActivity",
                           "instances": 200,
                           "canceled": 150,
                           "finished": 0,
                           "completeScope": 0,
                           "openIncidents": 0,
                           "resolvedIncidents": 0,
                           "deletedIncidents": 0
                         }
                       ]
                     }'] />

    <@lib.response
        code = "400"
        dto = "ExceptionDto"
        last = true
        desc = "Bad Request
                Returned if some of the query parameters are invalid, for example if a date parameter is supplied in an incorrect format.
                See the [Introduction](${docsUrl}/reference/rest/overview/#error-handling) for the error response format."/>
  }
}
</#macro>