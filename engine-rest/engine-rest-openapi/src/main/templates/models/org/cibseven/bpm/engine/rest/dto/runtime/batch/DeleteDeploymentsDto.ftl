<#macro dto_macro docsUrl="">
<@lib.dto>

    <@lib.property
        name = "deploymentIds"
        type = "array"
        itemType = "string"
        desc = "A list of deployment ids to delete." />

    <@lib.property
        name = "deploymentQuery"
        type = "ref"
        dto = "DeploymentQueryDto"
        desc = "A deployment query to select the deployments to delete." />

    <@lib.property
        name = "cascade"
        type = "boolean"
        desc = "If `true`, cascade deletion to process instances, historic process instances and jobs
                related to the deleted deployments. Default: `false`." />

    <@lib.property
        name = "skipCustomListeners"
        type = "boolean"
        desc = "If `true`, skips custom execution listeners when cascading deletion to process instances." />

    <@lib.property
        name = "skipIoMappings"
        type = "boolean"
        last = true
        desc = "If `true`, skips [input/output variable mappings](${docsUrl}/user-guide/process-engine/variables/#input-output-variable-mapping)
                when cascading deletion to process instances." />

</@lib.dto>
</#macro>
