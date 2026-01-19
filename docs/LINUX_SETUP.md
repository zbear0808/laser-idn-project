# Linux / WSL Setup Guide

This guide covers setting up the laser-idn-project development environment on Linux or Windows Subsystem for Linux (WSL).

## 1. System Dependencies

Update your system and install required packages:

```bash
sudo apt update && sudo apt upgrade -y

# Core build tools
sudo apt install -y wget curl git build-essential

# JavaFX Linux dependencies
sudo apt install -y \
    libgtk-3-0 \
    libglu1-mesa \
    libgl1-mesa-dri \
    libgl1-mesa-glx \
    libasound2 \
    libavcodec-extra \
    libavformat-dev \
    libpango-1.0-0 \
    libpangocairo-1.0-0 \
    libxtst6 \
    libxxf86vm1 \
    xdg-utils

# MIDI support (for midi-clj)
sudo apt install -y libasound2-dev
```

## 2. Install JDK 25 (Early Access)

This project requires JDK 25 for JavaFX 26-ea support.

### Option A: Using SDKMAN (Recommended)

```bash
# Install SDKMAN
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install JDK 25 
sdk install java 25temurin

# Verify installation
java -version
# Should show: openjdk version "25" ...
```

## 3. Install Clojure CLI

```bash
# Download and run the installer
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh

# Verify installation
clojure --version
```

## 4. Environment Variables

Add to your `~/.bashrc` or `~/.zshrc`:

```bash
# If using SDKMAN
source "$HOME/.sdkman/bin/sdkman-init.sh"

# For display (WSL only)
export DISPLAY=:0
```

Reload your shell:
```bash
source ~/.bashrc
```

## 5. Clone and Run the Project

```bash
# Clone the repository
git clone https://github.com/zbear0808/laser-idn-project.git
cd laser-idn-project

# Download dependencies
clojure -P -M:dev:laser-show:linux

# Run the application
clojure -M:dev:laser-show:linux

# Run tests
clojure -M:test:linux

# Build uberjar
clojure -T:build:linux uber
```

---

## WSL-Specific Setup

### GUI Display 

To optimize WSLg, create `/etc/wsl.conf`:
```ini
[wsl2]
guiApplications=true
memory=8GB

[experimental]
gpuSupport=true
```

Then restart WSL:
```powershell
wsl --shutdown
wsl
```