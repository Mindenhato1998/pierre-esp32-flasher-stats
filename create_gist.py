#!/usr/bin/env python3
"""
Create a GitHub Gist for Pierre Flash Counter
This will create a public Gist that can be read without authentication
"""

import json
import requests

def create_gist():
    # Initial counter data
    counter_data = {
        "totalFlashes": 0,
        "devices": {},
        "sessions": [],
        "todayFlashes": 0,
        "todayDate": "",
        "recentEvents": [],
        "lastUpdate": ""
    }

    # Create public Gist (no auth needed for public gists)
    gist_data = {
        "description": "Pierre ESP32 Flash Counter - Global Statistics",
        "public": True,
        "files": {
            "flash-counter.json": {
                "content": json.dumps(counter_data, indent=2)
            }
        }
    }

    # Create the Gist
    response = requests.post(
        "https://api.github.com/gists",
        json=gist_data,
        headers={"Accept": "application/vnd.github.v3+json"}
    )

    if response.status_code == 201:
        gist_info = response.json()
        gist_id = gist_info['id']
        gist_url = gist_info['html_url']
        raw_url = gist_info['files']['flash-counter.json']['raw_url']

        print("✅ GitHub Gist created successfully!")
        print(f"📋 Gist ID: {gist_id}")
        print(f"🔗 Gist URL: {gist_url}")
        print(f"📥 Raw URL: {raw_url}")

        return gist_id, gist_url, raw_url
    else:
        print(f"❌ Failed to create Gist: {response.status_code}")
        print(response.text)
        return None, None, None

if __name__ == "__main__":
    gist_id, gist_url, raw_url = create_gist()

    if gist_id:
        print("\n📝 Next steps:")
        print(f"1. Update index.html with GIST_ID = '{gist_id}'")
        print(f"2. The counter is now available at: {raw_url}")