<#macro dto_macro docsUrl="">
<@lib.dto desc = "One activity instance created by starting a child of an ad hoc sub process.">

    <@lib.property
        name = "activityId"
        type = "string"
        desc = "The id of the activity that was started."
    />

    <@lib.property
        name = "activityInstanceId"
        type = "string"
        desc = "The id of the activity instance that was created for it."
        last = true
    />

</@lib.dto>
</#macro>
