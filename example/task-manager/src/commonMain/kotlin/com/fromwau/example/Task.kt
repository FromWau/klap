package com.fromwau.example

import kotlinx.serialization.Serializable

@Serializable
data class Task(
    val id: Int,
    val title: String,
    val priority: Priority = Priority.MEDIUM,
    val tags: List<String> = emptyList(),
    val due: String? = null,
    val done: Boolean = false,
)


@Serializable
enum class Priority { LOW, MEDIUM, HIGH }
