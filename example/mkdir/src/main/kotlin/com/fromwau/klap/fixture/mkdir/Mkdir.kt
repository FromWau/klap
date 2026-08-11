package com.fromwau.klap.fixture.mkdir

import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.11 `mkdir`, reproduced as a command-line surface only.
 *
 * Real usage: `mkdir [OPTION]... DIRECTORY...`
 *   -m, --mode=MODE      set file mode (as in chmod), not a=rwx - umask
 *   -p, --parents        no error if existing, make parent directories as needed
 *   -v, --verbose        print a message for each created directory
 *   -Z                   set the SELinux security context to the default type
 *       --context[=CTX]  like -Z, or set the SELinux/SMACK context to CTX
 *       --help / --version
 */
public fun mkdirCli(): TypedCli<MkdirInputs> = cliOf("mkdir") {
    // GNU mkdir takes any unambiguous prefix of a long option: `mkdir --par` is --parents.
    abbreviation = Abbreviation.Options
    description = "Create the DIRECTORY(ies), if they do not already exist."
    version = "9.11"
    epilogue = "Report bugs to: bug-coreutils@gnu.org"

    example("mkdir -p a/b/c", "create every missing parent along the way")
    example("mkdir -m 0700 private", "create with an explicit octal mode")
    example("mkdir -pv one two three", "several operands at once, verbosely")

    // GNU mkdir's -m is "as in chmod", so it also accepts SYMBOLIC modes (`u=rwx,go=rx`, `-w`).
    // The study brief scopes this to octal, so the validate below is octal-only.
    val mode = option("--mode", "-m", help = "set file mode (as in chmod), not a=rwx - umask")
        .validate("must be an octal mode, e.g. 755 or 0700") { it.matches(Regex("[0-7]{1,4}")) }

    val parents = flag("--parents", "-p", help = "no error if existing, make parent directories as needed")

    val verbose = flag("--verbose", "-v", help = "print a message for each created directory")

    val selinux = flag("-Z", help = "set the SELinux security context of each directory to the default type")

    val context = option("--context", help = "like -Z, or set the SELinux or SMACK security context to CTX")
        .optionalValue("default")

    val directories = argument("directory", "directory to create").file().multiple(min = 1)

    action<String>(human = { it }) {
        val flags = buildList {
            if (parents()) add("-p")
            if (verbose()) add("-v")
            if (selinux()) add("-Z")
            mode()?.let { add("-m $it") }
            context()?.let { add("--context=$it") }
        }
        Ok("would create ${directories().size} director(y|ies) ${flags.joinToString(" ")}")
    }

    projection { MkdirInputs(mode(), parents(), verbose(), selinux(), context(), directories()) }
}

/**
 * What one `mkdir` line binds, as values rather than as the handles that read them.
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does
 * not name is pinned to its default too.
 */
public data class MkdirInputs(
    val mode: String?,
    val parents: Boolean,
    val verbose: Boolean,
    val selinux: Boolean,
    val context: String?,
    val directories: List<String>,
)

/** `mkdir` with no options: every field at the default its declaration gives it. */
public val NOTHING_BOUND: MkdirInputs = MkdirInputs(
    mode = null,
    parents = false,
    verbose = false,
    selinux = false,
    context = null,
    directories = emptyList(),
)
