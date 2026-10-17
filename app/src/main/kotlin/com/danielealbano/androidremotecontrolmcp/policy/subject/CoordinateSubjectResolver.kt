package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.policy.engine.OwnPackageName
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ActionExecutorImpl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

/**
 * Coordinate gestures: hit-test of tap/long_press/double_tap (x, y), pinch (centre and both finger start points),
 * custom_gesture (first point of every path) — all classified ACTION_BUTTON; swipe (start point, package-only
 * ACTION_BUTTON: slide-to-pay) and scroll (screen center, package-only NAVIGATE).
 */
class CoordinateSubjectResolver
    @Inject
    constructor(
        private val windowSource: PolicyWindowSource,
        private val hitTester: WindowHitTester,
        @param:OwnPackageName private val ownPackageName: String,
    ) : PolicySubjectResolver {
        override suspend fun resolve(
            policyClass: ToolPolicyClass,
            request: SubjectRequest,
        ): SubjectResolution {
            val gesture = gestureOf(policyClass, request.arguments)
            val result = windowSource.readWindows()
            return if (result.degraded) {
                SubjectSupport.unattributed(gesture.style.actionType, request.profiledPackages)
            } else {
                val touched = gesture.points.flatMap { hitTester.hitTest(result.windows, it.x, it.y) }
                SubjectSupport.touchSubject(
                    result.windows,
                    touched,
                    gesture.style,
                    request.profiledPackages,
                    ownPackageName,
                )
            }
        }

        private fun gestureOf(
            policyClass: ToolPolicyClass,
            arguments: JsonObject?,
        ): Gesture =
            when (policyClass) {
                ToolPolicyClass.POINT_GESTURE -> {
                    Gesture(listOf(pointOf(arguments, PARAM_X, PARAM_Y)), TouchStyle.CLASSIFIED_ACTION)
                }

                ToolPolicyClass.PINCH -> {
                    val center = pointOf(arguments, PARAM_CENTER_X, PARAM_CENTER_Y)
                    Gesture(pinchPoints(center), TouchStyle.CLASSIFIED_ACTION)
                }

                ToolPolicyClass.CUSTOM_GESTURE -> {
                    Gesture(pathStarts(arguments), TouchStyle.CLASSIFIED_ACTION)
                }

                ToolPolicyClass.SWIPE -> {
                    Gesture(listOf(pointOf(arguments, PARAM_X1, PARAM_Y1)), TouchStyle.FIXED_ACTION)
                }

                ToolPolicyClass.SCREEN_SCROLL -> {
                    Gesture(listOf(screenCenter()), TouchStyle.NAVIGATION)
                }

                else -> {
                    error("Unsupported policy class for CoordinateSubjectResolver: $policyClass")
                }
            }

        private fun pointOf(
            arguments: JsonObject?,
            xName: String,
            yName: String,
        ): ScreenPoint =
            ScreenPoint(
                McpToolUtils.requireFloat(arguments, xName),
                McpToolUtils.requireFloat(arguments, yName),
            )

        /** The centre plus both finger start points, as `ActionExecutorImpl.pinch` places them. */
        private fun pinchPoints(center: ScreenPoint): List<ScreenPoint> {
            val screen = windowSource.screenInfo()
            val width = screen.width.toFloat()
            val fingerY = center.y.coerceIn(0f, screen.height.toFloat())
            val offset = ActionExecutorImpl.PINCH_BASE_DISTANCE
            return listOf(
                center,
                ScreenPoint((center.x - offset).coerceIn(0f, width), fingerY),
                ScreenPoint((center.x + offset).coerceIn(0f, width), fingerY),
            )
        }

        private fun screenCenter(): ScreenPoint {
            val screen = windowSource.screenInfo()
            return ScreenPoint(screen.width / CENTER_DIVISOR, screen.height / CENTER_DIVISOR)
        }

        private fun pathStarts(arguments: JsonObject?): List<ScreenPoint> =
            pathsOf(arguments).mapIndexed { index, path -> firstPointOf(path, index) }

        private fun pathsOf(arguments: JsonObject?): JsonArray {
            val element =
                arguments?.get(PARAM_PATHS)
                    ?: throw McpToolException.InvalidParams("Missing required parameter: '$PARAM_PATHS'")
            val paths =
                element as? JsonArray
                    ?: throw McpToolException.InvalidParams("Parameter '$PARAM_PATHS' must be an array")
            requirePathCount(paths.size)
            return paths
        }

        private fun requirePathCount(count: Int) {
            if (count == 0) throw McpToolException.InvalidParams("Parameter '$PARAM_PATHS' must not be empty")
            if (count > MAX_PATHS) {
                throw McpToolException.InvalidParams("Too many paths (max $MAX_PATHS), got: $count")
            }
        }

        private fun firstPointOf(
            path: JsonElement,
            index: Int,
        ): ScreenPoint {
            val points = path as? JsonArray
            val first =
                points?.takeIf { it.size >= MIN_POINTS_PER_PATH }?.first() as? JsonObject
                    ?: throw McpToolException.InvalidParams(pathError(points, index))
            return pointOf(first, PARAM_X, PARAM_Y)
        }

        private fun pathError(
            points: JsonArray?,
            index: Int,
        ): String =
            when {
                points == null -> {
                    "Path at index $index must be an array of point objects"
                }

                points.size < MIN_POINTS_PER_PATH -> {
                    "Path at index $index must have at least $MIN_POINTS_PER_PATH points, has ${points.size}"
                }

                else -> {
                    "Point at path[$index][0] must be an object with x, y, time"
                }
            }

        private data class ScreenPoint(
            val x: Float,
            val y: Float,
        )

        private data class Gesture(
            val points: List<ScreenPoint>,
            val style: TouchStyle,
        )

        companion object {
            private const val PARAM_X = "x"
            private const val PARAM_Y = "y"
            private const val PARAM_X1 = "x1"
            private const val PARAM_Y1 = "y1"
            private const val PARAM_CENTER_X = "center_x"
            private const val PARAM_CENTER_Y = "center_y"
            private const val PARAM_PATHS = "paths"
            private const val MAX_PATHS = 10
            private const val MIN_POINTS_PER_PATH = 2
            private const val CENTER_DIVISOR = 2f
        }
    }
