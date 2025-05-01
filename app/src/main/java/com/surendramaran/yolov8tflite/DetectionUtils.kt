package com.surendramaran.yolov8tflite

import android.util.Log
import java.util.Locale


// DetectionUtils.kt
object DetectionUtils {
    // --- Configuration ---
    const val TAG = "DetectionUtils"
    const val MAX_RESULTS_TO_DISPLAY = 3 // Show top 3 obstacles visually
    const val MAX_RESULTS_TO_SPEAK = 1   // Speak only the single most important obstacle at a time
    const val HISTORY_EXPIRATION_MS = 6000L // How long to remember an object at a location (ms)
    const val MIN_TTS_INTERVAL_MS = 2500L   // Minimum time between any two TTS alerts (ms)

    // --- Class Thresholds & Relevance (University Campus Focus) ---
    // Threshold: Minimum normalized screen area (width*height) to consider the object.
    // Relevance: Subjective importance score (higher is more important).
    data class ClassInfo(val threshold: Float, val relevance: Float)

    val classThresholds = mapOf(
        // High Relevance - Immediate Hazards / Key Objects
        "person" to ClassInfo(threshold = 0.02f, relevance = 1.0f),   // People are most important
        "bicycle" to ClassInfo(threshold = 0.03f, relevance = 0.9f),  // Common, can move quickly
        "car" to ClassInfo(threshold = 0.04f, relevance = 0.95f), // Significant hazard
        "motorcycle" to ClassInfo(threshold = 0.03f, relevance = 0.9f),
        "bus" to ClassInfo(threshold = 0.05f, relevance = 0.95f),
        "truck" to ClassInfo(threshold = 0.05f, relevance = 0.95f),
        "stop sign" to ClassInfo(threshold = 0.01f, relevance = 0.85f), // Important navigation cue
        "traffic light" to ClassInfo(threshold = 0.015f, relevance = 0.8f), // Important cue

        // Medium Relevance - Potential Obstacles / Common Items
        "bench" to ClassInfo(threshold = 0.03f, relevance = 0.6f),    // Stationary obstacle
        "backpack" to ClassInfo(threshold = 0.01f, relevance = 0.5f), // Might be dropped/unexpected
        "umbrella" to ClassInfo(threshold = 0.01f, relevance = 0.4f), // Less common, but relevant if open/low
        "handbag" to ClassInfo(threshold = 0.01f, relevance = 0.4f),
        "suitcase" to ClassInfo(threshold = 0.03f, relevance = 0.7f), // Definite obstacle
        "dog" to ClassInfo(threshold = 0.015f, relevance = 0.75f), // Can move unpredictably
        "cat" to ClassInfo(threshold = 0.015f, relevance = 0.65f), // Less common hazard than dog

        // Low Relevance - Less Likely Hazards / Background Items (Higher Thresholds)
        "chair" to ClassInfo(threshold = 0.04f, relevance = 0.5f),    // Usually stationary, often expected indoors/cafes
        "potted plant" to ClassInfo(threshold = 0.03f, relevance = 0.4f), // Stationary decoration
        "fire hydrant" to ClassInfo(threshold = 0.015f, relevance = 0.6f), // Fixed obstacle
        "bird" to ClassInfo(threshold = 0.005f, relevance = 0.1f), // Generally not a walking hazard
        "tree" to ClassInfo(threshold = 0.10f, relevance = 0.7f),     // Trees themselves are obstacles if close
        "pole" to ClassInfo(threshold = 0.01f, relevance = 0.8f),     // Thin but important obstacle (lamppost, signpost)
        "bollard" to ClassInfo(threshold = 0.01f, relevance = 0.75f), // Low, fixed obstacle
        "stair" to ClassInfo(threshold = 0.05f, relevance = 0.9f),    // Important hazard/feature
        "door" to ClassInfo(threshold = 0.05f, relevance = 0.6f),     // Context-dependent (open/closed)
        "wall" to ClassInfo(threshold = 0.10f, relevance = 0.5f),     // Usually defines path boundaries

        // Default for anything else detected but not listed (likely less important)
        "default" to ClassInfo(threshold = 0.06f, relevance = 0.3f)
    ).mapKeys { it.key.lowercase(Locale.US) } // Ensure keys are lowercase


