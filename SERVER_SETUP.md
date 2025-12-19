# 🚀 24/7 Szerver Oldali MQTT Stats Collector

## 📋 Beállítási Útmutató

A GitHub Actions segítségével 24/7 szerver oldali megoldás, ami automatikusan gyűjti az ESP32 MQTT üzeneteket.

### 1. GitHub Secrets Beállítása

Menj a GitHub repository Settings → Secrets and variables → Actions oldalaira:

**Szükséges Secrets:**
```
HIVEMQ_USERNAME = pierreflasher
HIVEMQ_PASSWORD = Pierre2k23
```

### 2. Hogyan Működik

- **5 percenként** fut a GitHub Actions workflow
- **4 percig** hallgatja az MQTT üzeneteket
- **Automatikusan** frissíti a `stats-data.json` fájlt
- **24/7** működik, böngésző nem szükséges!

### 3. Workflow Fájlok

- `.github/workflows/mqtt-stats-collector.yml` - GitHub Actions workflow
- `.github/scripts/mqtt_collector.py` - Python MQTT kliens

### 4. Aktiválás

1. Commitold a fájlokat a repository-ba
2. Állítsd be a GitHub Secrets-ot
3. A workflow automatikusan elindul

### 5. Monitorozás

- GitHub → Actions → "ESP32 Stats Collector" workflow
- Logs-ban látod a működést:
  ```
  📨 [1] pierre/stats/test-device-123/flash: {"event":"flash_success"...}
  📊 Test Device Galaxy S21 flash count: 17
  ✅ Stats collection completed successfully
  ```

### 6. Előnyök

- ✅ **24/7 működés** - böngésző bezárva is
- ✅ **Automatikus** - nincs kézi beavatkozás
- ✅ **Megbízható** - GitHub szerverein fut
- ✅ **Ingyenes** - GitHub Actions korlátok alatt
- ✅ **Biztonságos** - titkosított credentialek

### 7. Költségek

- **GitHub Actions**: 2000 perc/hónap ingyenes
- **Workflow futás**: ~1 perc 5 percenként = 288 perc/nap
- **Havi használat**: ~8640 perc (túllépi az ingyenes keretet)

⚠️ **Figyelem**: Ez túllépi az ingyenes GitHub Actions límitet. Alternatíva: 15 perces cron schedule.

### 8. Optimalizált Verzió (15 perces)

A `.github/workflows/mqtt-stats-collector.yml` fájlban:
```yaml
schedule:
  - cron: '*/15 * * * *'  # 15 percenként
```

Ekkor: 96 perc/nap = ~2880 perc/hónap (belefér az ingyenes keretbe)

## 🎯 Összefoglalás

Most már **választhatsz**:
1. **Kliens oldali**: Weboldal nyitva kell legyen
2. **Szerver oldali**: 24/7 működés GitHub Actions-szel

A szerver oldali megoldás tökéletes ha nem akarod állandóan nyitva tartani a weboldalt!