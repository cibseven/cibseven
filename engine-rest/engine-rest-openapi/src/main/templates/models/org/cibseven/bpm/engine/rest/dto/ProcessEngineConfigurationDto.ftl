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
        name = "webclientExtensionsVersion"
        type = "string"
        desc = "The version of the webclient schema present in the database, or `null` when those
                tables are absent. The webclient reads it to decide whether the modeler, chat and
                notification features can be used." />

    <@lib.property
        name = "historyTimeToLive"
        type = "integer"
        format = "int32"
        nullable = false
        minimum = -1
        desc = "The engine-wide default history time to live in days. The engine applies it at deployment time to every process, decision and case definition whose BPMN, DMN or CMMN model does not set its own `historyTimeToLive`; changing it later does not affect definitions that are already deployed.
                The configured value may be written in days (`180`) or as an ISO-8601 period in days (`P180D`); it is always reported here as a number of days.
                `0` means history becomes eligible for removal right away and is deleted by the next history cleanup run.
                `-1` (default) means that no engine-wide default is configured: definitions without their own TTL keep their history indefinitely, and if `enforceHistoryTimeToLive` is `true`, deploying models without a TTL fails.
                `-1` is never a configured value itself, as the engine rejects negative values.
                The property is missing for engines that do not expose it (before 3.0.0)." />

    <@lib.property
        name = "enforceHistoryTimeToLive"
        type = "boolean"
        nullable = false
        last = true
        desc = "Whether the engine enforces a history time to live for deployed models. If `true`, deploying or redeploying a model resource (BPMN, DMN, CMMN) fails with a `ProcessEngineException` when no history time to live can be resolved for it, i.e. when the model does not set its own `historyTimeToLive` and no engine-wide default is configured (`historyTimeToLive` is `-1` here). If an engine-wide default is configured, models without their own TTL deploy fine and get the default.
                Enforcement only applies to new (re)deployments; definitions deployed earlier may still have no TTL.
                Defaults to `true`.
                The property is missing for engines that do not expose it (before 3.0.0); clients must treat that as unknown, not as `false`." />

</@lib.dto>
</#macro>
