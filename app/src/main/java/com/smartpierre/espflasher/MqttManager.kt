package com.smartpierre.espflasher

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import android.os.Build
import android.os.BatteryManager
import android.content.IntentFilter
import android.content.Intent
import java.util.Timer
import java.util.TimerTask
import android.os.Handler
import android.os.Looper

/**
 * MQTT Manager for HiveMQ Cloud
 * Handles publishing serial logs to HiveMQ for live sharing
 */
class MqttManager(
    private val context: Context,
    private val onConnectionStatus: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    companion object {
        // HiveMQ Cloud credentials
        private const val BROKER_URL = "ssl://0c1bf62a21e94682adf340b8a2d3fe04.s1.eu.hivemq.cloud:8883"
        private const val USERNAME = "pierreflasher"
        private const val PASSWORD = "Pierre2k23"

        // Topics
        private const val TOPIC_PREFIX = "pierre/serial"

        // Connection settings
        private const val KEEP_ALIVE_INTERVAL = 20 // seconds - shorter for faster disconnect detection
        private const val CONNECTION_TIMEOUT = 30 // seconds
        private const val QOS = 0 // Fire and forget for real-time logs

        // Share URL base - GitHub Pages URL
        const val SHARE_URL_BASE = "https://mindenhato1998.github.io/pierre-serial"
    }

    private var mqttClient: MqttClient? = null
    private var isConnected = false
    private var deviceId: String = ""
    private var sessionId: String = ""
    private var batteryUpdateTimer: java.util.Timer? = null
    private var heartbeatTimer: java.util.Timer? = null
    private var reconnectHandler = Handler(Looper.getMainLooper())
    private var connectionCheckTimer: Timer? = null

    // Circular buffer for message history - keep only last 1000 messages
    private val messageHistory = mutableListOf<String>()
    private val MAX_HISTORY_SIZE = 1000 // Keep only last 1000 messages
    private var messageCount = 0 // Current message count (max 1000)

    init {
        // Generate unique device ID (persisted)
        deviceId = getOrCreateDeviceId()
        // Generate or reuse session ID (persists for 5 minutes)
        sessionId = getOrCreateSessionId()
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences("mqtt_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().take(12)
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun getOrCreateSessionId(): String {
        val prefs = context.getSharedPreferences("mqtt_prefs", Context.MODE_PRIVATE)
        val storedSessionId = prefs.getString("session_id", null)
        val sessionTimestamp = prefs.getLong("session_timestamp", 0)
        val currentTime = System.currentTimeMillis()

        // Check if we have a stored session ID and it's less than 5 minutes old
        val fiveMinutesInMillis = 5 * 60 * 1000 // 5 minutes

        return if (storedSessionId != null &&
                   (currentTime - sessionTimestamp) < fiveMinutesInMillis) {
            // Reuse existing session ID
            Log.d("MqttManager", "Reusing existing session ID: $storedSessionId (age: ${(currentTime - sessionTimestamp)/1000}s)")

            // Don't update timestamp here - only update it when actively sending data
            // This prevents the session from being kept alive indefinitely just by reopening the app

            storedSessionId
        } else {
            // Generate new session ID
            val newSessionId = UUID.randomUUID().toString().take(8)
            Log.d("MqttManager", "Generating new session ID: $newSessionId")

            // Save new session ID with current timestamp
            prefs.edit()
                .putString("session_id", newSessionId)
                .putLong("session_timestamp", currentTime)
                .apply()

            // Reset message history for new session
            synchronized(messageHistory) {
                messageHistory.clear()
                messageCount = 0
            }

            newSessionId
        }
    }

    /**
     * Update session timestamp to keep the session alive while actively using the app
     */
    private fun updateSessionTimestamp() {
        val prefs = context.getSharedPreferences("mqtt_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("session_timestamp", System.currentTimeMillis()).apply()
    }

    /**
     * Connect to HiveMQ Cloud
     */
    fun connect() {
        try {
            disconnect() // Clean up any existing connection

            val clientId = "pierre-android-${deviceId}-${System.currentTimeMillis()}"
            mqttClient = MqttClient(BROKER_URL, clientId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = CONNECTION_TIMEOUT
                keepAliveInterval = KEEP_ALIVE_INTERVAL
                userName = USERNAME
                password = PASSWORD.toCharArray()

                // Set Last Will and Testament - this will be sent automatically if connection is lost
                val willTopic = "$TOPIC_PREFIX/$deviceId/$sessionId/status"
                val willMessage = "disconnected".toByteArray()
                setWill(willTopic, willMessage, QOS, false) // retained = false to avoid stale messages

                // SSL/TLS setup for HiveMQ Cloud
                socketFactory = getSSLSocketFactory()

                // Auto reconnect
                isAutomaticReconnect = true
                maxReconnectDelay = 10000
            }

            mqttClient?.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.e("MqttManager", "Connection lost: ${cause?.message}")
                    isConnected = false
                    onConnectionStatus(false)

                    // Auto reconnect is handled by MQTT client automatically
                    // Don't call connect() manually - it would interfere with auto-reconnect
                    Log.d("MqttManager", "Will auto-reconnect via MQTT client")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    // We don't subscribe, only publish
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    // Delivery confirmed
                }
            })

            mqttClient?.connectWithResult(options)?.waitForCompletion()
            isConnected = true
            onConnectionStatus(true)
            Log.d("MqttManager", "Successfully connected to HiveMQ")

            // Publish connected status and device info after a short delay
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    publishConnectedStatus()
                    publishDeviceInfo()
                }
            }, 1000) // 1 second delay

            // Start periodic battery updates (every 30 seconds)
            startBatteryUpdates()

            // Start heartbeat (every 10 seconds)
            startHeartbeat()

            // Start connection monitoring
            startConnectionMonitoring()

            // Don't publish automatic messages - only serial data
            Log.d("MqttManager", "Connected to HiveMQ Cloud")

            // Update session timestamp when successfully connecting
            updateSessionTimestamp()

            // Send a special message indicating buffer configuration
            publishBufferConfig()

        } catch (e: Exception) {
            Log.e("MqttManager", "Connection failed: ${e.message}")
            isConnected = false
            onConnectionStatus(false)
            onError("MQTT connection failed: ${e.message}")
        }
    }

    /**
     * Publish serial log line to HiveMQ with circular buffer management
     */
    fun publishLog(logLine: String) {
        if (!isConnected || mqttClient == null) {
            return
        }

        try {
            // Add to circular buffer
            synchronized(messageHistory) {
                if (messageHistory.size >= MAX_HISTORY_SIZE) {
                    // Remove oldest message when buffer is full
                    messageHistory.removeAt(0)
                    // Keep count at max value
                    messageCount = MAX_HISTORY_SIZE
                } else {
                    // Increment count only until we reach max
                    messageCount = messageHistory.size + 1
                }

                messageHistory.add(logLine)
            }

            val topic = "$TOPIC_PREFIX/$deviceId/$sessionId"
            val message = MqttMessage(logLine.toByteArray()).apply {
                qos = QOS
                isRetained = false // Don't retain for real-time logs
            }

            mqttClient?.publish(topic, message)

            // Update session timestamp to keep the session alive while actively sending data
            updateSessionTimestamp()

        } catch (e: Exception) {
            Log.e("MqttManager", "Publish failed: ${e.message}")
        }
    }

    /**
     * Publish multiple log lines
     */
    fun publishLogs(logs: List<String>) {
        logs.forEach { publishLog(it) }
    }

    /**
     * Publish buffer configuration to inform web clients about circular buffer
     */
    private fun publishBufferConfig() {
        if (!isConnected || mqttClient == null) return

        try {
            // Send buffer config on a special topic
            val configTopic = "$TOPIC_PREFIX/$deviceId/$sessionId/config"
            val configMessage = "CIRCULAR_BUFFER:$MAX_HISTORY_SIZE"
            val message = MqttMessage(configMessage.toByteArray()).apply {
                qos = QOS
                isRetained = true // Retain so new subscribers get the config
            }

            mqttClient?.publish(configTopic, message)
            Log.d("MqttManager", "Published buffer config: $configMessage")

            // Also send current message count (max 1000)
            val countTopic = "$TOPIC_PREFIX/$deviceId/$sessionId/count"
            val countMessage = messageCount.toString()
            val countMsg = MqttMessage(countMessage.toByteArray()).apply {
                qos = QOS
                isRetained = true
            }

            mqttClient?.publish(countTopic, countMsg)
        } catch (e: Exception) {
            Log.e("MqttManager", "Failed to publish buffer config: ${e.message}")
        }
    }

    /**
     * Publish connected status
     */
    private fun publishConnectedStatus() {
        if (!isConnected || mqttClient == null) {
            return
        }

        try {
            // First clear any old retained message by publishing empty retained message
            val topic = "$TOPIC_PREFIX/$deviceId/$sessionId/status"
            val clearMessage = MqttMessage("".toByteArray()).apply {
                qos = QOS
                isRetained = true
            }
            mqttClient?.publish(topic, clearMessage)

            // Then publish connected message
            Thread.sleep(100)
            val message = MqttMessage("connected".toByteArray()).apply {
                qos = QOS
                isRetained = false // Don't retain - rely on LWT for disconnect
            }

            mqttClient?.publish(topic, message)
            Log.d("MqttManager", "Published connected status")

        } catch (e: Exception) {
            Log.e("MqttManager", "Failed to publish connected status: ${e.message}")
        }
    }

    /**
     * Publish disconnect status
     */
    private fun publishDisconnectStatus() {
        if (!isConnected || mqttClient == null) {
            return
        }

        try {
            // Publish disconnect message to status topic
            val topic = "$TOPIC_PREFIX/$deviceId/$sessionId/status"
            val message = MqttMessage("disconnected".toByteArray()).apply {
                qos = QOS
                isRetained = true // Retain so new subscribers know the device is offline
            }

            mqttClient?.publish(topic, message)
            Log.d("MqttManager", "Published disconnect status")

        } catch (e: Exception) {
            Log.e("MqttManager", "Failed to publish disconnect status: ${e.message}")
        }
    }

    /**
     * Publish device information (name and battery)
     */
    fun publishDeviceInfo() {
        if (!isConnected || mqttClient == null) {
            return
        }

        try {
            // Get device name
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

            // Get battery percentage
            val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level != -1 && scale != -1) {
                (level * 100 / scale)
            } else {
                -1
            }

            // Publish device info to special topic
            val topic = "$TOPIC_PREFIX/$deviceId/$sessionId/info"
            val infoMessage = "$deviceName|$batteryPct"
            val message = MqttMessage(infoMessage.toByteArray()).apply {
                qos = QOS
                isRetained = true // Retain device info
            }

            mqttClient?.publish(topic, message)
            Log.d("MqttManager", "Published device info: $infoMessage")

        } catch (e: Exception) {
            Log.e("MqttManager", "Failed to publish device info: ${e.message}")
        }
    }

    /**
     * Get shareable URL for this session
     */
    fun getShareUrl(): String {
        // This creates a direct link that auto-connects
        val url = "$SHARE_URL_BASE?device=$deviceId&session=$sessionId"
        Log.d("MqttManager", "Generated share URL: $url")
        Log.d("MqttManager", "Device ID: $deviceId")
        Log.d("MqttManager", "Session ID: $sessionId")
        return url
    }

    /**
     * Get current topic for display
     */
    fun getCurrentTopic(): String {
        return "$TOPIC_PREFIX/$deviceId/$sessionId"
    }

    /**
     * Start periodic battery updates
     */
    private fun startBatteryUpdates() {
        stopBatteryUpdates() // Stop any existing timer

        batteryUpdateTimer = java.util.Timer()
        batteryUpdateTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                if (isConnected) {
                    publishDeviceInfo()
                }
            }
        }, 30000, 30000) // Start after 30 seconds, repeat every 30 seconds
    }

    /**
     * Stop periodic battery updates
     */
    private fun stopBatteryUpdates() {
        batteryUpdateTimer?.cancel()
        batteryUpdateTimer = null
    }

    /**
     * Start heartbeat to keep connection alive
     */
    private fun startHeartbeat() {
        stopHeartbeat() // Stop any existing timer

        heartbeatTimer = Timer()
        heartbeatTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (isConnected) {
                    try {
                        // Send heartbeat status
                        val topic = "$TOPIC_PREFIX/$deviceId/$sessionId/status"
                        val message = MqttMessage("connected".toByteArray()).apply {
                            qos = QOS
                            isRetained = false
                        }
                        mqttClient?.publish(topic, message)
                        Log.d("MqttManager", "Heartbeat sent")
                    } catch (e: Exception) {
                        Log.e("MqttManager", "Heartbeat failed: ${e.message}")
                    }
                }
            }
        }, 10000, 10000) // Every 10 seconds
    }

    /**
     * Stop heartbeat
     */
    private fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }

    /**
     * Start connection monitoring to detect successful reconnections
     */
    private fun startConnectionMonitoring() {
        stopConnectionMonitoring() // Stop any existing timer

        connectionCheckTimer = Timer()
        connectionCheckTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    val clientConnected = mqttClient?.isConnected == true

                    // Check if connection state changed
                    if (clientConnected && !isConnected) {
                        // Reconnection detected!
                        Log.d("MqttManager", "Reconnection detected - updating status")
                        isConnected = true
                        onConnectionStatus(true)

                        // Re-publish status and device info after reconnection
                        publishConnectedStatus()
                        publishDeviceInfo()

                        // Restart timers if needed
                        if (batteryUpdateTimer == null) startBatteryUpdates()
                        if (heartbeatTimer == null) startHeartbeat()

                    } else if (!clientConnected && isConnected) {
                        // Connection lost detected
                        Log.d("MqttManager", "Connection lost detected")
                        isConnected = false
                        onConnectionStatus(false)
                    }
                } catch (e: Exception) {
                    Log.e("MqttManager", "Connection monitoring error: ${e.message}")
                }
            }
        }, 2000, 5000) // Check every 5 seconds
    }

    /**
     * Stop connection monitoring
     */
    private fun stopConnectionMonitoring() {
        connectionCheckTimer?.cancel()
        connectionCheckTimer = null
    }

    /**
     * Disconnect from HiveMQ
     */
    fun disconnect() {
        try {
            stopBatteryUpdates() // Stop battery updates
            stopHeartbeat() // Stop heartbeat
            stopConnectionMonitoring() // Stop connection monitoring
            if (isConnected) {
                // No need to publish disconnect - LWT will handle it automatically
                mqttClient?.disconnect()
                isConnected = false
                onConnectionStatus(false)
            }
            mqttClient?.close()
        } catch (e: Exception) {
            Log.e("MqttManager", "Disconnect error: ${e.message}")
        }
    }

    /**
     * Check connection status
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Create SSL Socket Factory for HiveMQ Cloud
     */
    private fun getSSLSocketFactory(): SSLSocketFactory {
        return try {
            // Trust all certificates for simplicity (HiveMQ Cloud has valid certs)
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            sslContext.socketFactory
        } catch (e: Exception) {
            SSLContext.getDefault().socketFactory
        }
    }
}