    // --- Position & Distance Calculation ---

    /**
     * Determines the horizontal position relative to the center of the screen.
     * Returns "left", "right", or "center".
     */
    private fun getHorizontalRegion(box: BoundingBox): String {
        val centerThresholdLeft = 0.40f // Center zone is between 40% and 60%
        val centerThresholdRight = 0.60f
        return when {
            box.cx < centerThresholdLeft -> "left"
            box.cx > centerThresholdRight -> "right"
            else -> "center"
        }
    }

    /**
     * Estimates the "closeness" based primarily on the bounding box height.
     * Larger height generally means closer. Returns "close", "medium", or "far".
     * Thresholds need tuning based on typical object sizes and camera FOV.
     */
    private fun getCloseness(box: BoundingBox): String {
        val height = box.h
        return when {
            height > 0.60f -> "very close" // Object takes up > 60% of screen height
            height > 0.35f -> "close"      // > 35%
            height > 0.15f -> "medium"     // > 15%
            else -> "far"
        }
    }

    /**
     * Calculates the angle relative to the screen center (0 degrees = straight ahead).
     * Uses atan2 for angle calculation. Range is roughly -60 to +60 degrees assuming
     * typical phone FOV, mapped to clock face.
     * Returns an angle in degrees (approx). Positive is right, negative is left.
     */
    private fun getAngleDegrees(box: BoundingBox): Double {
        // Approximate horizontal field of view (adjust based on device testing)
        val horizontalFOV = 65.0

        // Calculate horizontal position relative to center (-0.5 to +0.5)
        val relativeX = box.cx - 0.5f

        // Estimate angle using tangent (simplified)
        // tan(angle/2) = relativeX / (0.5 / tan(FOV/2)) --- approximation
        // More direct: angle = relativeX * FOV
        return relativeX * horizontalFOV
    }

    /**
     * Converts an angle (-ve left, +ve right) to a clock face position.
     * 12 o'clock is straight ahead.
     */
    private fun getClockPosition(angleDegrees: Double): String {
        // Normalize angle slightly if needed, though direct mapping often works
        // Clock face degrees (approximate): 1h = 30 degrees
        // Thresholds define the boundaries for each clock number
        return when {
            angleDegrees in -7.5..7.5 -> "at 12 o'clock" // Center ±7.5°
            angleDegrees > 7.5 && angleDegrees <= 22.5 -> "at 1 o'clock" // 7.5° to 22.5°
            angleDegrees > 22.5 && angleDegrees <= 37.5 -> "at 2 o'clock" // etc.
            angleDegrees > 37.5 -> "at 3 o'clock" // Anything further right
            angleDegrees < -7.5 && angleDegrees >= -22.5 -> "at 11 o'clock"
            angleDegrees < -22.5 && angleDegrees >= -37.5 -> "at 10 o'clock"
            angleDegrees < -37.5 -> "at 9 o'clock" // Anything further left
            else -> "ahead" // Fallback
        }
    }

    // --- Alert Message Generation ---

    /**
     * Generates the final TTS alert message combining object name, closeness, and clock position.
     */
    fun generateAlertMessage(box: BoundingBox): String {
        // Map internal class name to a user-friendly, speakable name
        val speakableName = getSpeakableClassName(box.clsName)
        val closeness = getCloseness(box)
        val angle = getAngleDegrees(box)
        val clockPosition = getClockPosition(angle)

        // Combine components into a concise message
        // Prioritize closeness for immediate hazards
        return when (closeness) {
            "very close" -> "$speakableName very close $clockPosition"
            "close" -> "$speakableName close $clockPosition"
            else -> "$speakableName $clockPosition" // For medium/far, clock position is enough
        }
    }

