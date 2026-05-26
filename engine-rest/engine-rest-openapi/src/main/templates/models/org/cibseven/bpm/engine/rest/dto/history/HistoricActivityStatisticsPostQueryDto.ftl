<#macro dto_macro docsUrl="">
<@lib.dto
    desc = "A historic activity statistics query which defines a group of historic activity statistics" >

    <#assign requestMethod="POST"/>
    <#include "/lib/commons/history-process-definition-activity-statistics-params.ftl" >
    <@lib.properties params/>

    "sorting": {
      "type": "array",
      "nullable": true,
      "description": "Apply sorting of the result",
      "items":

        <#assign last = true >
        <#include "/lib/commons/sort-props.ftl" >

    }

</@lib.dto>
</#macro>