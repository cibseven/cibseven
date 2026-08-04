<#macro endpoint_macro docsUrl="">
{
  <@lib.endpointInfo
      id = "querySelfAuthorizations"
      tag = "Authorization"
      summary = "Get Authorizations"
      desc = "Queries for a list of the currently authenticated user's authorizations using a list of parameters."
  />

  "parameters" : [

    <#assign last = true >
    <#include "/lib/commons/authorization-self-query-params.ftl" >
    <@lib.parameters
        object = params
        last = last
    />
  ],

  "responses": {

    <@lib.response
        code = "200"
        dto = "AuthorizationDto"
        array = true
        desc = "Request successful."
        examples = ['"example-1": {
                       "summary": "Status 200.",
                       "description": "GET `/authorization/self`",
                       "value": [
                         {
                           "id": "anAuthorizationId",
                           "type": 0,
                           "permissions": [
                             "ALL"
                           ],
                           "userId": "jonny1",
                           "groupId": null,
                           "resourceType": 1,
                           "resourceId": "*"
                         },
                         {
                           "id": "anotherAuthorizationId",
                           "type": 0,
                           "permissions": [
                             "CREATE",
                             "READ"
                           ],
                           "userId": "jonny1",
                           "groupId": null,
                           "resourceType": 1,
                           "resourceId": "*",
                           "removalTime": "2018-02-10T14:33:19.000+0200",
                           "rootProcessInstanceId": "f8259e5d-ab9d-11e8-8449-e4a7a094a9d6"
                         }
                       ]
                     }']
    />

    <@lib.response
        code = "400"
        dto = "ExceptionDto"
        desc = "Returned if some of the query parameters are invalid, for example if a `sortOrder`
                parameter is supplied, but no `sortBy` is specified. See the
                [Introduction](${docsUrl}/reference/rest/overview/#error-handling)
                for the error response format."
        last = true
    />

  }

}
</#macro>