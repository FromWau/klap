package com.fromwau.klap

import com.fromwau.klap.internal.render.Candidate
import com.fromwau.klap.internal.spec.HolderSpec

public enum class CompletionShell {
    BASH, ZSH, FISH, POWERSHELL,
    ;

    public companion object {
        public fun fromOrNull(raw: String): CompletionShell? = when (raw.lowercase()) {
            "bash" -> BASH
            "zsh" -> ZSH
            "fish" -> FISH
            "powershell", "pwsh" -> POWERSHELL
            else -> null
        }
    }
}

/** The completion shell names as shown to users (matches the `completion` subcommand's enum choices). */
internal val COMPLETION_SHELL_NAMES: List<String> =
    CompletionShell.entries.map { it.name.lowercase() }

/**
 * DSL receiver for a `.completeWith { }` provider block: [current] is the partial word under the cursor
 * ("" if none yet), [words] the program-relative words typed so far (mirrors `COMP_WORDS[@]:1`, i.e.
 * everything after the program name, including [current] as the last entry). Call [candidate] (or
 * [candidates] for a plain list of values) to contribute completion candidates; the block itself returns
 * nothing, only the calls made against this receiver matter.
 *
 * Being a [ValueScope], it also reads the CLI's own declared inputs through their accessors — every global,
 * plus whatever the command under the cursor has been given so far — so a provider resolves `--file` the
 * same way the action will, instead of re-scanning [words] by hand. A scalar input the line has not supplied
 * yet, or whose value failed to convert, is unbound, and reading it aborts the provider, which the caller
 * reads as "no candidates" — usually the right answer, since a provider that needs an input it cannot see
 * has nothing to offer. A `multiple()` input instead reads back what has been typed so far, which may be
 * shorter than its declared minimum or empty, so a provider must not assume the arity an action can.
 *
 * Two costs to know. Reading ANY accessor resolves ALL of them: the command's inputs and every global are
 * converted and validated on that keypress, so a converter or `.validate { }` must be cheap and free of
 * side effects. And an aborted provider is indistinguishable from one that yielded nothing — the seam that
 * keeps a Tab press from dumping a stack trace also swallows a genuine bug in provider code.
 *
 * Candidates are prefix-filtered by [current] by default, matching on each candidate's value only, never
 * its description; pass `filterByPrefix = false` to `.completeWith` to do fuzzy matching against [current]
 * yourself instead.
 */
@KlapDsl
public class CompletionScope internal constructor(
    public val current: String,
    public val words: List<String>,
    // Lazy: a Tab press whose slot has no provider must never run a user converter or validator. NONE, not
    // the default SYNCHRONIZED — one completion resolves on one thread, so the lock would only cost.
    private val resolved: Lazy<Map<HolderSpec, Any?>>,
) : ValueScope() {

    override val values: Map<HolderSpec, Any?> get() = resolved.value

    // Not exceptional here, unlike in an action: an input the line has not reached yet is the expected
    // case, and the provider seam turns this into "no candidates". The message exists for the debugger
    // who unwraps it, not for a user.
    override fun unbound(spec: HolderSpec): Nothing =
        error(
            "input '${spec.name}' is not resolved for the command being completed: it has not been typed " +
                "yet, its value failed to convert, or it belongs to another command",
        )

    internal val collected: MutableList<Candidate> = mutableListOf()

    /**
     * Offers one completion candidate: [value] is what the shell inserts and matches [current] against;
     * [description], when present, is shown alongside it by shells that support it (zsh, fish,
     * PowerShell) and dropped by bash, whose native menu cannot show a per-candidate description.
     *
     * A call made after [completeFiles] is silently dropped, since the file directive only works alone.
     */
    public fun candidate(value: String, description: String? = null) {
        if (filesRequested) return
        collected += Candidate(value, description)
    }

    /** Offers each of [candidateValues] as a description-less candidate; sugar for calling [candidate] on every one. */
    public fun candidates(candidateValues: Iterable<String>) {
        candidateValues.forEach { candidate(it) }
    }

    /**
     * Hands this slot to the shell's own filesystem completion, the same thing `.file()` does for a whole
     * input. For a slot whose token is only PARTLY a path (`dd if=/dev/zero`, where `.file()` would mark
     * the whole operand as one) this is the only way to reach it: [nonPathPrefix] names the head of
     * [current] that is not part of the path (`"if="` here), which each shell peels off before running its
     * own path completion and puts back on whatever that inserts. The default, no prefix, means the whole
     * word is the path, which is what `.file()` means.
     *
     * Exclusive: it discards anything [candidate]/[candidates] collected before it and nothing may be added
     * after. Each generated script maps a LONE directive line to native file completion and treats any other
     * line as a literal candidate, so a directive sitting beside real candidates would be offered as the text
     * it is spelled with.
     */
    public fun completeFiles(nonPathPrefix: String = "") {
        collected.clear()
        collected += Candidate(COMPLETE_FILES + nonPathPrefix)
        filesRequested = true
    }

    private var filesRequested: Boolean = false
}

