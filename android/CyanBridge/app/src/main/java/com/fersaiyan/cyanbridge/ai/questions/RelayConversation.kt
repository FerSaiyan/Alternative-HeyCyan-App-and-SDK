package com.fersaiyan.cyanbridge.ai.questions

import com.fersaiyan.cyanbridge.shared.ai.RelayTurn
import java.io.File

/**
 * A Pro relay spoken conversation, kept by the app: the relay keeps none, so every follow-up resends the earlier turns
 * as text (`history`) and the conversation's photo with the new recording. The question as the model heard it comes
 * from the reply's last line ([HEARD_INSTRUCTION]); the answer is the reply without that line.
 */
internal object RelayConversation {
    /** Pro decision turns end with this line; [split] takes it off before the reply is parsed or spoken. */
    const val HEARD_INSTRUCTION =
        "After your reply, on a separate last line, write [heard] and their question as you heard it, in the language " +
            "they spoke. That line is never read out."

    private val HEARD_LINE = Regex("""\n?[ \t]*\[\s*heard\s*][^\n]*\s*$""", RegexOption.IGNORE_CASE)
    private const val UNHEARD = "(a spoken question)"

    private val turns = mutableListOf<RelayTurn>()

    /** The photo of the conversation (a photo question's, or one a [camera] reply took), resent with follow-ups. */
    @Volatile var photo: String? = null
        private set

    /** Whether the flow handed [photo] over for deletion; it is deleted when the conversation ends. */
    private var photoOwned = false

    @Synchronized
    fun start(photo: String?) {
        endPhoto()
        turns.clear()
        this.photo = photo
    }

    /** The turns before the question being answered (its own pair, when recorded, is the last two). */
    @Synchronized
    fun history(excludingCurrent: Boolean = false): List<RelayTurn> =
        if (excludingCurrent && turns.size >= 2) turns.dropLast(2) else turns.toList()

    @Synchronized
    fun record(heard: String?, reply: String) {
        turns += RelayTurn(fromUser = true, text = heard?.takeIf { it.isNotBlank() } ?: UNHEARD)
        turns += RelayTurn(fromUser = false, text = reply)
    }

    /** The current question was answered in another request ([camera], web results): that answer is what was said. */
    @Synchronized
    fun replaceLastAnswer(answer: String) {
        if (turns.lastOrNull()?.fromUser == false) turns[turns.lastIndex] = RelayTurn(fromUser = false, text = answer)
    }

    /** A photo a [camera] reply took: kept for follow-ups instead of being deleted with the turn. */
    @Synchronized
    fun adoptPhoto(path: String) {
        if (photo == path) return
        endPhoto()
        photo = path
        photoOwned = true
    }

    /** True when [path] is kept for follow-ups (the caller must not delete it now). */
    @Synchronized
    fun keeps(path: String): Boolean = photoOwned && photo == path

    private fun endPhoto() {
        if (photoOwned) photo?.let { File(it).delete() }
        photo = null
        photoOwned = false
    }

    /** The reply without its [heard] line, and the heard question. */
    fun split(reply: String): Pair<String, String?> {
        val match = HEARD_LINE.find(reply) ?: return reply to null
        val heard = match.value.trim().replaceFirst(Regex("""^\[\s*heard\s*]\s*""", RegexOption.IGNORE_CASE), "").trim()
        return reply.removeRange(match.range).trimEnd() to heard.ifBlank { null }
    }
}
