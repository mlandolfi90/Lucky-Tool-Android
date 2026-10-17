package com.danielealbano.androidremotecontrolmcp.testutil

import com.danielealbano.androidremotecontrolmcp.policy.subject.ResolverKind
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectResolution
import com.danielealbano.androidremotecontrolmcp.policy.subject.ToolPolicyCatalog
import com.danielealbano.androidremotecontrolmcp.policy.subject.ToolSubjectResolver
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test [ToolSubjectResolver]: a scripted tool resolves through its lambda (which sees the call's arguments and may
 * answer differently on each call). An unscripted tool keeps its catalog class: not-app-scoped / exempt-exit as
 * such, every other one `Unattributed` — so tests that script nothing behave exactly as before the gate.
 */
class ScriptedToolSubjectResolver : ToolSubjectResolver {
    private val scripts = ConcurrentHashMap<String, (JsonObject?) -> SubjectResolution>()
    private val calls = AtomicInteger(0)

    /** Number of [resolve] calls so far. */
    val resolveCount: Int get() = calls.get()

    /** `profiledPackages` of the latest [resolve] call. */
    @Volatile
    var lastProfiledPackages: Set<String> = emptySet()
        private set

    fun script(
        toolName: String,
        resolution: (JsonObject?) -> SubjectResolution,
    ) {
        scripts[toolName] = resolution
    }

    override suspend fun resolve(
        toolName: String,
        arguments: JsonObject?,
        profiledPackages: Set<String>,
    ): SubjectResolution {
        calls.incrementAndGet()
        lastProfiledPackages = profiledPackages
        val policyClass = ToolPolicyCatalog.requireClass(toolName)
        val unscripted =
            if (policyClass.kind == ResolverKind.NONE) {
                SubjectResolution.NotAppScoped
            } else {
                SubjectResolution.Unattributed
            }
        return scripts[toolName]?.invoke(arguments) ?: unscripted
    }
}
