package com.danielealbano.androidremotecontrolmcp.policy.subject

/** Derives the [SubjectResolution] of one call, for the [ToolPolicyClass] values of one [ResolverKind]. */
interface PolicySubjectResolver {
    /**
     * Throws the same kind of [com.danielealbano.androidremotecontrolmcp.mcp.McpToolException] the tool would throw
     * for invalid arguments, a missing node or a missing permission; callers let it propagate (nothing executes).
     */
    suspend fun resolve(
        policyClass: ToolPolicyClass,
        request: SubjectRequest,
    ): SubjectResolution
}
