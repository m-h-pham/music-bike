package com.app.musicbike.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.*
import androidx.fragment.app.Fragment
import com.app.musicbike.R
import com.app.musicbike.databinding.FragmentSensorsBinding // Import generated binding

class SensorsFragment : Fragment(), SensorEventListener {
    // Embedded sensor views
    private lateinit var embeddedPitch: TextView
    private lateinit var embeddedRoll: TextView
    private lateinit var embeddedYaw: TextView
    private lateinit var embeddedGForce: TextView
    private lateinit var jumpDetected: TextView
    private lateinit var dropDetected: TextView
    private lateinit var wheelSpeed: TextView

    // Phone sensor views
    private lateinit var phonePitch: TextView
    private lateinit var phoneRoll: TextView
    private lateinit var phoneYaw: TextView
    private lateinit var phoneGForce: TextView

    private var _binding: FragmentSensorsBinding? = null
    private val binding get() = _binding!!

    // Phone accelerometer variables
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    // Accelerometer variables for yaw/magnetometer
    // Add to top of SensorsFragment class
    private var magnetometer: Sensor? = null
    private val lastAccelerometerReading = FloatArray(3)
    private val lastMagnetometerReading = FloatArray(3)
    private var hasAccelerometerData = false
    private var hasMagnetometerData = false


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSensorsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        // Embedded
        embeddedPitch = view.findViewById(R.id.embedded_pitch)
        embeddedRoll = view.findViewById(R.id.embedded_roll)
        embeddedYaw = view.findViewById(R.id.embedded_yaw)
        embeddedGForce = view.findViewById(R.id.embedded_gforce)
        jumpDetected = view.findViewById(R.id.jump_detected)
        dropDetected = view.findViewById(R.id.drop_detected)
        wheelSpeed = view.findViewById(R.id.wheel_speed)

        // Phone
        phonePitch = view.findViewById(R.id.phone_pitch)
        phoneRoll = view.findViewById(R.id.phone_roll)
        phoneYaw = view.findViewById(R.id.phone_yaw)
        phoneGForce = view.findViewById(R.id.phone_gforce)
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.also { sensor -> // Add this block
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, lastAccelerometerReading, 0, event.values.size)
                hasAccelerometerData = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> { // Add this case
                System.arraycopy(event.values, 0, lastMagnetometerReading, 0, event.values.size)
                hasMagnetometerData = true
            }
        }

        if (hasAccelerometerData && hasMagnetometerData) {
            val rotationMatrix = FloatArray(9)
            if (SensorManager.getRotationMatrix(
                    rotationMatrix,
                    null,
                    lastAccelerometerReading,
                    lastMagnetometerReading
                )
            ) {
                val orientationAngles = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                val pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
                val yaw = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

                // Keep existing g-force calculation
                val ax = lastAccelerometerReading[0]
                val ay = lastAccelerometerReading[1]
                val az = lastAccelerometerReading[2]
                val gForce = sqrt(ax * ax + ay * ay + az * az) / SensorManager.GRAVITY_EARTH

                updatePhoneSensors(pitch, roll, yaw, gForce)
            }
        }
    }


    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed
    }

    fun updateEmbeddedSensors(
        pitch: Float,
        roll: Float,
        yaw: Float,
        gForce: Float,
        jump: Boolean,
        drop: Boolean,
        speed: Int
    ) {
        activity?.runOnUiThread {
            embeddedPitch.text = "Pitch: $pitch°"
            embeddedRoll.text = "Roll: $roll°"
            embeddedYaw.text = "Yaw: $yaw°"
            embeddedGForce.text = "G-Force: ${"%.2f".format(gForce)}g"
            jumpDetected.text = "Jump Detected: ${if (jump) "Yes" else "No"}"
            dropDetected.text = "Drop Detected: ${if (drop) "Yes" else "No"}"
            wheelSpeed.text = "Wheel Speed: ${speed} RPM"
        }
    }

    // Call this to update phone sensor data
    private fun updatePhoneSensors(pitch: Float, roll: Float, yaw: Float, gForce: Float) {
        activity?.runOnUiThread {
            phonePitch.text = "Pitch: ${"%.2f".format(pitch)}°"
            phoneRoll.text = "Roll: ${"%.2f".format(roll)}°"
            phoneYaw.text = "Yaw: ${"%.2f".format(yaw)}°"
            phoneGForce.text = "G-Force: ${"%.2f".format(gForce)}g"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
    