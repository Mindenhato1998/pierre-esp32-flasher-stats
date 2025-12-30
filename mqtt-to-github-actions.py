#!/usr/bin/env python3
"""
MQTT to GitHub Actions Bridge
Listens to flash events and triggers GitHub Actions to update counter
"""

import json
import paho.mqtt.client as mqtt
import requests
from datetime import datetime
import os
import sys

# MQTT Configuration
MQTT_BROKER = "0c1bf62a21e94682adf340b8a2d3fe04.s1.eu.hivemq.cloud"
MQTT_PORT = 8883
MQTT_USERNAME = "pierreflasher"
MQTT_PASSWORD = "Pierre2k23"
MQTT_TOPIC = "pierre/flash/events/+"

# GitHub Configuration - MUST BE SET!
GITHUB_OWNER = os.getenv("GITHUB_OWNER", "Mindenhato1998")
GITHUB_REPO = os.getenv("GITHUB_REPO", "pierre-esp32-flasher-stats")
GITHUB_TOKEN = os.getenv("GITHUB_TOKEN")  # Personal Access Token with repo scope

if not GITHUB_TOKEN:
    print("❌ ERROR: GITHUB_TOKEN environment variable not set!")
    print("Please set it with: export GITHUB_TOKEN='your_token_here'")
    sys.exit(1)

def trigger_github_action(device_id, device_name, firmware_type):
    """Trigger GitHub Action via repository_dispatch"""

    url = f"https://api.github.com/repos/{GITHUB_OWNER}/{GITHUB_REPO}/dispatches"

    headers = {
        "Authorization": f"token {GITHUB_TOKEN}",
        "Accept": "application/vnd.github.v3+json"
    }

    payload = {
        "event_type": "update-flash-counter",
        "client_payload": {
            "deviceId": device_id,
            "deviceName": device_name,
            "firmwareType": firmware_type,
            "timestamp": datetime.utcnow().isoformat() + "Z"
        }
    }

    try:
        response = requests.post(url, headers=headers, json=payload)

        if response.status_code == 204:
            print(f"✅ GitHub Action triggered for {device_name}")
            return True
        else:
            print(f"❌ Failed to trigger Action: {response.status_code}")
            print(response.text)
            return False

    except Exception as e:
        print(f"❌ Error triggering GitHub Action: {e}")
        return False

def on_connect(client, userdata, flags, rc):
    """Callback when connected to MQTT"""
    if rc == 0:
        print("✅ Connected to HiveMQ Cloud")
        client.subscribe(MQTT_TOPIC)
        print(f"📡 Subscribed to {MQTT_TOPIC}")
    else:
        print(f"❌ Connection failed with code {rc}")

def on_message(client, userdata, msg):
    """Callback when MQTT message received"""
    try:
        # Parse flash event
        payload = msg.payload.decode('utf-8')
        data = json.loads(payload)

        device_id = data.get('deviceId', 'unknown')
        device_name = data.get('deviceName', 'Unknown Device')
        firmware_type = data.get('firmwareType', 'production')

        print(f"\n📨 Flash event from {device_name}")
        print(f"   Device ID: {device_id}")
        print(f"   Firmware: {firmware_type}")

        # Trigger GitHub Action
        success = trigger_github_action(device_id, device_name, firmware_type)

        if success:
            print("   → GitHub Pages will be updated in ~1 minute")

    except Exception as e:
        print(f"❌ Error processing message: {e}")

def main():
    """Main function"""
    print("🚀 MQTT to GitHub Actions Bridge")
    print(f"📊 GitHub Repo: {GITHUB_OWNER}/{GITHUB_REPO}")
    print(f"🔗 Monitoring: {MQTT_TOPIC}")
    print("=" * 50)

    # Create MQTT client
    client = mqtt.Client()
    client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
    client.tls_set()

    # Set callbacks
    client.on_connect = on_connect
    client.on_message = on_message

    # Connect
    print("🔌 Connecting to HiveMQ Cloud...")
    client.connect(MQTT_BROKER, MQTT_PORT, 60)

    # Start loop
    try:
        client.loop_forever()
    except KeyboardInterrupt:
        print("\n👋 Shutting down...")
        client.disconnect()

if __name__ == "__main__":
    main()