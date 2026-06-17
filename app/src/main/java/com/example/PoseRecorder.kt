package com.example

import android.content.Context
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PoseRecorder(private val context: Context) {
    private var isRecording = false
    private var frameCount = 0
    private var startTimeMillis: Long = 0

    private var previousLandmarks = Array(35) { FloatArray(3) }
    private var previousLandmarksValid = BooleanArray(35)
    private var deviceGravity: FloatArray? = null
    
    private var jsonWriter: android.util.JsonWriter? = null
    private var currentFile: File? = null
    private var totalConf = 0f
    private var lowConfFrames = 0
    
    // Advanced Corrective Pipelines
    data class KalmanState(var p: Float = 0f, var v: Float = 0f, var cov: Float = 1f)
    private var kalmanStates = Array(35) { Array(3) { KalmanState() } }
    private var lastFrameTime: Long = 0
    private var wristOccludedFrames = mutableMapOf<Int, Int>()
    
    // Anti-swapping and Root Anchoring
    private var inCrossoverEvent = false
    private var rootAnchorActive = false
    private var rootAnchorTargetX = 0f
    private var rootAnchorTargetZ = 0f
    
    private val tempMeasures = FloatArray(3)

    private val bonePairs = listOf(
        Pair(11, 13), Pair(13, 15), // Left arm
        Pair(12, 14), Pair(14, 16), // Right arm
        Pair(23, 25), Pair(25, 27), Pair(27, 31), // Left leg
        Pair(24, 26), Pair(26, 28), Pair(28, 32), // Right leg
        Pair(11, 23), Pair(12, 24), // Torso sides
        Pair(11, 12), Pair(23, 24)  // Shoulders, Hips
    )
    private val calibratedBoneLengths = mutableMapOf<Pair<Int, Int>, Float>()
    private var floorPlaneY = Float.MIN_VALUE
    private var calibratedTPoseJson: org.json.JSONArray? = null
    private val calibrationLock = Any()
    
    private val calibrationAccumulator = mutableMapOf<Int, FloatArray>()
    private var calibrationFramesAccumulated = 0

    fun accumulateCalibration(pose: SmoothedPose) {
        pose.allPoseLandmarks.forEach { landmark ->
            val id = landmark.landmarkType
            if (id >= 35) return@forEach
            
            var posX = landmark.x
            var posY = landmark.y
            var posZ = landmark.z
            
            if (calibrationFramesAccumulated == 0 && id == 0) {
                calibrationAccumulator.clear()
            }
            
            val acc = calibrationAccumulator.getOrPut(id) { FloatArray(3) }
            acc[0] += posX
            acc[1] += posY
            acc[2] += posZ
        }
        if (pose.allPoseLandmarks.isNotEmpty()) {
            calibrationFramesAccumulated++
        }
    }

    fun finalizeCalibration() {
        if (calibrationFramesAccumulated == 0) return
        
        floorPlaneY = Float.MIN_VALUE
        val positions = mutableMapOf<Int, FloatArray>()
        
        val tempJsonArray = org.json.JSONArray()

        for (id in 0 until 35) {
            val acc = calibrationAccumulator[id] ?: continue
            val posX = acc[0] / calibrationFramesAccumulated
            val posY = acc[1] / calibrationFramesAccumulated
            val posZ = acc[2] / calibrationFramesAccumulated
            
            positions[id] = floatArrayOf(posX, posY, posZ)
            
            val lmJson = org.json.JSONObject()
            lmJson.put("id", id)
            lmJson.put("x", posX)
            lmJson.put("y", posY)
            lmJson.put("z", posZ)
            lmJson.put("visibility", 1.0)
            tempJsonArray.put(lmJson)
            
            if (id in 27..32) {
                if (posY > floorPlaneY) {
                    floorPlaneY = posY
                }
            }
        }

        synchronized(calibrationLock) {
            calibratedTPoseJson = tempJsonArray
        }
        
        for (pair in bonePairs) {
            val p1 = positions[pair.first]
            val p2 = positions[pair.second]
            if (p1 != null && p2 != null) {
                val dx = p1[0] - p2[0]
                val dy = p1[1] - p2[1]
                val dz = p1[2] - p2[2]
                val dist = kotlin.math.sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
                calibratedBoneLengths[pair] = dist
            }
        }
        
        // Reset
        calibrationAccumulator.clear()
        calibrationFramesAccumulated = 0
    }

    fun calibrate(pose: SmoothedPose) {
        accumulateCalibration(pose)
        finalizeCalibration()
    }

    fun setDeviceGravity(gravity: FloatArray) {
        deviceGravity = gravity.clone()
    }

    fun startRecording(imageWidth: Int, imageHeight: Int, isFrontCamera: Boolean) {
        frameCount = 0
        totalConf = 0f
        lowConfFrames = 0
        previousLandmarksValid.fill(false)
        kalmanStates = Array(35) { Array(3) { KalmanState() } }
        wristOccludedFrames.clear()
        inCrossoverEvent = false
        rootAnchorActive = false
        lastFrameTime = System.currentTimeMillis()
        
        try {
            val filename = "mocap_session_${Date().time}.mimic"
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (dir?.exists() == false) dir.mkdirs()
            currentFile = File(dir, filename)
            
            val fos = java.io.FileOutputStream(currentFile)
            val gzos = java.util.zip.GZIPOutputStream(fos)
            jsonWriter = android.util.JsonWriter(java.io.OutputStreamWriter(gzos, "UTF-8"))
            jsonWriter?.beginArray()
            
            // Metadata frame
            jsonWriter?.beginObject()
            jsonWriter?.name("type")?.value("metadata")
            jsonWriter?.name("image_width")?.value(imageWidth)
            jsonWriter?.name("image_height")?.value(imageHeight)
            jsonWriter?.name("is_front_camera")?.value(isFrontCamera)
            
            deviceGravity?.let {
                jsonWriter?.name("device_gravity_vector")
                jsonWriter?.beginArray()
                jsonWriter?.value(it[0])
                jsonWriter?.value(it[1])
                jsonWriter?.value(it[2])
                jsonWriter?.endArray()
            }
            
            synchronized(calibrationLock) {
                if (calibratedTPoseJson != null) {
                    jsonWriter?.name("tpose")
                    jsonWriter?.beginArray()
                    for (i in 0 until calibratedTPoseJson!!.length()) {
                        val lmJson = calibratedTPoseJson!!.getJSONObject(i)
                        jsonWriter?.beginObject()
                        if (lmJson.has("id")) jsonWriter?.name("id")?.value(lmJson.getInt("id"))
                        if (lmJson.has("x")) jsonWriter?.name("x")?.value(lmJson.getDouble("x"))
                        if (lmJson.has("y")) jsonWriter?.name("y")?.value(lmJson.getDouble("y"))
                        if (lmJson.has("z")) jsonWriter?.name("z")?.value(lmJson.getDouble("z"))
                        if (lmJson.has("visibility")) jsonWriter?.name("visibility")?.value(lmJson.getDouble("visibility"))
                        jsonWriter?.endObject()
                    }
                    jsonWriter?.endArray()
                }
            }
            jsonWriter?.endObject()
            
        } catch (e: Exception) {
            e.printStackTrace()
            currentFile = null
            jsonWriter = null
        }
        
        startTimeMillis = System.currentTimeMillis()
        isRecording = true
    }

    data class SessionStats(val avgConfidence: Float, val lowConfFrames: Int, val totalFrames: Int)
    var lastSessionStats: SessionStats? = null

    fun stopRecording(): File? {
        isRecording = false
        if (frameCount == 0 || currentFile == null) {
            try {
                jsonWriter?.close()
                jsonWriter = null
                currentFile?.delete()
            } catch (ignored: Exception) {}
            return null
        }
        
        lastSessionStats = SessionStats(totalConf / frameCount, lowConfFrames, frameCount)
        
        try {
            jsonWriter?.endArray()
            jsonWriter?.close()
            jsonWriter = null
            return currentFile
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun recordFrame(pose: SmoothedPose) {
        if (!isRecording) return

        val currentTime = System.currentTimeMillis()
        val timestamp = (currentTime - startTimeMillis) / 1000.0
        val timestampUtc = currentTime
        var dt = (currentTime - lastFrameTime) / 1000f
        if (dt <= 0f) dt = 0.033f
        lastFrameTime = currentTime

        val landmarkData = FloatArray(35 * 5)
        
        // Anti-Swapping Pre-Processing
        val rawX = FloatArray(35)
        val rawY = FloatArray(35)
        val rawZ = FloatArray(35)
        val rawConf = FloatArray(35)
        val validIds = BooleanArray(35)
        
        pose.allPoseLandmarks.forEach { landmark ->
            val id = landmark.landmarkType
            if (id < 35) {
                rawX[id] = landmark.x
                rawY[id] = landmark.y
                rawZ[id] = landmark.z
                rawConf[id] = landmark.inFrameLikelihood
                validIds[id] = true
            }
        }
        
        // Crossover Detection (Anti-Swapping)
        if (validIds[27] && validIds[28] && previousLandmarksValid[27] && previousLandmarksValid[28] && dt > 0f) {
            val lAnkleX = rawX[27]
            val rAnkleX = rawX[28]
            val prevLAnkleX = previousLandmarks[27][0]
            val prevRAnkleX = previousLandmarks[28][0]
            
            val lVelX = kotlin.math.abs(lAnkleX - prevLAnkleX) / dt
            val rVelX = kotlin.math.abs(rAnkleX - prevRAnkleX) / dt
            
            val teleportVel = 1000.0f
            if (lVelX > teleportVel && rVelX > teleportVel) {
                val wasLeftOfRight = prevLAnkleX < prevRAnkleX
                val isLeftOfRight = lAnkleX < rAnkleX
                if (wasLeftOfRight != isLeftOfRight) {
                    inCrossoverEvent = !inCrossoverEvent
                }
            } else if (lVelX < 50.0f && rVelX < 50.0f) {
                inCrossoverEvent = false
            }
        }
        
        if (inCrossoverEvent) {
             val swapPairs = listOf(23 to 24, 25 to 26, 27 to 28, 29 to 30, 31 to 32)
             for ((l, r) in swapPairs) {
                 if (validIds[l] && validIds[r]) {
                     val tx = rawX[l]; val ty = rawY[l]; val tz = rawZ[l]; val tc = rawConf[l]
                     rawX[l] = rawX[r]; rawY[l] = rawY[r]; rawZ[l] = rawZ[r]; rawConf[l] = rawConf[r]
                     rawX[r] = tx; rawY[r] = ty; rawZ[r] = tz; rawConf[r] = tc
                 }
             }
        }

        // 1. Base processing & Kalman Filtering
        for (id in 0 until 35) {
            if (!validIds[id]) continue
            
            var posX = rawX[id]
            var posY = rawY[id]
            var posZ = rawZ[id]
            var confidence = rawConf[id]
            
            // Initialization
            if (kalmanStates[id][0].cov == 1f && !previousLandmarksValid[id]) {
                kalmanStates[id][0].p = posX
                kalmanStates[id][1].p = posY
                kalmanStates[id][2].p = posZ
            }
            
            // 5. Occlusion Fallback (Dead Reckoning)
            if (id == 15 || id == 16) { // Wrists
                if (confidence < 0.3f) {
                    val occludedCount = wristOccludedFrames.getOrDefault(id, 0) + 1
                    wristOccludedFrames[id] = occludedCount
                    
                    if (occludedCount > 4) {
                         // Interpolate down to hip
                         val hipId = if (id == 15) 23 else 24
                         if (previousLandmarksValid[hipId]) {
                             val hipPos = previousLandmarks[hipId]
                             posX = kalmanStates[id][0].p + (hipPos[0] - kalmanStates[id][0].p) * 0.1f
                             posY = kalmanStates[id][1].p + ((hipPos[1] + 150f) - kalmanStates[id][1].p) * 0.1f // Add to Y to drop it slightly below hip
                             posZ = kalmanStates[id][2].p + (hipPos[2] - kalmanStates[id][2].p) * 0.1f
                             confidence = 1.0f // Treat artificial target as strong
                         } else {
                             confidence = 0.001f // Dead reckon
                         }
                    } else {
                        confidence = 0.001f // Dead reckon for first few frames
                    }
                } else {
                    wristOccludedFrames[id] = 0
                }
            }

            // 4. Kalman Bypass (Data is already 1€ smoothed)
            tempMeasures[0] = posX
            tempMeasures[1] = posY
            tempMeasures[2] = posZ
            
            // Keep the states updated so the Occlusion Fallback (Dead Reckoning) doesn't crash
            for (axis in 0..2) {
                kalmanStates[id][axis].p = tempMeasures[axis]
            }
            
            var planted = 0f
            if (id == 31 || id == 32 || id == 27 || id == 28) {
                if (previousLandmarksValid[id] && confidence > 0.4f && dt > 0f) {
                    val dx = tempMeasures[0] - previousLandmarks[id][0]
                    val dy = tempMeasures[1] - previousLandmarks[id][1]
                    val dz = tempMeasures[2] - previousLandmarks[id][2]
                    val speed = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz) / dt
                    val distToFloor = floorPlaneY - tempMeasures[1]
                    if (speed < 100.0f && kotlin.math.abs(distToFloor) < 40.0f) {
                        planted = 1f
                    }
                }
            }
            
            val baseIdx = id * 5
            landmarkData[baseIdx] = tempMeasures[0]
            landmarkData[baseIdx + 1] = tempMeasures[1]
            landmarkData[baseIdx + 2] = tempMeasures[2]
            landmarkData[baseIdx + 3] = confidence
            landmarkData[baseIdx + 4] = planted
        }
        
        // 3. Absolute Floor Penetration Guard
        if (floorPlaneY > Float.MIN_VALUE) {
            for (id in 27..32) {
                if (landmarkData[id*5 + 1] > floorPlaneY) {
                    landmarkData[id*5 + 1] = floorPlaneY
                    kalmanStates[id][1].p = floorPlaneY
                    kalmanStates[id][1].v = 0f
                }
            }
        }
        
        // Root Anchoring (Hip Stabilization)
        val leftPlanted = landmarkData[31*5 + 4] == 1f || landmarkData[27*5 + 4] == 1f
        val rightPlanted = landmarkData[32*5 + 4] == 1f || landmarkData[28*5 + 4] == 1f
        
        if (leftPlanted && rightPlanted) {
            val hX = (landmarkData[23*5] + landmarkData[24*5]) / 2f
            val hZ = (landmarkData[23*5 + 2] + landmarkData[24*5 + 2]) / 2f
            
            if (!rootAnchorActive) {
                rootAnchorActive = true
                rootAnchorTargetX = hX
                rootAnchorTargetZ = hZ
            } else {
                // Heavy Exponential Moving Average for Idle State
                rootAnchorTargetX = rootAnchorTargetX * 0.95f + hX * 0.05f
                rootAnchorTargetZ = rootAnchorTargetZ * 0.95f + hZ * 0.05f
                
                val shiftX = rootAnchorTargetX - hX
                val shiftZ = rootAnchorTargetZ - hZ
                
                landmarkData[23*5] += shiftX
                landmarkData[23*5 + 2] += shiftZ
                landmarkData[24*5] += shiftX
                landmarkData[24*5 + 2] += shiftZ
                
                kalmanStates[23][0].p = landmarkData[23*5]
                kalmanStates[23][2].p = landmarkData[23*5 + 2]
                kalmanStates[24][0].p = landmarkData[24*5]
                kalmanStates[24][2].p = landmarkData[24*5 + 2]
            }
        } else {
            rootAnchorActive = false
        }
        
        // 1. Bone Length Enforcement (Distance Cage)
        for (pair in bonePairs) {
            val parent = pair.first
            val child = pair.second
            val expectedLen = calibratedBoneLengths[pair] ?: continue
            
            val pX = landmarkData[parent*5]
            val pY = landmarkData[parent*5+1]
            val pZ = landmarkData[parent*5+2]
            
            val cX = landmarkData[child*5]
            val cY = landmarkData[child*5+1]
            val cZ = landmarkData[child*5+2]
            
            val dx = cX - pX
            val dy = cY - pY
            val dz = cZ - pZ
            val currentLen = kotlin.math.sqrt((dx*dx + dy*dy + dz*dz).toDouble()).toFloat()
            
            if (currentLen > 0.001f) {
                val scale = expectedLen / currentLen
                val newX = pX + dx * scale
                val newY = pY + dy * scale
                val newZ = pZ + dz * scale
                
                landmarkData[child*5] = newX
                landmarkData[child*5+1] = newY
                landmarkData[child*5+2] = newZ
                
                // Update Kalman state to reflect physical constraint
                kalmanStates[child][0].p = newX
                kalmanStates[child][1].p = newY
                kalmanStates[child][2].p = newZ
            }
        }
        
        // 2. Anatomical Hinge Clamping
        val clampHinge = { parentId: Int, hingeId: Int, childId: Int, isKnee: Boolean ->
            val hX = landmarkData[hingeId*5]; val hY = landmarkData[hingeId*5+1]; val hZ = landmarkData[hingeId*5+2]
            val pX = landmarkData[parentId*5]; val pY = landmarkData[parentId*5+1]; val pZ = landmarkData[parentId*5+2]
            val cX = landmarkData[childId*5]; val cY = landmarkData[childId*5+1]; val cZ = landmarkData[childId*5+2]
            
            val v1 = Vector3(hX - pX, hY - pY, hZ - pZ).normalized()
            val v2 = Vector3(cX - hX, cY - hY, cZ - hZ).normalized()
            
            // Vector from Left Hip (23) to Right Hip (24) -> body right
            val rHipX = landmarkData[24*5]; val rHipZ = landmarkData[24*5+2]
            val lHipX = landmarkData[23*5]; val lHipZ = landmarkData[23*5+2]
            val rightward = Vector3(rHipX - lHipX, 0f, rHipZ - lHipZ).normalized()
            
            // Forward is Rightward x Down
            val down = Vector3(0f, 1f, 0f)
            val forward = rightward.cross(down).normalized()

            if (isKnee) {
                // Knee bends back (away from forward). If shin points forward, it's bent backwards (flamingo).
                val proj = v2.dot(forward)
                if (proj > 0.1f) {
                    // Remove forward component
                    val clampedV2 = v2.sub(forward.mul(proj)).normalized()
                    val boneLen = calibratedBoneLengths[Pair(hingeId, childId)] ?: Vector3(cX - hX, cY - hY, cZ - hZ).length()
                    val newPos = Vector3(hX, hY, hZ).add(clampedV2.mul(boneLen))
                    landmarkData[childId*5] = newPos.x
                    landmarkData[childId*5+1] = newPos.y
                    landmarkData[childId*5+2] = newPos.z
                    kalmanStates[childId][0].p = newPos.x; kalmanStates[childId][1].p = newPos.y; kalmanStates[childId][2].p = newPos.z
                }
            } else {
                // Elbow bends forward. If forearm points backward (opposite of forward), it's bent backwards.
                val proj = v2.dot(forward)
                if (proj < -0.1f) {
                    val clampedV2 = v2.sub(forward.mul(proj)).normalized()
                    val boneLen = calibratedBoneLengths[Pair(hingeId, childId)] ?: Vector3(cX - hX, cY - hY, cZ - hZ).length()
                    val newPos = Vector3(hX, hY, hZ).add(clampedV2.mul(boneLen))
                    landmarkData[childId*5] = newPos.x
                    landmarkData[childId*5+1] = newPos.y
                    landmarkData[childId*5+2] = newPos.z
                    kalmanStates[childId][0].p = newPos.x; kalmanStates[childId][1].p = newPos.y; kalmanStates[childId][2].p = newPos.z
                }
            }
        }
        
        val solveKneeIK = { hipId: Int, kneeId: Int, ankleId: Int, footIndex: Int ->
            val L1 = calibratedBoneLengths[Pair(hipId, kneeId)] ?: 0f
            val L2 = calibratedBoneLengths[Pair(kneeId, ankleId)] ?: 0f
            if (L1 > 0 && L2 > 0) {
                val hX = landmarkData[hipId*5]; val hY = landmarkData[hipId*5+1]; val hZ = landmarkData[hipId*5+2]
                val aX = landmarkData[ankleId*5]; val aY = landmarkData[ankleId*5+1]; val aZ = landmarkData[ankleId*5+2]
                
                val dx = aX - hX
                val dy = aY - hY
                val dz = aZ - hZ
                val dsq = dx*dx + dy*dy + dz*dz
                val d = kotlin.math.sqrt(dsq.toDouble()).toFloat()
                
                if (d > 0.001f && d < (L1 + L2)) {
                    val a = (L1*L1 - L2*L2 + d*d) / (2 * d)
                    val radiusSq = L1*L1 - a*a
                    if (radiusSq > 0) {
                        val radius = kotlin.math.sqrt(radiusSq.toDouble()).toFloat()
                        
                        val cx = hX + dx * (a / d)
                        val cy = hY + dy * (a / d)
                        val cz = hZ + dz * (a / d)
                        
                        val nX = dx / d; val nY = dy / d; val nZ = dz / d
                        
                        val rHipX = landmarkData[24*5]; val rHipZ = landmarkData[24*5+2]
                        val lHipX = landmarkData[23*5]; val lHipZ = landmarkData[23*5+2]
                        val rightward = Vector3(rHipX - lHipX, 0f, rHipZ - lHipZ).normalized()
                        val down = Vector3(0f, 1f, 0f)
                        val forward = rightward.cross(down).normalized()
                        
                        val dot = forward.x*nX + forward.y*nY + forward.z*nZ
                        var projX = forward.x - dot * nX
                        var projY = forward.y - dot * nY
                        var projZ = forward.z - dot * nZ
                        
                        val projMag = kotlin.math.sqrt((projX*projX + projY*projY + projZ*projZ).toDouble()).toFloat()
                        if (projMag > 0.001f) {
                            projX /= projMag
                            projY /= projMag
                            projZ /= projMag
                            
                            val newKneeX = cx + projX * radius
                            val newKneeY = cy + projY * radius
                            val newKneeZ = cz + projZ * radius
                            
                            landmarkData[kneeId*5] = newKneeX
                            landmarkData[kneeId*5+1] = newKneeY
                            landmarkData[kneeId*5+2] = newKneeZ
                            
                            kalmanStates[kneeId][0].p = newKneeX
                            kalmanStates[kneeId][1].p = newKneeY
                            kalmanStates[kneeId][2].p = newKneeZ
                        }
                    }
                }
            } else {
                clampHinge(hipId, kneeId, ankleId, true)
            }
        }
        
        solveKneeIK(23, 25, 27, 31) // Left knee
        solveKneeIK(24, 26, 28, 32) // Right knee
        // Clamp elbows
        clampHinge(11, 13, 15, false) // Left elbow
        clampHinge(12, 14, 16, false) // Right elbow

        // Finalize state update
        for (i in 0 until 35) {
            previousLandmarks[i][0] = landmarkData[i*5]
            previousLandmarks[i][1] = landmarkData[i*5+1]
            previousLandmarks[i][2] = landmarkData[i*5+2]
            previousLandmarksValid[i] = true
        }
        
        var sumConf = 0f
        for (i in 0 until 35) {
            sumConf += landmarkData[i * 5 + 3]
        }
        val avgConf = sumConf / 35f
        if (avgConf < 0.6f) lowConfFrames++
        totalConf += avgConf
        
        try {
            if (jsonWriter != null) {
                jsonWriter?.beginObject()
                jsonWriter?.name("frame")?.value(frameCount)
                jsonWriter?.name("timestamp")?.value(timestamp)
                jsonWriter?.name("timestamp_utc")?.value(timestampUtc)
                
                jsonWriter?.name("landmarks")
                jsonWriter?.beginArray()
                for (i in 0 until 35) {
                    val baseIdx = i * 5
                    val vis = landmarkData[baseIdx + 3]
                    
                    // OPTIMIZATION 1: Skip invisible landmarks entirely. 
                    // The plugin handles missing IDs perfectly.
                    if (vis < 0.05f) continue 
                    
                    // OPTIMIZATION 2: Truncate floats to 3 decimal places
                    val x = (landmarkData[baseIdx] * 1000).toInt() / 1000.0
                    val y = (landmarkData[baseIdx + 1] * 1000).toInt() / 1000.0
                    val z = (landmarkData[baseIdx + 2] * 1000).toInt() / 1000.0
                    val v = (vis * 100).toInt() / 100.0 // 2 decimal places
                    
                    jsonWriter?.beginObject()
                    jsonWriter?.name("id")?.value(i)
                    jsonWriter?.name("x")?.value(x)
                    jsonWriter?.name("y")?.value(y)
                    jsonWriter?.name("z")?.value(z)
                    jsonWriter?.name("visibility")?.value(v) // REQUIRED BY BLENDER PLUGIN
                    
                    if (i == 31 || i == 32) {
                        val planted = landmarkData[baseIdx + 4].toInt()
                        if (planted == 1) { // OPTIMIZATION 3: Omit 'planted' if 0 (plugin defaults to 0)
                            jsonWriter?.name("planted")?.value(1)
                        }
                    }
                    jsonWriter?.endObject()
                }
                jsonWriter?.endArray()
                
                jsonWriter?.endObject()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording = false
            try { jsonWriter?.close() } catch (ignored: Exception) {}
            jsonWriter = null
        }
        
        frameCount++
    }

    fun isRecording() = isRecording
    fun getBufferLength() = frameCount
}
