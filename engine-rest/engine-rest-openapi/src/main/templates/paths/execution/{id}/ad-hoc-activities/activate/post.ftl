<#macro endpoint_macro docsUrl="">
{
  <@lib.endpointInfo
      id = "activateAdHocSubProcessActivities"
      tag = "Execution"
      summary = "Activate Ad Hoc Sub Process Activities"
      desc = "Activates one or more elements of an ad hoc sub process.

              An ad hoc sub process starts nothing when it is entered: the performers decide which of
              its elements to perform, and in what order. Only a directly startable element can be
              activated, which means an activity with no incoming sequence flow from within the scope.
              A gateway or an intermediate event inside the scope is reachable by flow but is never
              started directly.

              Either all of the requested elements are activated or none of them are: the request is
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
                       "elements": [
                         {
                           "elementId": "taskA",
                           "variables": {
                             "assignedTo": {"value": "alice", "type": "String"}
                           }
                         },
                         {
                           "elementId": "taskB"
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
        desc = "Request successful. Returns the element instances that were created, in the order
                the elements were given."
        examples = ['"example-1": {
                       "summary": "Status 200.",
                       "description": "POST `/execution/anExecutionId/ad-hoc-activities/activate`",
                       "value": [
                         {
                           "elementId": "taskA",
                           "elementInstanceId": "taskA:anElementInstanceId"
                         },
                         {
                           "elementId": "taskB",
                           "elementInstanceId": "taskB:anotherElementInstanceId"
                         }
                       ]
                     }']
    />

    <@lib.response
        code = "400"
        dto = "ExceptionDto"
        desc = "Returned if no elements were given, if the execution does not exist, if it is not
                an ad hoc sub process scope, or if any of the given elements is not directly
                startable. In the last case none of the elements are activated."
        last = true
    />

  }

}

</#macro>
