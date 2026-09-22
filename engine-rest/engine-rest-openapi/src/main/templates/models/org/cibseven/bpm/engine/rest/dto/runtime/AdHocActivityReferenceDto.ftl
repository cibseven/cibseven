<#macro dto_macro docsUrl="">
<@lib.dto desc = "">

    <@lib.property
        name = "elementId"
        type = "string"
        desc = "The id of the element to activate."
    />

    <@lib.property
        name = "variables"
        type = "object"
        additionalProperties = true
        dto = "VariableValueDto"
        desc = "Variables applied locally to the execution created for this element.

                The engine keys variables per element definition rather than per activation, so
                naming the same element twice in one request starts it twice but gives both
                performances the variables of the last entry. Send one request per performance to
                vary them."
        last = true
    />

</@lib.dto>
</#macro>
