# Pierre ESP32 Flasher - Statistics Dashboard

Private statistics dashboard for monitoring ESP32 flash/erase operations.

## Features

- **Real-time Statistics**: Live monitoring of flash and erase operations
- **Device Management**: Track multiple devices with their usage counts
- **Live Events**: Real-time event log showing all operations
- **Device Search**: Search and filter devices by name
- **Online Status**: Shows which devices are currently active
- **Mobile Responsive**: Works on all device sizes

## MQTT Integration

Uses the same HiveMQ Cloud infrastructure as the main serial viewer:

- **Broker**: `wss://0c1bf62a21e94682adf340b8a2d3fe04.s1.eu.hivemq.cloud:8884/mqtt`
- **Statistics Topic**: `pierre/stats/{deviceId}/{flash|erase}`
- **Device Info Topic**: `pierre/serial/{deviceId}/{sessionId}/info`

## Data Format

### Statistics Messages
```json
{
  "event": "flash_start|flash_success|flash_error|erase_start|erase_success|erase_error",
  "device_name": "Samsung Galaxy S23",
  "firmware_type": "production|custom",
  "timestamp": 1635789123000,
  "session_id": "abc12345"
}
```

### Device Info Messages
```
"Device Name|BatteryLevel"
```

## Usage

1. Open `index.html` in web browser
2. Dashboard automatically connects to MQTT broker
3. Real-time statistics appear as devices perform operations
4. Use search box to filter devices
5. Click refresh to manually update data

## Privacy

This dashboard is separate from the public serial viewer and should be kept private for internal monitoring only.