<#macro dto_macro docsUrl="">
<@lib.dto desc = "">

    <@lib.property
        name = "activityIds"
        type = "array"
        itemType = "string"
        desc = "The ids of the children to start. An activity may appear more than once: the
                specification allows an activity of an ad hoc sub process to be performed multiple
                times."
    />

    <#-- A map of activity id to a map of variable name to variable value object: two levels, which
         the macro's additionalProperties flag cannot express. Left to it, the schema would say each
         value is a string and every generated client would model the request wrongly. -->
    <@lib.property
        name = "activityVariables"
        type = "object"
        addProperty = "\"additionalProperties\": { \"type\": \"object\", \"additionalProperties\": { \"$ref\": \"#/components/schemas/VariableValueDto\" } }"
        desc = "Variables per activity id, applied locally to the execution of every performance of
                that activity in this request. Each key is an activity id and each value a JSON
                object of variable name to variable value object.

                Keyed per activity definition, not per performance. If `activityIds` names the same
                activity twice, both performances receive the same variables; send one request per
                performance to vary them."
        last = true
    />

</@lib.dto>
</#macro>
