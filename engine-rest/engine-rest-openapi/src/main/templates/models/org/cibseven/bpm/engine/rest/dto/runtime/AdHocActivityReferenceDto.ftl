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

                Each entry keeps its own: naming the same activity twice in one request with
                different variables starts it twice, and each performance gets the variables of its
                own entry."
        last = true
    />

</@lib.dto>
</#macro>
