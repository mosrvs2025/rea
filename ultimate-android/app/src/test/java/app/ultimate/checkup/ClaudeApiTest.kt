package app.ultimate.checkup

import app.ultimate.checkup.ai.AiResult
import app.ultimate.checkup.ai.ClaudeApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeApiTest {
    @Test fun requestCarriesTheReportTheVersionAndTheFallback() {
        val body = JSONObject(ClaudeApi.body("claude-opus-5-5", "REPORT", "What first?"))
        assertEquals("claude-opus-5-5", body.getString("model"))
        assertEquals(16_000, body.getInt("max_tokens"))
        assertEquals("medium", body.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body.getString("fallbacks"))
        assertFalse("sampling fields are rejected on 2026-10-21", body.has("temperature") || body.has("top_p") || body.has("top_k"))
        val msg = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        assertEquals("What first?\n\n---\n\nREPORT", msg.getString("content"))
        val h = ClaudeApi.headers("sk-ant-x", "claude-opus-5-5")
        assertEquals("2026-10-21", h["anthropic-version"])
        assertEquals("sk-ant-x", h["x-api-key"])
        assertEquals(ClaudeApi.FALLBACK_BETA, h["anthropic-beta"])
    }

    @Test fun haikuGetsNoServerSideFallback() {
        assertFalse(JSONObject(ClaudeApi.body("claude-haiku-5-5", "r", "q")).has("fallbacks"))
        assertNull(ClaudeApi.headers("k", "claude-haiku-5-5")["anthropic-beta"])
    }

    @Test fun answersJoinTextBlocksAndSkipOthers() {
        val reply = """{"model":"claude-opus-5-5","stop_reason":"end_turn","content":[{"type":"thinking","thinking":""},{"type":"text","text":"Fix the lock."},{"type":"text","text":"Then update."}],"usage":{"input_tokens":1200,"output_tokens":80}}"""
        val r = ClaudeApi.parse(200, reply, "claude-opus-5-5") as AiResult.Answer
        assertEquals("Fix the lock.\n\nThen update.", r.text)
        assertEquals(1200, r.inputTokens); assertEquals(80, r.outputTokens)
    }

    @Test fun refusalIsCheckedBeforeContent() {
        val reply = """{"stop_reason":"refusal","stop_details":{"type":"refusal","category":"cyber","explanation":"x"},"content":[{"type":"text","text":"partial"}]}"""
        assertEquals(AiResult.Refused("cyber"), ClaudeApi.parse(200, reply, "m"))
        assertEquals(AiResult.Refused(null), ClaudeApi.parse(200, """{"stop_reason":"refusal","stop_details":{"category":null},"content":[]}""", "m"))
    }

    @Test fun truncatedAnswersSaySo() {
        val r = ClaudeApi.parse(200, """{"stop_reason":"max_tokens","content":[{"type":"text","text":"Long"}]}""", "m") as AiResult.Answer
        assertTrue(r.text.endsWith("(The answer was cut off at its length limit.)"))
    }

    @Test fun errorsBecomePlainMessages() {
        val bad = ClaudeApi.parse(401, """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""", "m") as AiResult.Failed
        assertTrue(bad.badKey)
        assertTrue((ClaudeApi.parse(429, "", "m") as AiResult.Failed).message.startsWith("Rate limited"))
        assertTrue((ClaudeApi.parse(529, "{}", "m") as AiResult.Failed).message.contains("busy"))
        val other = ClaudeApi.parse(400, """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens too large"}}""", "m") as AiResult.Failed
        assertEquals("Anthropic returned an error (400): max_tokens too large", other.message)
        assertTrue((ClaudeApi.parse(200, "not json", "m") as AiResult.Failed).message.contains("could not read"))
    }
}
