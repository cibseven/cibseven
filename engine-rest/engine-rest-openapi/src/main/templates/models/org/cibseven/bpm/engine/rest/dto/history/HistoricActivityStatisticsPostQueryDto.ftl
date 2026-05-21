<#macro dto_macro docsUrl="">
<@lib.dto
    desc = "A historic activity statistics query which defines a group of historic activity statistics" >

    <#assign requestMethod="POST"/>
    <#include "/lib/commons/history-process-definition-activity-statistics-query-params.ftl" >
    <@lib.properties object=params last=true />

</@lib.dto>
</#macro>