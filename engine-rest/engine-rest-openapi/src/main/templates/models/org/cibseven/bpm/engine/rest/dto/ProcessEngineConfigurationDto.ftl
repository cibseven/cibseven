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
        type = "string"
        desc = "The engine-wide default history time to live, in days. `null` if not configured, meaning historic data is kept indefinitely unless a process or decision definition sets its own value." />

    <@lib.property
        name = "enforceHistoryTimeToLive"
        type = "boolean"
        last = true
        desc = "Whether the process engine requires an explicit history time to live to be set (rejecting `null` values). `null` if this process engine does not report it." />

</@lib.dto>
</#macro>