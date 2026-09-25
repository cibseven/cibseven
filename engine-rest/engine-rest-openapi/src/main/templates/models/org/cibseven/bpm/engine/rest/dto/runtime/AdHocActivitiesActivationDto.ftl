<#macro dto_macro docsUrl="">
<@lib.dto desc = "">

    <@lib.property
        name = "elements"
        type = "array"
        dto = "AdHocActivityReferenceDto"
        desc = "The elements to activate, in the order they should be started. An element may appear
                more than once: the specification allows an activity of an ad hoc sub process to be
                performed multiple times."
        last = true
    />

</@lib.dto>
</#macro>
