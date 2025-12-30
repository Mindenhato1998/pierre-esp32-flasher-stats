# 🚀 Pierre ESP32 Flash Counter - GitHub Pages Setup

## Teljes GitHub-alapú megoldás - Örökre ingyenes tárolás!

Ez a megoldás **GitHub Pages**-t használ a számláló tárolására. Teljesen ingyenes és örökre megmarad!

## 🏗️ Architektúra

```
Android App → MQTT → Bridge Script → GitHub Actions → GitHub Pages → Web Dashboard
```

## 📋 Telepítési lépések

### 1. Fork-old vagy töltsd fel a repository-t GitHub-ra

```bash
cd /home/testuser/Downloads/pierre-esp32-flasher-stats
git init
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/Mindenhato1998/pierre-esp32-flasher-stats.git
git push -u origin main
```

### 2. Engedélyezd a GitHub Pages-t

1. Menj a repository Settings-be
2. Baloldalon keresd meg a **Pages** menüpontot
3. Source: **Deploy from a branch**
4. Branch: **main** és **/docs** mappa
5. Kattints a **Save**-re

Pár perc múlva elérhető lesz:
```
https://mindenhato1998.github.io/pierre-esp32-flasher-stats/
```

### 3. Készíts GitHub Personal Access Token-t

1. GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
2. **Generate new token (classic)**
3. Név: `Pierre Flash Counter`
4. Jogosultságok: ✅ **repo** (minden repo jogosultság)
5. **Generate token**
6. **MÁSOLD KI A TOKEN-T!** (csak egyszer látod)

### 4. Állítsd be a repository secret-et

1. Repository Settings → Secrets and variables → Actions
2. **New repository secret**
3. Name: `PAT_TOKEN`
4. Value: A kimásolt token
5. **Add secret**

### 5. Indítsd el az MQTT bridge-et

**Linux/Mac:**
```bash
export GITHUB_TOKEN="ghp_xxxxxxxxxxxxxxxxxxxxx"  # A te tokened
export GITHUB_OWNER="Mindenhato1998"  # A te GitHub username-ed
export GITHUB_REPO="pierre-esp32-flasher-stats"  # A repo neve

python3 mqtt-to-github-actions.py
```

**Windows:**
```cmd
set GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxxx
set GITHUB_OWNER=Mindenhato1998
set GITHUB_REPO=pierre-esp32-flasher-stats

python mqtt-to-github-actions.py
```

### 6. Teszteld

1. Nyisd meg a web dashboard-ot:
   ```
   https://mindenhato1998.github.io/pierre-esp32-flasher-stats/
   ```

2. Flash-elj egy ESP32-t az Android app-pal

3. Nézd meg hogy növekszik-e a számláló

4. **Frissítsd az oldalt (F5)** - a számláló megmarad!

## 🎯 Előnyök

✅ **Teljesen ingyenes** - GitHub Pages örökre ingyenes
✅ **Örök tárolás** - amíg a GitHub létezik
✅ **Nincs lokális tárolás** - minden a GitHub-on van
✅ **Automatikus frissítés** - GitHub Actions kezeli
✅ **Publikus URL** - bárhonnan elérhető
✅ **Verziókövetés** - minden változás látható a commit history-ban

## 🔧 Hibakeresés

### A számláló nem frissül
1. Ellenőrizd hogy fut-e az MQTT bridge: `python3 mqtt-to-github-actions.py`
2. Nézd meg a GitHub Actions fület a repo-ban
3. Ellenőrizd a token jogosultságait

### Permission denied hiba
- Ellenőrizd hogy a workflow file-ban `permissions: contents: write` van-e

### A weboldal nem töltődik be
- Várj 5-10 percet a GitHub Pages aktiválása után
- Ellenőrizd a Settings → Pages oldalon az URL-t

## 📝 Működés részletei

1. **Android app** flash eseményt küld MQTT-n keresztül
2. **MQTT bridge script** fogadja és GitHub Actions-t triggerel
3. **GitHub Actions** frissíti a `docs/counter-data.json` fájlt
4. **GitHub Pages** automatikusan publikálja a változást
5. **Web dashboard** betölti a frissített JSON-t

## 🚨 Fontos

- A GitHub token-t SOHA ne oszd meg senkivel!
- A bridge script-nek futnia kell hogy működjön a frissítés
- Lehet hosztolni a bridge-et egy ingyenes VPS-en (pl. Oracle Cloud)

## 📊 Monitoring

- GitHub Actions runs: https://github.com/Mindenhato1998/pierre-esp32-flasher-stats/actions
- Commit history: https://github.com/Mindenhato1998/pierre-esp32-flasher-stats/commits/main
- Live dashboard: https://mindenhato1998.github.io/pierre-esp32-flasher-stats/

---

Ez a megoldás **100% felhő alapú** és **örökre ingyenes**! 🎉