/**
 * Reserved single-candidate directive returned by [Invocation.ShowCompleteCandidates] when the slot under the
 * cursor takes a filesystem path, carrying the non-path head of that word (empty when the whole word is the
 * path) as everything after it on the line. commonMain cannot enumerate the filesystem, so each generated
 * script maps this lone directive to that shell's native file completion. The leading space keeps it from
 * colliding with, or prefix-matching, any real candidate or typed word; the trailing colon fixes where the
 * marker ends, so the scripts can test for it with a prefix match and take the rest of the line verbatim.
 */
internal const val COMPLETE_FILES = " klap:files:"

// Every shell delegates the whole decision to `program __complete -- <words + current>` and prints the
// returned lines LITERALLY (never re-eval'd, so a candidate like `$(...)` or a backtick can't execute on
// Tab). A lone COMPLETE_FILES line maps to that shell's native file completion; the rest go out verbatim.
public fun Cli.renderCompletion(shell: CompletionShell): String = when (shell) {
    CompletionShell.BASH -> renderBash()
    CompletionShell.ZSH -> renderZsh()
    CompletionShell.FISH -> renderFish()
    CompletionShell.POWERSHELL -> renderPowershell()
}

private fun Cli.renderBash(): String = $$"""
    # $$name bash completion (generated)
    _$$name() {
      local cur="${COMP_WORDS[COMP_CWORD]}"
      # bash's COMP_WORDBREAKS splits `--tag=r` into `--tag` `=` `r`, so rebuild the intact word and word
      # list from COMP_LINE and pass THOSE to __complete. (A quoted word with a space isn't rebuilt; a real
      # fix would need eval, which would reintroduce the injection this delegation avoids.)
      local line="${COMP_LINE:0:COMP_POINT}"
      local -a relWords
      read -ra relWords <<< "$line"
      local fullCur=""
      if [[ "$line" != *[[:space:]] ]]; then
        local n=${#relWords[@]}
        fullCur="${relWords[$((n - 1))]}"
        relWords=("${relWords[@]:0:$((n - 1))}")
      fi
      relWords=("${relWords[@]:1}")
      local -a lines
      mapfile -t lines < <("${COMP_WORDS[0]}" __complete -- "${relWords[@]}" "$fullCur")
      # Each line is `value` or `value\tdescription`. bash's menu cannot show a per-candidate description,
      # so drop everything from the FIRST tab on, keeping the value only (a COMPLETE_FILES line has no tab).
      local i
      for i in "${!lines[@]}"; do
        lines[$i]="${lines[$i]%%$'\t'*}"
      done
      if [ "${#lines[@]}" -eq 1 ] && [[ "${lines[0]}" == "$$COMPLETE_FILES"* ]]; then
        # The directive's tail is the non-path head of the word (dd's `if=`), which must not reach compgen.
        local nonPath="${lines[0]#"$$COMPLETE_FILES"}"
        local pathCur="${cur#"$nonPath"}"
        # The default COMP_WORDBREAKS holds `=` and has already split that head off $cur; where it has not,
        # bash overwrites the whole word on insertion, so put the head back on every match.
        local keep=""
        [ "$pathCur" = "$cur" ] || keep="$nonPath"
        mapfile -t COMPREPLY < <(compgen -f -- "$pathCur")
        COMPREPLY=("${COMPREPLY[@]/#/$keep}")
        # -o filenames marks these as paths: a directory match gets a trailing `/` to descend, not compgen's plain trailing space.
        compopt -o filenames 2>/dev/null
        return
      fi
      COMPREPLY=()
      if [ "$fullCur" = "$cur" ]; then
        # bash overwrites the whole current word on insertion, so re-prepend the part a bare value candidate
        # doesn't already cover (else "red" would blank the `-t` / `--tag=` prefix it was completing).
        local candidate flagPrefix k suffix
        for candidate in "${lines[@]}"; do
          flagPrefix="$fullCur"
          for ((k = ${#fullCur}; k >= 0; k--)); do
            suffix="${fullCur:${#fullCur}-k}"
            if [[ "$candidate" == "$suffix"* ]]; then
              flagPrefix="${fullCur:0:${#fullCur}-k}"
              break
            fi
          done
          COMPREPLY+=("${flagPrefix}${candidate}")
        done
      else
        COMPREPLY=("${lines[@]}")
      fi
    }
    complete -F _$$name $$name
    """.trimIndent()

