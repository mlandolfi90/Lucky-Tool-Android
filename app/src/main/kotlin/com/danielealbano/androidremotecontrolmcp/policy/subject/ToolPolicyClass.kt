package com.danielealbano.androidremotecontrolmcp.policy.subject

/** Which [PolicySubjectResolver] handles a [ToolPolicyClass]; [NONE] means the call targets no app. */
enum class ResolverKind {
    NONE,
    WINDOW,
    NODE,
    COORDINATE,
    TEXT,
    NOTIFICATION,
    LAUNCH,
}

/** How the target app(s) and the action type of one tool's calls are derived. */
enum class ToolPolicyClass(
    val kind: ResolverKind,
) {
    NOT_APP_SCOPED(ResolverKind.NONE),
    EXEMPT_EXIT(ResolverKind.NONE),
    SCREEN_READ(ResolverKind.WINDOW),
    PRESS_BACK(ResolverKind.WINDOW),
    CLIPBOARD_WRITE(ResolverKind.WINDOW),
    NODE_DETAILS_READ(ResolverKind.NODE),
    NODE_CLICK(ResolverKind.NODE),
    NODE_LONG_CLICK(ResolverKind.NODE),
    NODE_TAP(ResolverKind.NODE),
    NODE_SCROLL(ResolverKind.NODE),
    POINT_GESTURE(ResolverKind.COORDINATE),
    PINCH(ResolverKind.COORDINATE),
    CUSTOM_GESTURE(ResolverKind.COORDINATE),
    SWIPE(ResolverKind.COORDINATE),
    SCREEN_SCROLL(ResolverKind.COORDINATE),
    TEXT_EDIT(ResolverKind.TEXT),
    PRESS_KEY(ResolverKind.TEXT),
    NOTIFICATION_LIST(ResolverKind.NOTIFICATION),
    NOTIFICATION_OPEN(ResolverKind.NOTIFICATION),
    NOTIFICATION_CONTROL(ResolverKind.NOTIFICATION),
    NOTIFICATION_ACTION(ResolverKind.NOTIFICATION),
    NOTIFICATION_REPLY(ResolverKind.NOTIFICATION),
    APP_LAUNCH(ResolverKind.LAUNCH),
    SEND_INTENT(ResolverKind.LAUNCH),
    OPEN_URI(ResolverKind.LAUNCH),
}
