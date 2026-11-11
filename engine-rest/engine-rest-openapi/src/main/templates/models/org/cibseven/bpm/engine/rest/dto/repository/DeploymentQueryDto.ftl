<#macro dto_macro docsUrl="">
<@lib.dto
    desc = "A deployment query which defines a group of deployments." >

    <@lib.property
        name = "id"
        type = "string"
        desc = "Filter by deployment id." />

    <@lib.property
        name = "name"
        type = "string"
        desc = "Filter by the deployment name. Exact match." />

    <@lib.property
        name = "nameLike"
        type = "string"
        desc = "Filter by the deployment name that the parameter is a substring of. The parameter can include the
                wildcard `%` to express like-strategy such as: starts with (`%`name), ends with (name`%`) or contains
                (`%`name`%`)." />

    <@lib.property
        name = "source"
        type = "string"
        desc = "Filter by the deployment source." />

    <@lib.property
        name = "withoutSource"
        type = "boolean"
        desc = "Filter by the deployment source whereby source is equal to `null`." />

    <@lib.property
        name = "tenantIdIn"
        type = "array"
        itemType = "string"
        desc = "Filter by a list of tenant ids. A deployment must have one of the given tenant ids.
                Must be a JSON array of Strings." />

    <@lib.property
        name = "withoutTenantId"
        type = "boolean"
        desc = "Only include deployments which belong to no tenant. Value may only be `true`, as `false` is the
                default behavior." />

    <@lib.property
        name = "includeDeploymentsWithoutTenantId"
        type = "boolean"
        desc = "Include deployments which belong to no tenant. Can be used in combination with `tenantIdIn`.
                Value may only be `true`, as `false` is the default behavior." />

    <@lib.property
        name = "deploymentBefore"
        type = "string"
        format = "date-time"
        desc = "Restricts to all deployments before the given date.
                By [default](${docsUrl}/reference/rest/overview/date-format/), the date must have the format
                `yyyy-MM-dd'T'HH:mm:ss.SSSZ`, e.g., `2013-01-23T14:42:45.000+0200`." />

    <@lib.property
        name = "deploymentAfter"
        type = "string"
        format = "date-time"
        desc = "Restricts to all deployments after the given date.
                By [default](${docsUrl}/reference/rest/overview/date-format/), the date must have the format
                `yyyy-MM-dd'T'HH:mm:ss.SSSZ`, e.g., `2013-01-23T14:42:45.000+0200`." />

    "sorting": {
      "type": "array",
      "nullable": true,
      "description": "Apply sorting of the result",
      "items":

        <#assign last = true >
        <#assign sortByValues = ['"id"', '"name"', '"deploymentTime"', '"tenantId"']>
        <#include "/lib/commons/sort-props.ftl" >

    }

</@lib.dto>
</#macro>
