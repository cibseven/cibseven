<#macro dto_macro docsUrl="">
<@lib.dto desc = "">

    <@lib.property
        name = "elementId"
        type = "string"
        desc = "The id of the element that was activated."
    />

    <@lib.property
        name = "elementInstanceId"
        type = "string"
        desc = "The id of the element instance that was created for it."
        last = true
    />

</@lib.dto>
</#macro>
