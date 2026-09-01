<#macro endpoint_macro docsUrl="">
{
  <@lib.endpointInfo
      id = "completeAdHocSubProcess"
      tag = "Execution"
      summary = "Complete Ad Hoc Sub Process"
      desc = "Ends an ad hoc sub process, cancelling whatever is still running inside it.

              The performers decide when an ad hoc sub process is finished. Without this, a scope
              whose completion condition never becomes true has no way out short of deleting the
              process instance.

              Running children are cancelled whether or not `cancelRemainingInstances` is set on the
              scope. That attribute governs what happens when the completion condition is satisfied,
              not what happens when a performer says the scope is done."
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
      dto = "ExecutionTriggerDto"
      examples = ['"example-1": {
                     "summary": "POST `/execution/anExecutionId/ad-hoc-activities/complete`",
                     "value": {
                       "variables": {
                         "outcome": {"value": "approved", "type": "String"}
                       }
                     }
                   }']
  />

  "responses": {

    <@lib.response
        code = "204"
        desc = "Request successful."
    />

    <@lib.response
        code = "400"
        dto = "ExceptionDto"
        desc = "Returned if the execution does not exist or is not an ad hoc sub process scope."
        last = true
    />

  }

}

</#macro>
