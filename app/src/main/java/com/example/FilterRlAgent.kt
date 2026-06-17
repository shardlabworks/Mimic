package com.example

import org.json.JSONArray
import org.json.JSONObject

class FilterRlAgent(private val id: Int) {
    // Q-Table: 6 States (2 Confidences x 3 Speeds) x 3 Actions (Decrease, Keep, Increase Beta)
    private val qTable = Array(6) { FloatArray(3) { 0f } }
    
    private val learningRate = 0.1f
    private val discountFactor = 0.9f
    private var explorationRate = 0.1f // Decays over time
    
    private var currentBeta = 0.04f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastRawZ = 0f
    private var lastVelocity = 0f

    fun getOptimalBeta(rawX: Float, rawY: Float, rawZ: Float, filteredX: Float, filteredY: Float, filteredZ: Float, confidence: Float, dt: Float): Float {
        // True 3D Physics
        val dx = rawX - lastRawX
        val dy = rawY - lastRawY
        val dz = rawZ - lastRawZ
        val trueVelocity = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz) / dt
        val acceleration = kotlin.math.abs(trueVelocity - lastVelocity) / dt
        
        lastRawX = rawX
        lastRawY = rawY
        lastRawZ = rawZ
        lastVelocity = trueVelocity

        // 1. Determine State based on confidence and velocity
        val confidenceState = if (confidence < 0.5f) 0 else 1 // 0=Blurry/Hidden, 1=Clear
        val speedState = when {
            trueVelocity < 1.0f -> 0 // Slow
            trueVelocity < 4.0f -> 1 // Medium
            else -> 2                // Fast
        }

        // 6 Total States: (3 Speeds) x (2 Confidences)
        val state = (confidenceState * 3) + speedState

        // 2. Choose Action (Epsilon-Greedy)
        val action = if (Math.random() < explorationRate) {
            (0..2).random()
        } else {
            qTable[state].indices.maxByOrNull { qTable[state][it] } ?: 1
        }

        // 3. Apply Action to Beta
        currentBeta = when (action) {
            0 -> (currentBeta - 0.01f).coerceAtLeast(0.001f)
            1 -> currentBeta
            2 -> (currentBeta + 0.02f).coerceAtMost(1.0f)
            else -> currentBeta
        }

        // 4. Calculate Reward
        val latency = kotlin.math.sqrt((rawX - filteredX)*(rawX - filteredX) + (rawY - filteredY)*(rawY - filteredY) + (rawZ - filteredZ)*(rawZ - filteredZ))
        val jitter = acceleration // High acceleration means shaking
        
        val reward = if (confidence < 0.5f) {
            // Hidden/Blurry: Punish shaking mercilessly to force maximum smoothing
            -(jitter * 10f) - latency 
        } else if (speedState == 2) {
            // Fast & Clear: Punish latency heavily to force zero-lag snappiness
            -latency * 5f 
        } else {
            // Normal Movement: Balance them
            -jitter - latency 
        }

        // 5. Update Q-Table (Bellman Equation)
        val oldQ = qTable[state][action]
        val maxFutureQ = qTable[state].maxOrNull() ?: 0f
        qTable[state][action] = oldQ + learningRate * (reward + discountFactor * maxFutureQ - oldQ)

        // 6. Epsilon Decay (gradually lock in the learned values to stop glitching over time)
        explorationRate = (explorationRate * 0.9995f).coerceAtLeast(0.05f)

        return currentBeta
    }

    // JSON serialization to save the AI's memory
    fun serialize(): String {
        val jsonArray = JSONArray()
        for (state in qTable) {
            val stateArray = JSONArray()
            for (value in state) stateArray.put(value.toDouble())
            jsonArray.put(stateArray)
        }
        val obj = JSONObject()
        obj.put("q", jsonArray)
        obj.put("e", explorationRate.toDouble())
        return obj.toString()
    }

    fun deserialize(jsonStr: String) {
        try {
            val obj = JSONObject(jsonStr)
            val jsonArray = obj.getJSONArray("q")
            for (i in 0 until 6) {
                val stateArray = jsonArray.getJSONArray(i)
                for (j in 0 until 3) {
                    qTable[i][j] = stateArray.getDouble(j).toFloat()
                }
            }
            explorationRate = obj.getDouble("e").toFloat()
        } catch (e: Exception) {}
    }
}
