<#macro endpoint_macro docsUrl="">
{
  <@lib.endpointInfo
      id = "activateAdHocSubProcessActivities"
      tag = "Execution"
      summary = "Activate Ad Hoc Sub Process Activities"
      desc = "Activates one or more activities of an ad hoc sub process.

              Unless its model names activities to activate on entry, an ad hoc sub process starts
              nothing when it is entered: the performers decide which of its activities to perform,
              and in what order. Only a directly startable activity can be activated, that is one
              with no incoming sequence flow from within the scope.
              A gateway or an intermediate event inside the scope is reachable by flow but is never
              started directly.

              Either all of the requested activities are activated or none of them are: the request is
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
      dto = "AdHocActivitiesActivationDto"
      examples = ['"example-1": {
                     "summary": "POST `/execution/anExecutionId/ad-hoc-activities/activate`",
                     "value": {
                       "activities": [
                         {
                           "activityId": "taskA",
                           "variables": {
                             "assignedTo": {"value": "alice", "type": "String"}
                           }
                         },
                         {
                           "activityId": "taskB"
                         }
                       ]
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
                       "description": "POST `/execution/anExecutionId/ad-hoc-activities/activate`",
                       "value": [
                         {
                           "activityId": "taskA",
                           "activityInstanceId": "taskA:anActivityInstanceId"
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
        desc = "Returned if no activities were given, if the execution does not exist, if it is not
                an ad hoc sub process scope, or if any of the given activities is not directly
                startable. In the last case none of the activities are activated."
        last = true
    />

  }

}

</#macro>
