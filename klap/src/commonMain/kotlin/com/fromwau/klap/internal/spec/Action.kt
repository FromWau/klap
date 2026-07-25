package com.fromwau.klap.internal.spec

import com.fromwau.klap.ActionScope
import com.fromwau.klap.CliError
import com.fromwau.klap.Result
import com.fromwau.klap.Result.*
import com.fromwau.klap.fold
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val klapJson = Json { encodeDefaults = true }

/** Why [Action.renderOutput] could not produce output: the action's own error, or a klap-side render/encode failure. */
internal sealed interface ActionError {
    data class Failed(val error: CliError) : ActionError
    data object NotSerializable : ActionError
    data class EncodeFailed(val message: String?) : ActionError
    data class RenderFailed(val message: String?) : ActionError
}

/** The erased, non-generic face of an [ActionSpec]: run the action for its rendered output, or for its raw value. */
internal sealed interface Action {
    /**
     * Run the action and render its result to display text (`--json` or human), for the
     * [com.fromwau.klap.run] terminal path.
     */
    fun renderOutput(scope: ActionScope, json: Boolean): Result<String, ActionError>

    /**
     * Run the action and return its own typed result, unrendered, for the
     * [com.fromwau.klap.runAction] embedding hatch.
     */
    fun evaluate(scope: ActionScope): Result<Any?, CliError>
}

/**
 * The sole [Action] implementation; [T] is bound only here, so the block/serializer/human compose behind
 * the erased [Action] surface. [serializer] is a lazy provider, not a resolved [KSerializer], so a
 * non-`@Serializable` return type fails only when `--json` actually renders, never at `cli { }`
 * construction (which would otherwise crash every invocation, including `--help`).
 */
internal class ActionSpec<T>(
    private val block: ActionScope.() -> Result<T, CliError>,
    private val serializer: () -> KSerializer<T>,
    private val human: (ActionScope.(T) -> String)?,
) : Action {
    override fun renderOutput(scope: ActionScope, json: Boolean): Result<String, ActionError> =
        scope.block().fold(
            onError = { error -> Error(ActionError.Failed(error)) },
            onSuccess = { value -> if (json) renderJson(value) else renderHuman(scope, value) },
        )

    private fun renderJson(value: T): Result<String, ActionError> {
        val resolved = try {
            serializer()
        } catch (_: SerializationException) {
            return Error(ActionError.NotSerializable)
        }

        return try {
            Success(klapJson.encodeToString(resolved, value))
        } catch (e: Exception) {
            Error(ActionError.EncodeFailed(e.message))
        }
    }

    private fun renderHuman(scope: ActionScope, value: T): Result<String, ActionError> = try {
        Success(human?.invoke(scope, value) ?: value?.toString().orEmpty())
    } catch (e: Exception) {
        Error(ActionError.RenderFailed(e.message))
    }

    override fun evaluate(scope: ActionScope): Result<Any?, CliError> = scope.block()
}
