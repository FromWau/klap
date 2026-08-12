package com.fromwau.klap.internal.spec

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.fold
import com.fromwau.klap.ActionScope
import com.fromwau.klap.CliError
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val klapJson = Json { encodeDefaults = true }

/** Why [Action.renderOutput] could not produce output: the action's own error, or a klap-side render/encode failure. */
internal sealed interface ActionError : IError {
    data class Failed(val error: CliError) : ActionError
    data object NotSerializable : ActionError
    data class EncodeFailed(val message: String?) : ActionError
    data class RenderFailed(val message: String?) : ActionError
}

/** The erased, non-generic face of an [ActionSpec]: run the action for its rendered output, or for its raw value. */
internal sealed interface Action {
    /**
     * Whether this action was declared with `actionSuspending { }`. The synchronous entry points read this
     * to refuse an action they cannot drive; `action { }` and `actionSuspending { }` are its only writers,
     * one always passing false and the other always true.
     */
    val suspending: Boolean

    /**
     * Run the action and render its result to display text (`--json` or human), for the
     * [com.fromwau.klap.run] terminal path.
     */
    suspend fun renderOutput(scope: ActionScope, json: Boolean): Result<String, ActionError>

    /**
     * Run the action and return its own typed result, unrendered, for the
     * [com.fromwau.klap.runAction] embedding hatch.
     */
    suspend fun evaluate(scope: ActionScope): Result<Any?, CliError>
}

/**
 * The sole [Action] implementation; [T] is bound only here, so the block/serializer/human compose behind
 * the erased [Action] surface. [serializer] is a lazy provider, not a resolved [KSerializer], so a
 * non-`@Serializable` return type fails only when `--json` actually renders, never at `cli { }`
 * construction (which would otherwise crash every invocation, including `--help`).
 */
internal class ActionSpec<T>(
    private val block: suspend ActionScope.() -> Result<T, CliError>,
    override val suspending: Boolean,
    private val serializer: () -> KSerializer<T>,
    private val human: (ActionScope.(T) -> String)?,
) : Action {
    override suspend fun renderOutput(scope: ActionScope, json: Boolean): Result<String, ActionError> =
        scope.block().fold(
            onError = { error -> Err(ActionError.Failed(error)) },
            onSuccess = { value -> if (json) renderJson(value) else renderHuman(scope, value) },
        )

    private fun renderJson(value: T): Result<String, ActionError> {
        val resolved = try {
            serializer()
        } catch (_: SerializationException) {
            return Err(ActionError.NotSerializable)
        }

        return try {
            Ok(klapJson.encodeToString(resolved, value))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Err(ActionError.EncodeFailed(e.message))
        }
    }

    private fun renderHuman(scope: ActionScope, value: T): Result<String, ActionError> = try {
        Ok(human?.invoke(scope, value) ?: value?.toString().orEmpty())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Err(ActionError.RenderFailed(e.message))
    }

    override suspend fun evaluate(scope: ActionScope): Result<Any?, CliError> = scope.block()
}
