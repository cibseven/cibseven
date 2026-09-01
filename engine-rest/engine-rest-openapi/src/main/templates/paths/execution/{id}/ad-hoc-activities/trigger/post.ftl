<#macro endpoint_macro docsUrl="">
{
  <@lib.endpointInfo
      id = "triggerAdHocActivities"
      tag = "Execution"
      summary = "Trigger Ad Hoc Activities"
      desc = "Starts one or more children of an ad hoc sub process.

              An ad hoc sub process starts nothing when it is entered: the performers decide which of
              its activities to perform, and in what order. Only a directly startable child can be
              started, which means an activity with no incoming sequence flow from within the scope.
              A gateway or an intermediate event inside the scope is reachable by flow but is never
              started directly.

              Either all of the requested activities are started or none of them are: the request is
              validated in full before anything is created."
  />

  "parameters" : [

      <@lib.parameter
          name = "id"
          location = "path"
          type = "string"
          required = true
          desc = "The id of the execution of the ad hoc sub process scope itself."
          last = true
      />

  ],

  <@lib.requestBody
      mediaType = "application/json"
      dto = "TriggerAdHocActivitiesDto"
      examples = ['"example-1": {
                     "summary": "POST `/execution/anExecutionId/ad-hoc-activities/trigger`",
                     "value": {
                       "activityIds": ["taskA", "taskB"],
                       "activityVariables": {
                         "taskA": {
                           "assignedTo": {"value": "alice", "type": "String"}
                         }
                       }
                     }
                   }']
  />

  "responses": {

    <@lib.response
        code = "200"
        dto = "AdHocActivityInstanceDto"
        array = true
        desc = "Request successful. Returns the activity instances that were created, in the order
                the activities were given."
        examples = ['"example-1": {
                       "summary": "Status 200.",
                       "description": "POST `/execution/anExecutionId/ad-hoc-activities/trigger`",
                       "value": [
                         {
                           "activityId": "taskA",
                           "activityInstanceId": "taskA:aActivityInstanceId"
                         },
                         {
                           "activityId": "taskB",
                           "activityInstanceId": "taskB:anotherActivityInstanceId"
                         }
                       ]
                     }']
    />

    <@lib.response
        code = "400"
        dto = "ExceptionDto"
        desc = "Returned if no activity ids were given, if the execution does not exist, if it is not
                an ad hoc sub process scope, or if any of the given activities is not directly
                startable. In the last case none of the activities are started."
        last = true
    />

  }

}

</#macro>
