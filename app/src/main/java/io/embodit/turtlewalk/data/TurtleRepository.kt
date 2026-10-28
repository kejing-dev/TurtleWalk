package io.embodit.turtlewalk.data

import android.content.Context
import io.embodit.turtlewalk.domain.model.LanguagePref
import io.embodit.turtlewalk.domain.model.LessonRecord
import io.embodit.turtlewalk.domain.model.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app remembers between launches (design `persistence`): language, last field size, the
 * coachmarks seen, and per lesson "drawn right", "walked", "turned", the last program and the last walk
 * verdict. The field's place in the room is not stored. Local only, no account, no network.
 */
interface TurtleRepository {
    suspend fun load(): Settings

    suspend fun save(settings: Settings)
}

/** SharedPreferences-backed store; one JSON document, written on the IO dispatcher. */
class PrefsTurtleRepository(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) : TurtleRepository {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun load(): Settings =
        withContext(io) {
            val text = prefs.getString(KEY, null) ?: return@withContext Settings()
            runCatching { SettingsJson.decode(text) }.getOrDefault(Settings())
        }

    override suspend fun save(settings: Settings) {
        withContext(io) { prefs.edit().putString(KEY, SettingsJson.encode(settings)).commit() }
    }

    private companion object {
        const val FILE = "turtle_walk"
        const val KEY = "settings_v1"
    }
}

object SettingsJson {
    fun encode(s: Settings): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("language", s.language.tag)
        root.put("fieldM", s.fieldM.toDouble())
        root.put("lastLesson", s.lastLesson)
        root.put("coachSeen", JSONArray().apply { s.coachSeen.sorted().forEach { put(it) } })
        root.put(
            "lessons",
            JSONObject().apply {
                for ((n, r) in s.records) {
                    put(
                        n.toString(),
                        JSONObject()
                            .put("drawn", r.drawnRight)
                            .put("walked", r.walked)
                            .put("turned", r.turned)
                            .put("program", r.program)
                            .put("walkVerdict", r.lastWalkVerdict ?: JSONObject.NULL),
                    )
                }
            },
        )
        return root.toString()
    }

    fun decode(text: String): Settings {
        val root = JSONObject(text)
        val records = HashMap<Int, LessonRecord>()
        root.optJSONObject("lessons")?.let { o ->
            for (k in o.keys()) {
                val r = o.optJSONObject(k) ?: continue
                val n = k.toIntOrNull() ?: continue
                records[n] =
                    LessonRecord(
                        drawnRight = r.optBoolean("drawn"),
                        walked = r.optBoolean("walked"),
                        turned = r.optBoolean("turned"),
                        program = r.optString("program", ""),
                        lastWalkVerdict = if (r.isNull("walkVerdict")) null else r.optString("walkVerdict"),
                    )
            }
        }
        val seen = HashSet<Int>()
        root.optJSONArray("coachSeen")?.let { a -> for (i in 0 until a.length()) seen += a.optInt(i) }
        return Settings(
            language = LanguagePref.fromTag(root.optString("language", "system")),
            fieldM = root.optDouble("fieldM", 2.0).toFloat().coerceIn(1f, 2f),
            lastLesson = root.optInt("lastLesson", 1).coerceIn(1, 10),
            coachSeen = seen,
            records = records,
        )
    }
}