    /**
     * Maps internal class names (potentially with underscores) to speakable names.
     */
    private fun getSpeakableClassName(className: String): String {
        return when (val lowerCaseName = className.lowercase(Locale.US)) {
            "stopsign" -> "stop sign" // Common typo fix
            "trafficlight" -> "traffic light"
            "pottedplant" -> "potted plant"
            "firehydrant" -> "fire hydrant"
            // Add more specific mappings if your model uses underscores or abbreviations
            else -> lowerCaseName // Default: just use the mapped name
        }
    }

    // --- Filtering and Prioritization ---

    /**
     * Filters bounding boxes based on class thresholds and calculates a priority score.
     * Returns a list of bounding boxes sorted by priority (highest first).
     */
    fun filterAndPrioritize(boxes: List<BoundingBox>): List<BoundingBox> {
        val consideredBoxes = boxes.mapNotNull { box ->
            val lowerCaseName = box.clsName.lowercase(Locale.US)
            val info = classThresholds[lowerCaseName] ?: classThresholds["default"]!! // Get info or default
            val normalizedArea = box.w * box.h

            // 1. Filter by Area Threshold
            if (normalizedArea < info.threshold) {
                Log.v(TAG, "Filtering out ${box.clsName} - Area $normalizedArea < Threshold ${info.threshold}")
                null // Filter out if too small
            } else {
                // 2. Calculate Priority Score if passing threshold
                val score = calculatePriorityScore(box, info, normalizedArea)
                Log.v(TAG, "Box: ${box.clsName}, Area: ${"%.3f".format(normalizedArea)}, Score: ${"%.2f".format(score)}")
                Pair(box.copy(clsName = lowerCaseName), score) // Return box with score (use lowercase name)
            }
        }
        // 3. Sort by Score (Descending)
        return consideredBoxes.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Calculates a priority score based on relevance, size, position, and closeness.
     */
    private fun calculatePriorityScore(box: BoundingBox, info: ClassInfo, normalizedArea: Float): Float {
        // Weighting factors - adjust these based on testing
        val relevanceWeight = 3.0f
        val areaWeight = 1.5f       // Larger objects are generally more important
        val closenessWeight = 2.5f  // Closer objects are much more important
        val centralityWeight = 1.0f // Objects closer to center path are more important

        // Closeness score (higher for closer objects) - based on height
        val closenessScore = box.h * closenessWeight // Simple height-based closeness

        // Centrality score (higher for objects near center horizontal axis)
        val centrality = 1.0f - (kotlin.math.abs(box.cx - 0.5f) * 2.0f) // 1.0 at center, 0.0 at edges
        val centralityScore = centrality * centralityWeight

        // Final score calculation
        val score = (info.relevance * relevanceWeight) +
                (normalizedArea * areaWeight) +
                closenessScore +
                centralityScore

        return score
    }

    // --- Alert History Management ---

    /**
     * Generates a unique key for the alert history map.
     * Uses Class Name + Horizontal Region + Closeness Region.
     * This prevents spamming for the same object staying in the same relative area.
     */
    fun generateHistoryKey(box: BoundingBox): String {
        val hRegion = getHorizontalRegion(box)
        val cRegion = getCloseness(box)
        // Key uses lowercase name for consistency
        return "${box.clsName.lowercase(Locale.US)}_${hRegion}_$cRegion"
    }

    /**
     * Checks if an alert should be generated based on the history.
     */
    fun shouldAlert(alertHistory: MutableMap<String, Long>, box: BoundingBox): Boolean {
        val key = generateHistoryKey(box)
        val currentTime = System.currentTimeMillis()
        val lastAlertTime = alertHistory[key]

        return if (lastAlertTime == null || (currentTime - lastAlertTime > HISTORY_EXPIRATION_MS)) {
            true // Alert if new or history expired
        } else {
            false // Don't alert if recently alerted for this object/region combo
        }
    }

    /**
     * Updates the timestamp for a given object key in the alert history.
     */
    fun updateAlertHistory(alertHistory: MutableMap<String, Long>, box: BoundingBox) {
        val key = generateHistoryKey(box)
        alertHistory[key] = System.currentTimeMillis()
    }

    /**
     * Removes expired entries from the alert history map.
     */
    fun cleanupExpiredHistory(alertHistory: MutableMap<String, Long>) {
        val currentTime = System.currentTimeMillis()
        val removed = alertHistory.entries.removeAll { currentTime - it.value > HISTORY_EXPIRATION_MS }
        // if (removed) Log.v(TAG, "Cleaned expired alert history")
    }

    /**
     * Maps internal model class names to display names if needed (can be same as speakable).
     * Returns null if the class should be ignored entirely.
     */
    fun getDisplayClassName(originalName: String): String? {
        val lowerCaseName = originalName.lowercase(Locale.US)
        // Return the name if it's in our map, otherwise null to filter it out
        return if (classThresholds.containsKey(lowerCaseName)) lowerCaseName else null
        // Alternatively, if you want to display unknowns as "obstacle":
        // return classInfoMap[lowerCaseName]?.let { lowerCaseName } ?: "obstacle"
    }




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
//    fun calculatePriorityScore(box: BoundingBox): Float {
//        val normalizedArea = box.w * box.h
//        val classWeight = 1 - (classThresholds[box.clsName] ?: classThresholds["default"]!!)
//        val positionWeight = getPositionWeight(box)
//        val elevationWeight = 1 + (box.y1 * 0.5f) // Higher objects get slight boost
//
//        return (normalizedArea * 2.0f) +
//                (classWeight * 2.0f) +
//                (positionWeight * 1.5f) +
//                (elevationWeight * 0.5f)
//    }



//    fun getDisplayClassName(originalName: String): String {
//        return if (classThresholds.containsKey(originalName)) originalName else "obstacle"
//    }

//    fun generateAlertMessage(box: BoundingBox): String {
//        val displayName = getDisplayClassName(box.clsName).replace("_", " ")
//        val position = getPositionDescription(box)
//        return "$displayName $position"
//    }


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


//    fun filterAndPrioritize(boxes: List<BoundingBox>): List<BoundingBox> {
//        val filtered = boxes.filter { box ->
//            val normalizedArea = box.w * box.h
//            normalizedArea >= (classThresholds[box.clsName] ?: classThresholds["default"]!!)
//        }
//
//        return filtered.map { box ->
//            Pair(box, calculatePriorityScore(box))
//        }.sortedByDescending { it.second }
//            .map { it.first }
//    }
//
//    // Common alert history management
//    fun shouldAlert(alertHistory: MutableMap<String, Long>, box: BoundingBox): Boolean {
//        val key = generateHistoryKey(box)
//        val currentTime = System.currentTimeMillis()
//        return when {
//            !alertHistory.containsKey(key) -> true
//            currentTime - alertHistory[key]!! > HISTORY_EXPIRATION_MS -> true
//            else -> false
//        }
//    }
//
//    fun generateHistoryKey(box: BoundingBox): String {
//        val positionKey = when {
//            box.y2 > 0.8f -> "floor"
//            box.cx < 0.3f -> "left"
//            box.cx > 0.7f -> "right"
//            else -> "center"
//        }
//        return "${box.clsName}_$positionKey"
//    }
//
//    fun cleanupExpiredHistory(alertHistory: MutableMap<String, Long>) {
//        val currentTime = System.currentTimeMillis()
//        alertHistory.entries.removeAll { currentTime - it.value > HISTORY_EXPIRATION_MS }
//    }
//
//    const val HISTORY_EXPIRATION_MS = 8000L
}
