<#macro dto_macro docsUrl="">
<@lib.dto>

    <@lib.property
        name = "engineName"
        type = "string"
        desc = "The name of the process engine." />

    <@lib.property
        name = "historyLevel"
        type = "string"
        desc = "The history level of the process engine, e.g., `full`, `audit`, `none`." />

    <@lib.property
        name = "authorizationEnabled"
        type = "boolean"
        desc = "Whether authorization is enabled on the process engine." />

    <@lib.property
        name = "enablePasswordPolicy"
        type = "boolean"
        desc = "Whether the password policy is enabled on the process engine." />

    <@lib.property
        name = "historyTimeToLive"
        type = "integer"
        format = "int32"
        desc = "The engine-wide default history time to live in days for process definitions and decision definitions if no other value is defined inside BPMN or DMN models. `-1` (default) means to keep history indefinitely, `0` means to delete history immediately." />

    <@lib.property
        name = "enforceHistoryTimeToLive"
        type = "boolean"
        last = true
        desc = "Feature flag that prevents the deployment or redeployment of any model resource (BPMN, DMN, CMMN) that contains a `historyTimeToLive` of `null` by throwing a `ProcessEngineException`. Defaults to `true`." />

</@lib.dto>
</#macro>