package com.miniichat.data

import kotlinx.serialization.Serializable

enum class Role { user, assistant, system }

@Serializable
data class Attachment(
    val type: String,        // "image" | "file" | "audio"
    val uri: String,         // content:// or file path
    val mimeType: String,
    val name: String,
    val sizeBytes: Long = 0,
    /** Audio only: duration of the recording. */
    val durationMs: Long = 0,
    /** Audio only: local whisper transcript, if one was made. */
    val transcript: String? = null
)

@Serializable
data class Message(
    val id: String,
    val role: String,
    val content: String,
    val attachments: List<Attachment> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
