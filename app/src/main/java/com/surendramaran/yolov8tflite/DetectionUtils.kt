package com.surendramaran.yolov8tflite

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
        "floor" to 1.1f,
        "center" to 1.0f,
        "left" to 0.7f,
        "right" to 0.7f
    )



    // New ground detection logic
    fun isOnGround(box: BoundingBox): Boolean {
        val isBottomInFloorZone = box.y2 > 0.85f    // More strict bottom position
        val isCompactObject = box.h < 0.25f         // Max 25% of screen height
        val isWideObject = box.w > 0.4f             // Min 40% of screen width

        return when (box.clsName) {
            "chair", "potted plant" -> isBottomInFloorZone && isCompactObject
            "person" -> false  // Never classify people as floor objects
            else -> isBottomInFloorZone && (isCompactObject || isWideObject)
        }
    }

    // Updated position weighting
    fun getPositionWeight(box: BoundingBox): Float {
        return when {
            isOnGround(box) -> positionWeights["floor"]!!
            box.cx < 0.35f -> positionWeights["left"]!! * elevationFactor(box)
            box.cx > 0.65f -> positionWeights["right"]!! * elevationFactor(box)
            else -> positionWeights["center"]!! * elevationFactor(box)
        }
    }

    private fun elevationFactor(box: BoundingBox): Float {
        val verticalPosition = 1 - (box.y1 + box.h/2) // Center Y coordinate
        return 0.8f + (verticalPosition * 0.4f) // 0.8-1.2 range based on height
    }

    // Improved priority calculation
    fun calculatePriorityScore(box: BoundingBox): Float {
        val normalizedArea = box.w * box.h
        val classWeight = 1 - (classThresholds[box.clsName] ?: classThresholds["default"]!!)
        val positionWeight = getPositionWeight(box)
        val elevationWeight = 1 + (box.y1 * 0.5f) // Higher objects get slight boost

        return (normalizedArea * 2.0f) +
                (classWeight * 2.0f) +
                (positionWeight * 1.5f) +
                (elevationWeight * 0.5f)
    }



    fun getDisplayClassName(originalName: String): String {
        return if (classThresholds.containsKey(originalName)) originalName else "obstacle"
    }

    fun generateAlertMessage(box: BoundingBox): String {
        val displayName = getDisplayClassName(box.clsName).replace("_", " ")
        val position = getPositionDescription(box)
        return "$displayName $position"
    }


    fun getPositionDescription(box: BoundingBox): String {
        return when {
            isOnGround(box) -> "on the floor ${getHorizontalPosition(box)}"
            else -> "${getVerticalPosition(box)} ${getHorizontalPosition(box)}"
        }
    }

    private fun getHorizontalPosition(box: BoundingBox): String {
        return when {
            box.cx < 0.35f -> "to your left"
            box.cx > 0.65f -> "to your right"
            else -> "ahead"
        }
    }

    private fun getVerticalPosition(box: BoundingBox): String {
        val centerY = box.y1 + box.h/2
        return when {
            centerY < 0.3f -> "high up"
            centerY > 0.7f -> "low down"
            else -> ""
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
