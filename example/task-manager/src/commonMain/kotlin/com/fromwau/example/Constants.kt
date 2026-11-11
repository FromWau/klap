package com.fromwau.example

import com.fromwau.klap.Exit

internal enum class TaskExit(override val code: Int, override val meaning: String) : Exit {
    NotFound(3, "no task has that id"),
    AlreadyDone(4, "the task is already done"),
    CorruptStore(5, "the task store is not valid JSON"),
    IoError(6, "the task store could not be read or written"),
    StoreBusy(7, "another run holds the task store"),
}

/** The exits of every command that reads or writes the task store. */
internal val STORE_EXITS: Array<Exit> = arrayOf(TaskExit.CorruptStore, TaskExit.IoError, TaskExit.StoreBusy)

internal const val DEFAULT_STORE_FILE = "tasks.json"
