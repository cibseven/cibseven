<#macro endpoint_macro docsUrl="">
{
  <@lib.endpointInfo
      id = "queryOwnAuthorizations"
      tag = "Authorization"
      summary = "Get Own Authorizations"
      desc = "Queries for a list of the authorizations that are applicable to the currently
              authenticated user, using a list of parameters. This includes the authorizations
              that address the user directly, the authorizations that address one of the user's
              groups and the global authorizations.
              In contrast to [Get Authorizations](${docsUrl}/reference/rest/authorization/get-query/),
              the result is not restricted to the authorizations the user is allowed to read, and
              it is not paginated: all matching authorizations are returned."
  />

  "parameters" : [

    <#assign last = false >
    <#include "/lib/commons/authorization-self-query-params.ftl" >
    <@lib.parameters
        object = params
        last = last
    />
    <#assign last = true >
    <#include "/lib/commons/sort-params.ftl">

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
                           "type": 1,
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
                           "userId": "*",
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
    />

    <@lib.response
        code = "401"
        dto = "ExceptionDto"
        desc = "The user is not authenticated. See the
                [Introduction](${docsUrl}/reference/rest/overview/#error-handling)
                for the error response format."
        last = true
    />

  }

}
</#macro>
