<#macro dto_macro docsUrl="">
<@lib.dto>

    <@lib.property
        name = "history"
        type = "string"
        desc = "The history level of the process engine, e.g., `full`, `audit`, `none`." />

    <@lib.property
        name = "authorizationEnabled"
        type = "boolean"
        desc = "Whether authorization is enabled on the process engine." />

    <@lib.property
        name = "enablePasswordPolicy"
        type = "boolean"
        last = true
        desc = "Whether the password policy is enabled on the process engine." />

</@lib.dto>
</#macro>