package com.fromwau.klap

import com.fromwau.klap.internal.spec.Action
import com.fromwau.klap.internal.spec.NamedSpec

/** Globally-scoped, position-independent flags resolved before dispatch. */
public data class Globals(val json: Boolean)

/** What a successful parse resolves to: run this command, or show help/version instead. */
public sealed interface Invocation {
    // internal constructor: the generated copy() must stay internal, or a public copy() would let
    // consumers forge an Execute with an arbitrary scope. Public consumers read [command]/[globals],
    // match `is Invocation.Execute`, and run the resolved action via Execute.runAction().
    @ConsistentCopyVisibility
    public data class Execute internal constructor(
        val command: Command,
        val globals: Globals,
        // Non-null by construction, so running it needs no check: a group resolves to ShowHelp, and a
        // command with neither an action nor subcommands is rejected when the tree is built.
        internal val action: Action,
        internal val scope: ActionScope,
    ) : Invocation {
        /**
         * The bound inputs, readable without running the action: `with(exec.inputs) { name() }`. This is
         * what makes your own parsing testable — assert that an argv binds the values you expect, with no
         * action, no output and no exit.
         *
         * You get the reading half of an action's scope, not its colour operators, since nothing prints here.
         */
        public val inputs: ValueScope get() = scope
    }
    // internal constructor: globalSpecs is spec-typed (internal), so the generated copy() must
    // stay internal too, or a public copy() would re-expose that internal type.
    @ConsistentCopyVisibility
    public data class ShowHelp internal constructor(
        val command: Command,
        val qualifiedName: String,
        internal val globalSpecs: List<NamedSpec> = emptyList(),
        // The ROOT's own versioned status (not this command's), so a subcommand's help still lists
        // --version when the root has one, matching --version's position-independent behavior.
        internal val rootVersioned: Boolean = false,
        // Set by --help-all: render this node AND every descendant's help recursively, not just this node.
        internal val recursive: Boolean = false,
        // The ROOT's built-in surface, threaded down for the same reason [rootVersioned] is: a subcommand's
        // Global options block lists the tree's built-ins, and only the root knows which it still offers.
        internal val builtins: Builtins = Builtins.DEFAULT,
    ) : Invocation
    /** [json] prints the version as a JSON object rather than the plain `name version` line. */
    public data class ShowVersion(val cli: Cli, val json: Boolean = false) : Invocation
    public data class ShowCompletion(val cli: Cli, val shell: CompletionShell) : Invocation
    public data class ShowDocs(val cli: Cli, val format: DocFormat) : Invocation

    /** The hidden `__complete` builtin: print one tab-completion candidate per line for the given words. */
    public data class ShowCompleteCandidates(val cli: Cli, val words: List<String>) : Invocation
}
