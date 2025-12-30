#!/usr/bin/env python3
"""
GitHub Gist updater for Pierre Flash Counter
Listens to MQTT messages and updates GitHub Gist
"""

import json
import time
import paho.mqtt.client as mqtt
import requests
from datetime import datetime

# Configuration
MQTT_BROKER = "0c1bf62a21e94682adf340b8a2d3fe04.s1.eu.hivemq.cloud"
MQTT_PORT = 8883
MQTT_USERNAME = "pierreflasher"
MQTT_PASSWORD = "Pierre2k23!"
MQTT_TOPIC = "pierre/flash/update"

# GitHub Gist configuration (you need to create a personal access token)
GIST_ID = "GIST_ID_HERE"  # Replace with your Gist ID
GITHUB_TOKEN = "YOUR_GITHUB_TOKEN"  # Replace with your GitHub token
GIST_FILENAME = "flash-counter.json"

def update_gist(data):
    """Update GitHub Gist with new counter data"""
    try:
        url = f"https://api.github.com/gists/{GIST_ID}"
        headers = {
            "Authorization": f"token {GITHUB_TOKEN}",
            "Accept": "application/vnd.github.v3+json"
        }

        payload = {
            "files": {
                GIST_FILENAME: {
                    "content": json.dumps(data, indent=2)
                }
            }
        }

        response = requests.patch(url, headers=headers, json=payload)

        if response.status_code == 200:
            print(f"✅ Gist updated successfully at {datetime.now()}")
            return True
        else:
            print(f"❌ Failed to update Gist: {response.status_code}")
            print(response.text)
            return False

    except Exception as e:
        print(f"❌ Error updating Gist: {e}")
        return False

def on_connect(client, userdata, flags, rc):
    """Callback when connected to MQTT broker"""
    if rc == 0:
        print("✅ Connected to HiveMQ")
        client.subscribe(MQTT_TOPIC)
        print(f"📡 Subscribed to {MQTT_TOPIC}")
    else:
        print(f"❌ Connection failed with code {rc}")

def on_message(client, userdata, msg):
    """Callback when MQTT message received"""
    try:
        payload = msg.payload.decode('utf-8')
        data = json.loads(payload)

        print(f"📨 Received update request: {data.get('totalFlashes', 0)} flashes")

        # Update the Gist
        if update_gist(data):
            print("✨ Flash counter synced to GitHub Gist")

    except Exception as e:
        print(f"❌ Error processing message: {e}")

def main():
    """Main function"""
    print("🚀 Starting GitHub Gist updater for Pierre Flash Counter")
    print(f"📊 Gist ID: {GIST_ID}")
    print(f"🔗 Monitoring topic: {MQTT_TOPIC}")

    # Create MQTT client
    client = mqtt.Client()
    client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
    client.tls_set()

    # Set callbacks
    client.on_connect = on_connect
    client.on_message = on_message

    # Connect to broker
    print("🔌 Connecting to HiveMQ...")
    client.connect(MQTT_BROKER, MQTT_PORT, 60)

    # Start loop
    try:
        client.loop_forever()
    except KeyboardInterrupt:
        print("\n👋 Shutting down...")
        client.disconnect()

if __name__ == "__main__":
    main()