package com.winlator.cmod.components

import com.winlator.cmod.components.ComponentDownloads as Downloader
import org.json.JSONArray
import org.json.JSONObject

/** One install step from a Bottles-format dependency manifest (action + its raw fields). */
class ComponentStep(val action: String, val obj: JSONObject) {
    fun str(key: String, def: String = ""): String = obj.optString(key, def)
    fun bundle(): JSONArray? = obj.optJSONArray("bundle")
}

data class Component(
    val name: String,
    val description: String,
    val provider: String,
    val status: String,            // ready | needs-upstream | pending-manual
    val dependencies: List<String>,
    val steps: List<ComponentStep>,
    /** A recording of what the component's own installer leaves in a prefix (component-snapshots release), or "". */
    val snapshot: String = "",
) {
    val ready: Boolean get() = status == "ready"
}

/**
 * Reads the system-library component catalog (components.json) hosted on winlator-contents.
 * Same source BannerHub's Component Manager uses.
 */
object ComponentCatalog {
    const val URL = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/components.json"

    /** The last catalog [load] read, by name: what an install looks dependencies up in. */
    @Volatile
    var byName: Map<String, Component> = emptyMap()
        private set

    fun load(): List<Component> {
        val json = Downloader.downloadString(URL) ?: return emptyList()
        val arr = JSONObject(json).optJSONArray("components") ?: return emptyList()
        val out = ArrayList<Component>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val stepsArr = c.optJSONArray("steps") ?: JSONArray()
            val steps = ArrayList<ComponentStep>(stepsArr.length())
            for (j in 0 until stepsArr.length()) {
                val s = stepsArr.getJSONObject(j)
                steps.add(ComponentStep(s.optString("action", ""), s))
            }
            val deps = ArrayList<String>()
            c.optJSONArray("dependencies")?.let { d -> for (k in 0 until d.length()) deps.add(d.optString(k)) }
            out.add(
                Component(
                    name = c.optString("name"),
                    description = c.optString("description"),
                    provider = c.optString("provider"),
                    status = c.optString("status", "ready"),
                    dependencies = deps,
                    steps = steps,
                    snapshot = c.optString("snapshot", ""),
                )
            )
        }
        if (out.isNotEmpty()) byName = out.associateBy { it.name }
        return out
    }
}
