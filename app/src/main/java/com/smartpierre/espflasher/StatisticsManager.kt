package com.smartpierre.espflasher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Statistics Manager for ESP32 Flasher
 * Sends flash/erase operation statistics to MQTT for dashboard monitoring
 */
class StatisticsManager(
    private val context: Context,
    private val onError: (String) -> Unit = {}
) {
    companion object {
        // Same HiveMQ Cloud credentials as MqttManager
        private const val BROKER_URL = "ssl://0c1bf62a21e94682adf340b8a2d3fe04.s1.eu.hivemq.cloud:8883"
        private const val USERNAME = "pierreflasher"
        private const val PASSWORD = "Pierre2k23"

        // Statistics topics
        private const val STATS_TOPIC_PREFIX = "pierre/stats"
        private const val STATUS_TOPIC_PREFIX = "pierre/status"

        // Connection settings
        private const val CONNECTION_TIMEOUT = 10 // seconds
        private const val KEEPALIVE_INTERVAL = 10 // seconds (faster offline detection)
        private const val QOS = 0 // Fire and forget
    }

    private var mqttClient: MqttClient? = null
    private var isConnected = false
    private var deviceId: String = ""
    private var deviceName: String = ""
    private var appVersion: String = ""

    init {
        deviceId = getOrCreateDeviceId()
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        appVersion = getAppVersion()
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = context.getSharedPreferences("mqtt_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString().take(12)
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    /**
     * Send flash operation statistic
     */
    fun sendFlashStats(event: String, firmwareType: String = "production", sessionId: String? = null) {
        sendStats("flash", event, firmwareType, sessionId)
    }

    /**
     * Send erase operation statistic
     */
    fun sendEraseStats(event: String, sessionId: String? = null) {
        sendStats("erase", event, "n/a", sessionId)
    }

    /**
     * Connect to MQTT with LWT and stay connected
     */
    fun connect() {
        try {
            if (isConnected) return

            val clientId = "pierre-stats-${deviceId}"
            mqttClient = MqttClient(BROKER_URL, clientId, MemoryPersistence())

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = CONNECTION_TIMEOUT
                keepAliveInterval = KEEPALIVE_INTERVAL // Faster offline detection
                userName = USERNAME
                password = PASSWORD.toCharArray()
                socketFactory = getSSLSocketFactory()

                // Set Last Will and Testament - automatic offline when connection lost
                setWill(
                    "$STATUS_TOPIC_PREFIX/$deviceId/offline",
                    "offline".toByteArray(),
                    1, // QoS
                    false // retain
                )
            }

            mqttClient?.connectWithResult(options)?.waitForCompletion(5000)

            if (mqttClient?.isConnected == true) {
                isConnected = true

                // Send online status immediately after connection
                val onlineMessage = MqttMessage("online".toByteArray()).apply {
                    qos = 1
                    isRetained = false
                }
                mqttClient?.publish("$STATUS_TOPIC_PREFIX/$deviceId/online", onlineMessage)
                Log.d("StatisticsManager", "Online status sent: pierre/status/$deviceId/online -> online")

                // Send device_online stats message with version info
                sendStats("device", "device_online", "n/a", null)

                Log.d("StatisticsManager", "Connected to MQTT with LWT")
            }

        } catch (e: Exception) {
            Log.e("StatisticsManager", "MQTT connection failed: ${e.message}")
            isConnected = false
        }
    }

    /**
     * Disconnect from MQTT
     */
    fun disconnect() {
        try {
            if (isConnected && mqttClient?.isConnected == true) {
                // Send manual offline before disconnect (optional, LWT will handle it anyway)
                val offlineMessage = MqttMessage("offline".toByteArray()).apply {
                    qos = 1
                    isRetained = false
                }
                mqttClient?.publish("$STATUS_TOPIC_PREFIX/$deviceId/offline", offlineMessage)

                mqttClient?.disconnect()
                mqttClient?.close()
            }
            isConnected = false
            Log.d("StatisticsManager", "Disconnected from MQTT")
        } catch (e: Exception) {
            Log.e("StatisticsManager", "MQTT disconnect failed: ${e.message}")
        }
    }

    private fun sendStats(operationType: String, event: String, firmwareType: String, sessionId: String?) {
        try {
            // Ensure we're connected
            if (!isConnected || mqttClient?.isConnected != true) {
                connect()
            }

            if (isConnected && mqttClient?.isConnected == true) {
                // Prepare statistics message
                val statsData = JSONObject().apply {
                    put("event", event)
                    put("device_name", deviceName)
                    put("firmware_type", firmwareType)
                    put("timestamp", System.currentTimeMillis())
                    put("session_id", sessionId ?: "unknown")
                    put("app_version", appVersion)
                }

                // Send message using persistent connection
                val topic = "$STATS_TOPIC_PREFIX/$deviceId/$operationType"
                val message = MqttMessage(statsData.toString().toByteArray()).apply {
                    qos = QOS
                    isRetained = false
                }

                mqttClient?.publish(topic, message)
                Log.d("StatisticsManager", "Stats sent: $topic -> $statsData")
            } else {
                Log.w("StatisticsManager", "Cannot send stats - MQTT not connected")
            }

        } catch (e: Exception) {
            Log.e("StatisticsManager", "Failed to send stats: ${e.message}")
            onError("Stats error: ${e.message}")
        }
    }


    /**
     * Create SSL Socket Factory for HiveMQ Cloud
     */
    private fun getSSLSocketFactory(): SSLSocketFactory {
        return try {
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