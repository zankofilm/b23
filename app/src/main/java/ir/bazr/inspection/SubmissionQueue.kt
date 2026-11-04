package ir.bazr.inspection

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Explicit user-controlled outbox; retry never silently submits without an unlocked session. */
class SubmissionQueue(private val store: SecureStore) {
    private fun index(): JSONArray = runCatching { JSONArray(store.load("outbox_index") ?: "[]") }.getOrDefault(JSONArray())
    private fun recordKey(key: String) = "outbox:$key"
    fun get(key: String): JSONObject? = store.load(recordKey(key))?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun entries(): List<JSONObject> {
        val keys = index()
        return (0 until keys.length()).mapNotNull { get(keys.optString(it)) }
    }
    private fun saveRecord(key: String, item: JSONObject) { store.save(recordKey(key), item.toString()) }
    fun enqueue(mission: JSONObject, draft: InspectionDraft): JSONObject {
        val key = draft.key
        get(key)?.let { return it } // One stable pending operation per mission.
        val record = JSONObject()
            .put("mission_key", key)
            .put("mission", JSONObject(mission.toString()))
            .put("organization", mission.optString("orgName", "دستگاه اجرایی"))
            .put("queued_at", Instant.now().toString())
            .put("finished_at", Instant.now().toString())
            .put("state", "pending")
            .put("error", "")
            .put("uploaded", JSONObject())
        saveRecord(key, record)
        val index = index()
        if ((0 until index.length()).none { index.optString(it) == key }) {
            index.put(key); store.save("outbox_index", index.toString())
        }
        return record
    }
    fun setState(key: String, state: String, error: String = "") {
        get(key)?.let { saveRecord(key, it.put("state", state).put("error", error.take(220))) }
    }
    fun rememberUpload(key: String, question: String, fileId: Int) {
        get(key)?.let { item ->
            val uploads = item.optJSONObject("uploaded") ?: JSONObject()
            uploads.put(question, fileId)
            saveRecord(key, item.put("uploaded", uploads))
        }
    }
    fun remove(key: String) {
        store.clear(recordKey(key))
        val next = JSONArray()
        val previous = index()
        for (i in 0 until previous.length()) if (previous.optString(i) != key) next.put(previous.optString(i))
        store.save("outbox_index", next.toString())
    }
}
