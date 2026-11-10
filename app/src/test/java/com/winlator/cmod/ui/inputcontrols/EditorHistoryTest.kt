package com.winlator.cmod.ui.inputcontrols

import android.graphics.Bitmap
import android.graphics.Canvas
import com.winlator.cmod.inputcontrols.Binding
import com.winlator.cmod.inputcontrols.ControlElement
import com.winlator.cmod.inputcontrols.ControlsProfile
import com.winlator.cmod.widget.InputControlsView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EditorHistoryTest {
    private lateinit var canvas: InputControlsView
    private lateinit var profile: ControlsProfile
    private lateinit var history: EditorHistory
    private lateinit var original: ControlElement

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        canvas = InputControlsView(context)
        canvas.setEditMode(true)
        canvas.layout(0, 0, 1000, 600)
        profile = ControlsProfile(context, 7654).apply { name = "history-test" }
        original = ControlElement(canvas).apply {
            type = ControlElement.Type.RANGE_BUTTON
            range = ControlElement.Range.FROM_0_TO_9
            scale = 1.25f
            setX(500); setY(300)
            opacity = .65f
            customColor = 0xff123456.toInt()
            setBindingAt(0, Binding.KEY_A)
            setBindingAt(7, Binding.KEY_B)
        }
        profile.addElement(original)
        canvas.profile = profile
        // Establish the grid/dimensions just as the editor's first draw does.
        canvas.draw(Canvas(Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)))
        canvas.selectEditorElement(original)
        history = EditorHistory(profile, canvas)
    }

    @Test fun duplicatePreservesBindingsAndKeepsOriginalIndependent() {
        assertTrue(history.duplicate())
        assertEquals(2, profile.elements.size)
        val copy = profile.elements.last()
        assertNotSame(original, copy)
        assertEquals(Binding.KEY_A, copy.getBindingAt(0))
        assertEquals(Binding.KEY_B, copy.getBindingAt(7))
        assertEquals(original.scale, copy.scale, 0f)
        assertEquals(original.customColor, copy.customColor)
        copy.setBindingAt(0, Binding.NONE)
        assertEquals(Binding.KEY_A, original.getBindingAt(0))
        assertTrue(history.undo())
        assertEquals(1, profile.elements.size)
        assertEquals(Binding.KEY_B, profile.elements.first().getBindingAt(7))
    }

    @Test fun undoRestoresEditsAndDeletionWithoutChangingProfileIdentity() {
        history.begin()
        original.scale = 1.8f
        profile.themeColor = 0xff654321.toInt()
        history.commit()
        history.begin()
        canvas.removeElement()
        history.commit()
        assertTrue(profile.elements.isEmpty())
        assertTrue(history.undo())
        assertEquals(1.8f, profile.elements.first().scale, 0f)
        assertTrue(history.undo())
        assertEquals(1.25f, profile.elements.first().scale, 0f)
        assertEquals(0, profile.themeColor)
        assertEquals(7654, profile.id)
        assertFalse(history.undo())
    }
}
