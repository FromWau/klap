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

/**
 * What a successful action leaves for the runner: the text to print, and the code to exit with. They
 * travel as a pair because the code is a projection of the value, which is erased by the time [text] is.
 */
internal class Rendered(val text: String, val exitCode: Int)

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
    suspend fun renderOutput(scope: ActionScope, json: Boolean): Result<Rendered, ActionError>

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
    private val exitCode: (ActionScope.(T) -> Int)?,
) : Action {
    override suspend fun renderOutput(scope: ActionScope, json: Boolean): Result<Rendered, ActionError> =
        scope.block().fold(
            onError = { error -> Err(ActionError.Failed(error)) },
            onSuccess = { value -> renderSuccess(scope, value, json) },
        )

    private fun renderSuccess(scope: ActionScope, value: T, json: Boolean): Result<Rendered, ActionError> {
        // Only on this arm: an error carries its own exit code, so consulting the projection there would
        // give two answers for one run. Guarded like [renderHuman], because it is consumer code running
        // inside klap's never-throw boundary.
        val code = try {
            exitCode?.invoke(scope, value) ?: 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Err(ActionError.RenderFailed(e.message))
        }
        return when (val text = if (json) renderJson(value) else renderHuman(scope, value)) {
            is Result.Error -> text
            is Result.Success -> Ok(Rendered(text.value, code))
        }
    }

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
