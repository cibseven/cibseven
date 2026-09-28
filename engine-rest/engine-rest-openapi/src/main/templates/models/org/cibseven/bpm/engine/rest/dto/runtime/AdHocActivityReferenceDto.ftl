<#macro dto_macro docsUrl="">
<@lib.dto desc = "">

    <@lib.property
        name = "activityId"
        type = "string"
        desc = "The id of the activity to activate."
    />

    <@lib.property
        name = "variables"
        type = "object"
        additionalProperties = true
        dto = "VariableValueDto"
        desc = "Variables applied locally to the execution created for this activity.

                The engine keys variables per activity definition rather than per activation, so
                naming the same activity twice in one request starts it twice but gives both
                performances the variables of the last entry. Send one request per performance to
                vary them."
        last = true
    />

</@lib.dto>
</#macro>