private fun Cli.renderZsh(): String = $$"""
    #compdef $$name
    _$$name() {
      local -a lines
      lines=("${(@f)$("${words[1]}" __complete -- "${(@)words[2,-1]}")}")
      if [[ ${#lines[@]} -eq 1 && "${lines[1]}" == "$$COMPLETE_FILES"* ]]; then
        # _files matches $PREFIX as a whole path, so move the directive's non-path head (dd's `if=`) into
        # $IPREFIX, which is kept on the line but never matched against.
        local nonPath="${lines[1]#"$$COMPLETE_FILES"}"
        (( ${#nonPath} )) && compset -p ${#nonPath}
        _files
        return
      fi
      # Split each `value\tdescription` line: $values are matched/inserted, $descriptions are shown beside
      # them via `compadd -d` as "value  -- description" (a description-less line shows the bare value).
      local -a values descriptions
      local line
      for line in "${lines[@]}"; do
        values+=("${line%%$'\t'*}")
        if [[ "$line" == *$'\t'* ]]; then
          descriptions+=("${line%%$'\t'*}  -- ${line#*$'\t'}")
        else
          descriptions+=("${line%%$'\t'*}")
        fi
      done
      if (( ${#values[@]} > 0 )); then
        # compadd only offers candidates starting with $PREFIX (the whole typed word, e.g. "--tag=r"), but
        # __complete returns the bare value ("red"). Move the already-typed prefix into $IPREFIX via
        # `compset -p <count>`, count = leading chars the candidate doesn't cover (0, a no-op, for an ordinary
        # subcommand/flag/positional; strips "--tag=" for "--tag=r", "-t" for "-tr").
        local cur="$PREFIX"
        local curLen=${#cur}
        local candidate="${values[1]}"
        local k suffix stripLen
        for (( k = curLen; k >= 0; k-- )); do
          if (( k == 0 )); then
            suffix=""
          else
            suffix="${cur: -$k}"
          fi
          if [[ "$candidate" == "$suffix"* ]]; then
            stripLen=$(( curLen - k ))
            (( stripLen > 0 )) && compset -p "$stripLen"
            break
          fi
        done
      fi
      compadd -d descriptions -- "${values[@]}"
    }
    _$$name "$@"
    """.trimIndent()

