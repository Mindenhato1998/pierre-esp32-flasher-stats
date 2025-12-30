#!/usr/bin/env python3
"""Test flash event sender"""

import json
import paho.mqtt.client as mqtt
from datetime import datetime

# MQTT Configuration
MQTT_BROKER = "0c1bf62a21e94682adf340b8a2d3fe04.s1.eu.hivemq.cloud"
MQTT_PORT = 8883
MQTT_USERNAME = "pierreflasher"
MQTT_PASSWORD = "Pierre2k23"

# Create MQTT client
client = mqtt.Client()
client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
client.tls_set()

# Connect
print("Connecting to HiveMQ...")
client.connect(MQTT_BROKER, MQTT_PORT, 60)

# Create test flash event
test_event = {
    "deviceId": "cloud-test-001",
    "sessionId": "cloud-session-001",
    "deviceName": "Cloud Test Device",
    "firmwareType": "production",
    "flashSize": 1048576,
    "flashTime": 15000,
    "timestamp": datetime.now().isoformat(),
    "androidVersion": "14"
}

# Publish event
topic = f"pierre/flash/events/{test_event['deviceId']}"
payload = json.dumps(test_event)

print(f"Sending test flash event to {topic}")
info = client.publish(topic, payload, qos=1)
info.wait_for_publish()

print("✅ Test flash event sent!")
print(json.dumps(test_event, indent=2))

client.disconnect()