package com.fersaiyan.cyanbridge.shared.chat

/**
 * Chat messages are plain text, so an attached photo is stored as a leading Markdown image
 * pointing at a file in the app data folder: `![photo](cyanbridge-image:chat-image-1.jpg)`.
 * The bubble shows the image; prompts sent to an LLM use [stripImage].
 */
object ChatImageMarkup {
    private const val SCHEME = "cyanbridge-image:"
    private val LEADING_IMAGE = Regex("^!\\[[^\\]]*]\\(" + Regex.escape(SCHEME) + "([^)\\s]+)\\)\\s*")

    fun attach(relativePath: String, text: String): String = "![photo]($SCHEME$relativePath)\n$text"

    /** File name relative to the app data folder, or null when the message has no image. */
    fun imagePath(content: String): String? = LEADING_IMAGE.find(content)?.groupValues?.get(1)

    fun stripImage(content: String): String = content.replaceFirst(LEADING_IMAGE, "")
}