private fun Cli.renderFish(): String = $$"""
    # $$name fish completion (generated)
    function __$${name}_klap_complete
        set -l tokens (commandline -opc)
        set -l current (commandline -ct)
        set -l response ($tokens[1] __complete -- $tokens[2..-1] "$current")
        set -l marker (string length -- "$$COMPLETE_FILES")
        if test (count $response) -eq 1; and test (string sub -l $marker -- "$response[1]") = "$$COMPLETE_FILES"
            # The directive's tail is the non-path head of the token (dd's `if=`): complete only what follows
            # it, then put it back, since fish replaces the WHOLE token with each line printed.
            set -l nonPath (string sub -s (math $marker + 1) -- "$response[1]")
            set -l pathCur (string sub -s (math (string length -- "$nonPath") + 1) -- "$current")
            for entry in (__fish_complete_path "$pathCur")
                printf '%s\n' "$nonPath$entry"
            end
            return
        end
        set -l curLen (string length -- "$current")
        # Each response line is `value` or `value\tdescription`; fish's completion reads that tab form
        # natively, so a line is emitted WHOLE and fish splits it into the inserted value and the shown
        # description. The value sits at the line's head, so the prefix match below is unaffected by any
        # trailing description. fish prefix-matches each candidate against the WHOLE current token, but
        # __complete returns the bare value ("red") for an attached `--tag=r`/`-tr`, so re-prepend the part
        # of the token the candidate already covers (a no-op for ordinary candidates, which cover it whole).
        for line in $response
            set -l flagPrefix "$current"
            for k in (seq $curLen -1 0)
                set -l suffix ""
                if test $k -gt 0
                    set suffix (string sub -s -$k -- "$current")
                end
                if string match -q -- "$suffix*" "$line"
                    set flagPrefix (string sub -l (math $curLen - $k) -- "$current")
                    break
                end
            end
            # printf, not echo: fish's echo treats a leading -e/-n/-s as its own flag and would swallow a
            # candidate that looks like one; printf's first arg is always the format string, so it never does.
            printf '%s\n' "$flagPrefix$line"
        end
    end
    complete -c $$name -f -a '(__$${name}_klap_complete)'
    """.trimIndent()

private fun Cli.renderPowershell(): String = $$"""
    # $$name powershell completion (generated)
    Register-ArgumentCompleter -Native -CommandName $$name -ScriptBlock {
        param($wordToComplete, $commandAst, $cursorPosition)
        $words = @($commandAst.CommandElements | ForEach-Object { $_.ToString() })
        if ($commandAst.CommandElements[-1].Extent.EndOffset -lt $cursorPosition) { $words += '' }
        $relative = if ($words.Count -gt 1) { $words[1..($words.Count - 1)] } else { @() }
        $lines = @(& $words[0] __complete -- $relative)
        if ($lines.Count -eq 1 -and $lines[0].StartsWith("$$COMPLETE_FILES")) {
            # The directive's tail is the non-path head of the word (dd's `if=`), which Get-ChildItem would
            # otherwise resolve as part of the path; it is put back below, since the whole word is replaced.
            $nonPath = $lines[0].Substring("$$COMPLETE_FILES".Length)
            $pathWord = if ($wordToComplete.StartsWith($nonPath)) { $wordToComplete.Substring($nonPath.Length) } else { $wordToComplete }
            # Get-ChildItem yields only leaf names, so re-prepend the exact directory prefix the user typed;
            # a directory completion ends in a separator so a further Tab descends into it, as other shells do.
            $prefix = if ($pathWord -match '^(.*[\\/])') { $matches[1] } else { '' }
            Get-ChildItem -Path "$pathWord*" | ForEach-Object {
                $completion = "$nonPath$prefix$($_.Name)"
                if ($_.PSIsContainer) { $completion += [System.IO.Path]::DirectorySeparatorChar }
                [System.Management.Automation.CompletionResult]::new($completion, $completion, 'ParameterValue', $completion)
            }
            return
        }
        $lines | ForEach-Object {
            # Split `value\tdescription` on the FIRST tab: the value is inserted and listed, the description
            # becomes the tooltip (a description-less line tooltips the value, unchanged from before).
            $value, $tip = $_ -split "`t", 2
            if (-not $tip) { $tip = $value }
            [System.Management.Automation.CompletionResult]::new($value, $value, 'ParameterValue', $tip)
        }
    }
    """.trimIndent()