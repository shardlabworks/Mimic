package com.example

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

data class SmoothedPoseLandmark(
    val landmarkType: Int,
    val x: Float,
    val y: Float,
    val z: Float,
    val inFrameLikelihood: Float
)

data class SmoothedPose(val allPoseLandmarks: List<SmoothedPoseLandmark>) {
    fun getPoseLandmark(type: Int): SmoothedPoseLandmark? {
        return allPoseLandmarks.find { it.landmarkType == type }
    }
}

class AdaptivePoseProcessor {
    private val bank = SmoothJointBank()

    fun getSaveData() = bank.getAgentData()
    fun loadData(str: String) { bank.loadAgentData(str) }

    fun process(pose: Pose, timestampMs: Long): SmoothedPose {
        val rawLandmarks = pose.allPoseLandmarks
        val smoothedLandmarks = mutableListOf<SmoothedPoseLandmark>()

        for (landmark in rawLandmarks) {
            val id = landmark.landmarkType
            val conf = landmark.inFrameLikelihood
            
            var x = landmark.position.x
            var y = landmark.position.y
            var z = 0f
            
            try {
                val p3d = landmark.javaClass.getMethod("getPosition3D").invoke(landmark)
                x = p3d.javaClass.getMethod("getX").invoke(p3d) as Float
                y = p3d.javaClass.getMethod("getY").invoke(p3d) as Float
                z = p3d.javaClass.getMethod("getZ").invoke(p3d) as Float
            } catch (e: Exception) {}

            val smoothed = bank.smooth(id, x, y, z, conf, timestampMs)
            
            smoothedLandmarks.add(
                SmoothedPoseLandmark(
                    landmarkType = id,
                    x = smoothed[0],
                    y = smoothed[1],
                    z = smoothed[2],
                    inFrameLikelihood = conf
                )
            )
        }

        val lShoulder = smoothedLandmarks.find { it.landmarkType == 11 }
        val rShoulder = smoothedLandmarks.find { it.landmarkType == 12 }
        val lHip = smoothedLandmarks.find { it.landmarkType == 23 }
        val rHip = smoothedLandmarks.find { it.landmarkType == 24 }

        if (lShoulder != null && rShoulder != null && lHip != null && rHip != null) {
            val midShoulderX = (lShoulder.x + rShoulder.x) / 2f
            val midShoulderY = (lShoulder.y + rShoulder.y) / 2f
            val midShoulderZ = (lShoulder.z + rShoulder.z) / 2f

            val midHipX = (lHip.x + rHip.x) / 2f
            val midHipY = (lHip.y + rHip.y) / 2f
            val midHipZ = (lHip.z + rHip.z) / 2f

            val conf = kotlin.math.min(kotlin.math.min(lShoulder.inFrameLikelihood, rShoulder.inFrameLikelihood), kotlin.math.min(lHip.inFrameLikelihood, rHip.inFrameLikelihood))

            // ID 33: Lower Spine (closer to hips, 1/3 up from hips)
            val lowerSpineX = midHipX + (midShoulderX - midHipX) * 0.33f
            val lowerSpineY = midHipY + (midShoulderY - midHipY) * 0.33f
            val lowerSpineZ = midHipZ + (midShoulderZ - midHipZ) * 0.33f

            smoothedLandmarks.add(SmoothedPoseLandmark(33, lowerSpineX, lowerSpineY, lowerSpineZ, conf))

            // ID 34: Upper Spine (closer to shoulders, 2/3 up from hips)
            val upperSpineX = midHipX + (midShoulderX - midHipX) * 0.66f
            val upperSpineY = midHipY + (midShoulderY - midHipY) * 0.66f
            val upperSpineZ = midHipZ + (midShoulderZ - midHipZ) * 0.66f

            smoothedLandmarks.add(SmoothedPoseLandmark(34, upperSpineX, upperSpineY, upperSpineZ, conf))
        }

        return SmoothedPose(smoothedLandmarks)
    }
}
