package com.smartpierre.espflasher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.content.Context
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

class SerialMonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "serial_monitor_channel"
        const val NOTIFICATION_ID = 1
        var isRunning = false

        // Static reference to maintain USB connection across screen locks
        var usbConnection: UsbDeviceConnection? = null
        var connectedDevice: UsbDevice? = null
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var usbManager: UsbManager
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Create strongest possible wake lock to keep USB and CPU active during flashing
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "PierreFlasher::ServiceWakeLock"
        )

        // Initialize USB manager
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        isRunning = true
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Acquire wake lock to prevent doze mode during USB operations
        if (!wakeLock.isHeld) {
            wakeLock.acquire(30*60*1000L /*30 minutes max*/)
        }

        // Monitor and maintain USB connection
        maintainUsbConnection()

        // Keep service running
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()

        // Release wake lock when service stops
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }

        isRunning = false
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Serial Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps serial monitoring active in background"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun maintainUsbConnection() {
        // Keep reference to USB devices to prevent garbage collection
        val devices = usbManager.deviceList
        for ((_, device) in devices) {
            if (usbManager.hasPermission(device)) {
                try {
                    // Maintain connection reference in foreground service
                    // BUT don't claim interfaces - let UsbSerialManager handle that
                    if (usbConnection == null || connectedDevice != device) {
                        usbConnection?.close()
                        usbConnection = usbManager.openDevice(device)
                        connectedDevice = device

                        // Just keep the raw connection alive - NO interface claiming
                        // NO bulk transfers or interface operations
                        usbConnection?.let { connection ->
                            // Simple keep-alive mechanism without interface operations
                            Thread {
                                while (isRunning && usbConnection != null) {
                                    try {
                                        Thread.sleep(30000) // 30 second intervals
                                        // Just verify connection is still valid
                                        // No bulk transfers - those require interface claim
                                        if (usbConnection == null) {
                                            break
                                        }
                                    } catch (e: Exception) {
                                        // Connection lost, attempt reconnect
                                        break
                                    }
                                }
                            }.start()
                        }
                    }
                } catch (e: Exception) {
                    // Connection failed, will retry next time
                }
            }
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pierre Flasher")
            .setContentText("USB connection protected - Screen lock safe")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}