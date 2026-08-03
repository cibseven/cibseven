
<#assign sortByValues = [
  '"resourceType"',
  '"resourceId"'
]>

<#assign params = {
  "resourceType": {
    "type": "integer",
    "format": "int32",
    "desc": "Filter by an integer representation of the resource type. See the
             [User Guide](${docsUrl}/user-guide/process-engine/authorization-service/#resources)
             for a list of integer representations of resource types."
  },
  "resourceId": {
    "type": "string",
    "desc": "Filter by resource id."
  }
}>
