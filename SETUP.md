# Pierre ESP32 Flash Counter - GitHub Gist Setup

## Overview
This system uses GitHub Gist as a persistent storage for the ESP32 flash counter data. The architecture consists of:

1. **Android App** → Sends flash events via MQTT
2. **Web Dashboard** → Reads counter from GitHub Gist
3. **MQTT Bridge** → Listens to events and updates GitHub Gist via Actions

## Setup Instructions

### Step 1: Create a GitHub Gist

1. Go to https://gist.github.com/
2. Create a new public Gist with filename `flash-counter.json`
3. Add this initial content:
```json
{
  "totalFlashes": 0,
  "devices": {},
  "sessions": [],
  "todayFlashes": 0,
  "todayDate": "",
  "recentEvents": [],
  "lastUpdate": ""
}
```
4. Save the Gist and copy its ID from the URL
   - Example URL: `https://gist.github.com/Mindenhato1998/abc123def456`
   - Gist ID: `abc123def456`

### Step 2: Create GitHub Personal Access Token

1. Go to GitHub Settings → Developer settings → Personal access tokens
2. Generate new token (classic) with `gist` scope
3. Save this token securely

### Step 3: Configure the Web Dashboard

1. Edit `index.html`
2. Replace `YOUR_GIST_ID_HERE` with your actual Gist ID:
```javascript
const GIST_ID = 'abc123def456'; // Your actual Gist ID
```

### Step 4: Set up GitHub Repository Secrets

1. In your repository settings, go to Secrets and variables → Actions
2. Add these secrets:
   - `GIST_TOKEN`: Your GitHub personal access token
   - `GIST_ID`: Your Gist ID

### Step 5: Deploy the MQTT Bridge

Option A: Run locally:
```bash
# Set environment variable
export GITHUB_TOKEN="your_github_token_here"

# Install dependencies
pip install paho-mqtt requests

# Run the bridge
python mqtt_to_github.py
```

Option B: Deploy on a server (recommended):
```bash
# Create systemd service
sudo nano /etc/systemd/system/mqtt-bridge.service

[Unit]
Description=MQTT to GitHub Bridge for Pierre Flash Counter
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/pierre-counter
Environment="GITHUB_TOKEN=your_token_here"
ExecStart=/usr/bin/python3 /home/ubuntu/pierre-counter/mqtt_to_github.py
Restart=always

[Install]
WantedBy=multi-user.target

# Enable and start
sudo systemctl enable mqtt-bridge
sudo systemctl start mqtt-bridge
```

### Step 6: Test the System

1. Flash an ESP32 device using the Android app
2. Check the MQTT bridge logs for flash events
3. Verify the Gist is updated at https://gist.github.com/Mindenhato1998/YOUR_GIST_ID
4. Open the web dashboard and verify counter displays correctly

## Architecture

```
┌─────────────────┐
│  Android App    │
│  (Publisher)    │
└────────┬────────┘
         │
         │ MQTT Flash Event
         ▼
┌─────────────────┐
│  HiveMQ Cloud   │
│  (MQTT Broker)  │
└────────┬────────┘
         │
         │ Subscribe
         ▼
┌─────────────────┐
│  MQTT Bridge    │
│  (Python Script)│
└────────┬────────┘
         │
         │ GitHub API
         ▼
┌─────────────────┐      ┌─────────────────┐
│  GitHub Action  │◄─────│  GitHub Gist    │
│  (Update Gist)  │      │  (Storage)      │
└─────────────────┘      └────────┬────────┘
                                  │
                                  │ HTTPS GET
                                  ▼
                         ┌─────────────────┐
                         │  Web Dashboard  │
                         │  (Display)      │
                         └─────────────────┘
```

## Monitoring

### Check MQTT Bridge Status
```bash
# If running as systemd service
sudo systemctl status mqtt-bridge
sudo journalctl -u mqtt-bridge -f

# If running locally
# Check console output
```

### Verify Gist Updates
- Visit: https://gist.github.com/Mindenhato1998/YOUR_GIST_ID
- Check revision history for recent updates

### Dashboard
- Open index.html in browser
- Counter should persist across refreshes
- Updates appear in real-time

## Troubleshooting

### Counter resets on refresh
- Check Gist ID is correct in index.html
- Verify Gist is public and accessible
- Check browser console for fetch errors

### MQTT events not updating Gist
- Verify GITHUB_TOKEN environment variable is set
- Check bridge is running and connected to HiveMQ
- Verify GitHub token has `gist` scope

### GitHub Action fails
- Check repository secrets are configured
- Verify workflow file syntax
- Check Actions tab for error logs

## Security Notes

- Keep GitHub token secure and never commit it
- Use environment variables or secrets management
- Consider IP whitelisting for production deployment
- Monitor Gist access logs for unusual activity

## Support

For issues or questions:
- Check MQTT bridge logs first
- Verify all configuration values
- Test each component independently
- Create issue in repository if needed