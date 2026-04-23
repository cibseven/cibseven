<#macro endpoint_macro docsUrl="">
{
  <@lib.endpointInfo
      id = "getProcessEngineConfiguration"
      tag = "Configuration"
      summary = "Get Process Engine Configuration"
      desc = "Retrieves configuration parameters of the process engine." />

  "responses" : {
    <@lib.response
        code = "200"
        dto = "ProcessEngineConfigurationDto"
        last = true
        desc = "Request successful."
        examples = ['"example-1": {
                       "summary": "Status 200 Response",
                       "description": "The Response content of a status 200",
                       "value": {
                           "history": "full",
                           "authorizationEnabled": false,
                           "enablePasswordPolicy": false
                         }
                     }'] />
  }
}
</#macro>