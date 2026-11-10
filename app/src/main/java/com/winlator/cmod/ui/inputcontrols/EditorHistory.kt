package com.winlator.cmod.ui.inputcontrols

import com.winlator.cmod.inputcontrols.*
import com.winlator.cmod.widget.InputControlsView
import org.json.JSONObject

internal class EditorHistory(private val profile: ControlsProfile, private val canvas: InputControlsView) {
    private data class Snapshot(val color: Int, val elements: List<String>)
    private val undo = ArrayDeque<Snapshot>()
    private var baseline: Snapshot? = null
    val canUndo get() = undo.isNotEmpty()
    private fun snapshot() = Snapshot(profile.themeColor, profile.elements.map { it.toJSONObject().toString() })
    fun begin() { if (canvas.maxWidth <= 0 || canvas.maxHeight <= 0 || !profile.isElementsLoaded) return; if (baseline == null) baseline = snapshot() }
    fun commit() {
        if (canvas.maxWidth <= 0 || canvas.maxHeight <= 0 || !profile.isElementsLoaded) return
        val next = snapshot()
        baseline?.let { if (it != next) { undo.addLast(it); if (undo.size > 30) undo.removeFirst() } }
        baseline = next
    }
    fun undo(): Boolean {
        if (undo.isEmpty()) return false
        val saved = undo.last()
        val elements = runCatching { saved.elements.map { decode(JSONObject(it)) } }.getOrNull() ?: return false
        undo.removeLast()
        profile.elements.toList().forEach { profile.removeElement(it) }
        elements.forEach { profile.addElement(it) }
        profile.themeColor = saved.color
        canvas.selectEditorElement(null)
        profile.save(); canvas.invalidate(); baseline = snapshot()
        return true
    }
    fun duplicate(): Boolean {
        val source = canvas.selectedElement ?: return false
        begin()
        val copy = runCatching { decode(source.toJSONObject()) }.getOrNull() ?: return false
        copy.setX( (source.x.toInt() + 24).coerceIn(0, canvas.maxWidth))
        copy.setY( (source.y.toInt() + 24).coerceIn(0, canvas.maxHeight))
        profile.addElement(copy); canvas.selectEditorElement(copy)
        profile.save(); canvas.invalidate(); commit()
        return true
    }
    private fun decode(data: JSONObject): ControlElement = ControlElement(canvas).apply {
        type = ControlElement.Type.valueOf(data.getString("type"))
        shape = ControlElement.Shape.valueOf(data.getString("shape"))
        isToggleSwitch = data.optBoolean("toggleSwitch")
        setX((data.getDouble("x") * canvas.maxWidth).toInt())
        setY((data.getDouble("y") * canvas.maxHeight).toInt())
        scale = data.getDouble("scale").toFloat()
        deadZone = data.optDouble("deadZone", 0.15).toFloat()
        areaWidthRatio = data.optDouble("areaWidthRatio", 0.38).toFloat()
        areaHeightRatio = data.optDouble("areaHeightRatio", 0.64).toFloat()
        stickRadiusRatio = data.optDouble("stickRadiusRatio", 0.14).toFloat()
        text = data.optString("text")
        setIconId(data.optInt("iconId"))
        if (data.has("range")) range = ControlElement.Range.valueOf(data.getString("range"))
        orientation = data.optInt("orientation").toByte()
        opacity = data.optDouble("opacity", 1.0).toFloat()
        customColor = data.optInt("customColor")
        isMouseMoveMode = data.optBoolean("mouseMoveMode")
        if (data.has("customIconPath")) customIconPath = data.getString("customIconPath")
        val bindings = data.getJSONArray("bindings")
        for (i in 0 until bindings.length()) setBindingAt(i, Binding.fromString(bindings.getString(i)))
    }
}
