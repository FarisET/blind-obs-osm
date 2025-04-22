package com.surendramaran.yolov8tflite

import kotlin.math.sqrt

// DetectionUtils.kt
object DetectionUtils {
    // Shared class thresholds
    val classThresholds = mapOf(
        "person" to 0.03f,    // 3% of screen area
        "bicycle" to 0.04f,
        "car" to 0.05f,
        "motorcycle" to 0.04f,
        "bus" to 0.06f,
        "truck" to 0.07f,

        // Medium priority
        "traffic light" to 0.02f,
        "fire hydrant" to 0.015f,
        "stop sign" to 0.01f,
        "bench" to 0.04f,
        "dog" to 0.02f,
        "cat" to 0.015f,

        // Low priority/rare obstacles (higher thresholds)
        "chair" to 0.05f,
        "potted plant" to 0.04f,

        // Default threshold for unlisted classes
        "default" to 0.05f

    )



    // Shared position weights
    val positionWeights = mapOf(
        "floor" to 1.0f,
        "center" to 0.9f,
        "left" to 0.7f,
        "right" to 0.7f
    )

    fun calculatePriorityScore(box: BoundingBox): Float {
        val normalizedArea = box.w * box.h
        val classWeight = 1 - (classThresholds[box.clsName] ?: classThresholds["default"]!!)
        val positionWeight = getPositionWeight(box)
        return (normalizedArea * 2.5f) + (classWeight * 1.8f) + (positionWeight * 2.0f)
    }

    fun getPositionWeight(box: BoundingBox): Float {
        val (xCenter, yBottom) = box.cx to box.y2
        return when {
            yBottom > 0.8f -> positionWeights["floor"]!!
            xCenter in 0.3f..0.7f -> positionWeights["center"]!!
            xCenter < 0.3f -> positionWeights["left"]!!
            else -> positionWeights["right"]!!
        }
    }

    fun getDisplayClassName(originalName: String): String {
        return if (classThresholds.containsKey(originalName)) originalName else "obstacle"
    }

    fun estimateDistance(box: BoundingBox): Float {
        val normalizedArea = box.w * box.h
        if (normalizedArea <= 0f) return Float.MAX_VALUE

        val threshold = classThresholds[box.clsName] ?: classThresholds["default"]!!
        val ratio = threshold / normalizedArea
        val distance = sqrt(ratio.toDouble()).toFloat() * 2.0f // Heuristic adjustment

        return distance.coerceIn(0.5f, 20.0f) // Clamp to reasonable values
    }

    fun generateAlertMessage(box: BoundingBox): String {
        val displayName = getDisplayClassName(box.clsName).replace("_", " ")
        val position = getPositionDescription(box)
        val distance = "%.1f meters".format(estimateDistance(box))
        return "$displayName $position, $distance"
    }

    fun getPositionDescription(box: BoundingBox): String {
        return when {
            box.y2 > 0.8f -> "ahead on the floor"
            box.cx < 0.3f -> "on your left"
            box.cx > 0.7f -> "on your right"
            else -> "ahead"
        }
    }

    fun filterAndPrioritize(boxes: List<BoundingBox>): List<BoundingBox> {
        val filtered = boxes.filter { box ->
            val normalizedArea = box.w * box.h
            normalizedArea >= (classThresholds[box.clsName] ?: classThresholds["default"]!!)
        }

        return filtered.map { box ->
            Pair(box, calculatePriorityScore(box))
        }.sortedByDescending { it.second }
            .map { it.first }
    }

    // Common alert history management
    fun shouldAlert(alertHistory: MutableMap<String, Long>, box: BoundingBox): Boolean {
        val key = generateHistoryKey(box)
        val currentTime = System.currentTimeMillis()
        return when {
            !alertHistory.containsKey(key) -> true
            currentTime - alertHistory[key]!! > HISTORY_EXPIRATION_MS -> true
            else -> false
        }
    }

    fun generateHistoryKey(box: BoundingBox): String {
        val positionKey = when {
            box.y2 > 0.8f -> "floor"
            box.cx < 0.3f -> "left"
            box.cx > 0.7f -> "right"
            else -> "center"
        }
        return "${box.clsName}_$positionKey"
    }

    fun cleanupExpiredHistory(alertHistory: MutableMap<String, Long>) {
        val currentTime = System.currentTimeMillis()
        alertHistory.entries.removeAll { currentTime - it.value > HISTORY_EXPIRATION_MS }
    }

    const val HISTORY_EXPIRATION_MS = 8000L
}
