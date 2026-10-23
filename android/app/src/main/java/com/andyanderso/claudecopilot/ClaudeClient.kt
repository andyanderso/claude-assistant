package com.andyanderso.claudecopilot

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(val role: String, val text: String)   // role: "user" | "assistant"
data class Source(val url: String, val title: String)
data class Reply(val text: String, val sources: List<Source>, val model: String)

class ClaudeApiException(val code: Int, val body: String) : IOException("Claude API error $code")

/**
 * Minimal client for the Claude Messages API. No SDK: one HTTPS POST per turn.
 * Blocking — always call [ask] off the main thread.
 */
class ClaudeClient(private val prefs: Prefs) {

    companion object {
        const val HAIKU = "claude-haiku-5-5"
        const val SONNET = "claude-sonnet-5-5"
        const val OPUS = "claude-opus-5-5"

        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"

        const val DEFAULT_SYSTEM =
            "You are Claude Copilot, a voice assistant the user talks to on their Android phone, often while driving. " +
            "You prioritize accuracy and well-sourced answers. " +
            "GROUNDING: When a question turns on facts, current information, data, science, health, or anything checkable, use web search and rely on the most reputable sources you can find — peer-reviewed research, primary data, government and academic institutions, and established expert outlets — over blogs, forums, marketing, or SEO content. Prefer primary sources over summaries of them. " +
            "RELIABILITY: Tell the user how solid the basis is. Flag out loud when a source is weak, contested, preliminary (a preprint or a single small study), anecdotal, or when experts disagree, and distinguish established consensus from early or fringe findings. If the evidence is thin or you're unsure, say so plainly instead of sounding confident. Never invent studies, statistics, quotes, or citations — if you can't find real support, say that. " +
            "CONFIDENCE: Begin every factual or checkable answer by speaking one short confidence cue first — 'High confidence' when it's well established and backed by strong reputable sources, 'Moderate confidence' when support is reasonable but limited or partial, or 'Low confidence' when the evidence is thin, contested, preliminary, or from memory without solid sourcing — then a dash and your answer. Skip the cue entirely for casual conversation, greetings, or chit-chat. " +
            "VOICE: Keep replies short and conversational, usually one to three sentences, the way a person speaks — no markdown, lists, headings, or emoji, since everything is read aloud. Attribute naturally out loud ('a 2024 review in Nature found…', 'according to NOAA…') and describe reliability in words; the screen shows the source links, so don't read URLs aloud. Lead with the key point, then offer to go deeper. When the user wants to research a topic, have a real back-and-forth: answer, then ask what they want to dig into next. Don't search for casual conversation or chit-chat."
    }

    fun ask(history: List<ChatMessage>): Reply {
        val model = ModelRouter.pick(prefs.model, history.lastOrNull { it.role == "user" }?.text ?: "")
        val messages = JSONArray()
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }

        val today = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())
        val system = prefs.systemPrompt + " Today's date is $today."

        val text = StringBuilder()
        val sources = LinkedHashMap<String, Source>()   // keeps first-seen order, drops duplicates

        // With web search on, the API can pause a long turn (stop_reason "pause_turn").
        // We hand the partial turn back and let it finish, a few rounds at most.
        repeat(4) {
            val body = JSONObject()
                .put("model", model)
                .put("max_tokens", 1024)
                .put("system", system)
                .put("messages", messages)
            if (prefs.grounding) {
                body.put("tools", JSONArray().put(
                    JSONObject()
                        .put("type", "web_search_20250305")
                        .put("name", "web_search")
                        .put("max_uses", 5)
                ))
            }

            val res = post(body)
            val content = res.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> {
                        text.append(block.optString("text")).append(' ')
                        block.optJSONArray("citations")?.let { cites ->
                            for (j in 0 until cites.length()) {
                                val c = cites.optJSONObject(j) ?: continue
                                addSource(sources, c.optString("url"), c.optString("title"))
                            }
                        }
                    }
                    "web_search_tool_result" -> block.optJSONArray("content")?.let { results ->
                        for (j in 0 until results.length()) {
                            val r = results.optJSONObject(j) ?: continue
                            addSource(sources, r.optString("url"), r.optString("title"))
                        }
                    }
                }
            }

            if (res.optString("stop_reason") != "pause_turn") {
                return Reply(tidy(text.toString()), sources.values.take(6), model)
            }
            messages.put(JSONObject().put("role", "assistant").put("content", content))
        }
        return Reply(tidy(text.toString()), sources.values.take(6), model)
    }

    private fun addSource(into: MutableMap<String, Source>, url: String, title: String) {
        if (url.startsWith("http") && url !in into) into[url] = Source(url, title.ifBlank { url })
    }

    /** Text arrives in several blocks; join them and clean up the seams. */
    private fun tidy(raw: String): String {
        val t = raw.replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([.,;:!?])"), "$1")
            .trim()
        return t.ifEmpty { "I didn't get an answer back. Try asking again." }
    }

    private fun post(body: JSONObject): JSONObject {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000           // grounded answers can take a while
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", prefs.apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val payload = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ClaudeApiException(code, payload)
            return JSONObject(payload)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Picks a model per question with simple keyword and length rules: instant and free,
 * but a heuristic. Questions about research, analysis, code or math go to Opus;
 * short quick lookups go to Haiku; everything else to Sonnet.
 */
object ModelRouter {
    private val heavy = Regex("(research|analyz|analys|compare|comparison|trade-?off|pros and cons|strateg|derive|prove|debug|algorithm|optimi[sz]e|refactor|architect|implication|step by step|walk me through|explain why|reason through|break down|weigh|nuance|forecast model|evidence|studies)")
    private val codey = Regex("(\\bcode\\b|function|regex|python|javascript|kotlin|typescript|\\bsql\\b|stack trace|error message|compile|syntax|payload|drupal|backdrop)")
    private val mathy = Regex("(\\d+\\s*[+\\-*/^%]\\s*\\d+|calculate|equation|integral|derivative|solve for|probability|permutation|pace|splits?\\b)")
    private val quick = Regex("^(what('| i)?s|what is|who('| i)?s|who is|when|where|how (far|long|much|many|old|tall|fast)|convert|define|spell|translate|remind|hey|hi|hello|thanks|thank you|yes|no|stop|repeat|say that again)\\b")

    fun pick(setting: String, text: String): String = if (setting != "auto") setting else choose(text)

    fun choose(text: String): String {
        val t = text.lowercase(Locale.US).trim()
        val words = t.split(Regex("\\s+")).count { it.isNotBlank() }
        return when {
            heavy.containsMatchIn(t) || codey.containsMatchIn(t) || mathy.containsMatchIn(t) || words > 45 -> ClaudeClient.OPUS
            quick.containsMatchIn(t) && words <= 12 -> ClaudeClient.HAIKU
            else -> ClaudeClient.SONNET
        }
    }

    fun shortName(model: String): String = when {
        "haiku" in model -> "Haiku"
        "opus" in model -> "Opus"
        "sonnet" in model -> "Sonnet"
        else -> model
    }
}

/** Reads the spoken confidence cue ("High confidence — …") off the front of a reply. */
object Confidence {
    private val cue = Regex("^\\s*(high|moderate|low)\\s+confidence\\b\\s*[—–\\-:,.]*\\s*", RegexOption.IGNORE_CASE)

    fun of(text: String): String? = cue.find(text)?.groupValues?.get(1)?.lowercase(Locale.US)

    fun strip(text: String): String = cue.replaceFirst(text, "")
}
