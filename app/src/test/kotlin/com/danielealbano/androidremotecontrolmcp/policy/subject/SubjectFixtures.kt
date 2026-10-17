package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import kotlinx.serialization.json.JsonObject

/** Builders shared by the subject-resolution tests. */
internal object SubjectFixtures {
    const val BANK = "com.example.bank"
    const val SHOP = "com.example.shop"
    const val KEYBOARD = "com.example.keyboard"
    const val SYSTEM_UI = "com.android.systemui"

    /** Stands in for the injected `@OwnPackageName` in every test that builds a hit tester or a resolver. */
    const val OWN_PACKAGE = "com.danielealbano.androidremotecontrolmcp.gms.debug"
    const val SCREEN_WIDTH = 1080
    const val SCREEN_HEIGHT = 2400
    const val BUTTON_CLASS = "android.widget.Button"
    const val FIELD_CLASS = "android.widget.EditText"
    private const val SCREEN_DPI = 420

    val FULL_SCREEN = BoundsData(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT)
    val SCREEN = ScreenInfo(SCREEN_WIDTH, SCREEN_HEIGHT, SCREEN_DPI, ScreenInfo.ORIENTATION_PORTRAIT)

    fun container(
        id: String,
        bounds: BoundsData,
        children: List<AccessibilityNodeData>,
    ): AccessibilityNodeData = AccessibilityNodeData(id = id, bounds = bounds, visible = true, children = children)

    fun button(
        id: String,
        bounds: BoundsData,
        text: String?,
        resourceId: String? = null,
    ): AccessibilityNodeData =
        AccessibilityNodeData(
            id = id,
            className = BUTTON_CLASS,
            text = text,
            resourceId = resourceId,
            bounds = bounds,
            clickable = true,
            enabled = true,
            visible = true,
        )

    fun field(
        id: String,
        bounds: BoundsData,
        text: String?,
        hint: String?,
        isPassword: Boolean = false,
    ): AccessibilityNodeData =
        AccessibilityNodeData(
            id = id,
            className = FIELD_CLASS,
            text = text,
            hintText = hint,
            bounds = bounds,
            clickable = true,
            editable = true,
            isPassword = isPassword,
            enabled = true,
            visible = true,
        )

    /** windowId = layer, so every test window has a distinct id when layers differ. */
    fun window(
        packageName: String?,
        type: String,
        root: AccessibilityNodeData,
        focused: Boolean,
        layer: Int,
    ): WindowData =
        WindowData(
            windowId = layer,
            windowType = type,
            packageName = packageName,
            layer = layer,
            focused = focused,
            tree = root,
        )

    fun read(vararg entries: WindowData): MultiWindowResult = MultiWindowResult(entries.toList())

    fun request(
        toolName: String,
        arguments: JsonObject,
        profiledPackages: Set<String> = emptySet(),
    ): SubjectRequest = SubjectRequest(toolName, arguments, profiledPackages)
}

/** In-memory [PolicyWindowSource]; [readCount] proves argument validation happens before any read. */
internal class FakePolicyWindowSource(
    var windows: MultiWindowResult = MultiWindowResult(emptyList()),
    var focusedField: FocusedField? = null,
    var screen: ScreenInfo = SubjectFixtures.SCREEN,
) : PolicyWindowSource {
    var readCount = 0
        private set

    override fun readWindows(): MultiWindowResult {
        readCount++
        return windows
    }

    override fun focusedEditableField(): FocusedField? = focusedField

    override fun screenInfo(): ScreenInfo = screen
}
