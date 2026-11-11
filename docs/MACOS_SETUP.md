# macOS Setup Guide

This guide covers setting up the development environment for **Laser Show** (`laser-idn-project`) on macOS, covering both Apple Silicon (M1/M2/M3/M4/etc.) and Intel Macs.

---

## 1. Prerequisites & Architecture Check

First, determine whether your Mac runs Apple Silicon (`arm64`) or Intel (`x86_64`):

```bash
uname -m
```

- If output is `arm64`: You have an **Apple Silicon Mac** (use `:mac-arm` platform alias in Clojure commands).
- If output is `x86_64`: You have an **Intel Mac** (use `:mac` platform alias in Clojure commands).

Make sure [Homebrew](https://brew.sh/) is installed:

```bash
brew --version
```
If not installed, install Homebrew via:
```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

---

## 2. JDK 25 Installation

This project requires **JDK 25** because it uses JavaFX early-access build `28-ea+11` (see `deps.edn` and `.github/workflows/build-and-test.yml`). Earlier Java versions (JDK 17, 21, etc.) will fail to load the JavaFX 28-ea modules.

You have two main installation options on macOS:

### Option A: Homebrew OpenJDK 25 (Recommended — No Sudo Required)

Homebrew distributes OpenJDK 25 as a formula bottle into `/opt/homebrew` (on Apple Silicon) or `/usr/local` (on Intel) without requiring administrator/root privileges:

```bash
brew install openjdk@25
```

Because `openjdk@25` is keg-only, add it to your `PATH` and set `JAVA_HOME`:

```bash
# Add to ~/.zshrc (or ~/.bash_profile if using bash)
echo 'export PATH="/opt/homebrew/opt/openjdk@25/bin:$PATH"' >> ~/.zshrc
echo 'export JAVA_HOME="/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"' >> ~/.zshrc
```

*(Note for Intel Macs: replace `/opt/homebrew` with `/usr/local`).*

Apply to your active terminal session:
```bash
export PATH="/opt/homebrew/opt/openjdk@25/bin:$PATH"
export JAVA_HOME="/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home"
```

Optional (if you want the macOS system wrapper `/usr/libexec/java_home` to discover it globally):
```bash
sudo ln -sfn /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-25.jdk
```

---

### Option B: Eclipse Temurin 25 (Adoptium Cask)

If you prefer the official Adoptium / Eclipse Temurin build used in the GitHub Actions CI workflow:

```bash
brew install --cask temurin@25
```

*Note: Since this installs an official macOS installer package (`.pkg`) to `/Library/Java/JavaVirtualMachines/`, macOS will prompt for your administrator password.*

Configure your shell profile:
```bash
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 25)' >> ~/.zshrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

---

### Option C: Using SDKMAN

If you manage multiple SDK versions with [SDKMAN!](https://sdkman.io):

```bash
# Install SDKMAN (if not already installed)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# Install JDK 25
sdk install java 25temurin
```

---

### Verify Java Installation

Check that your active Java runtime is version 25:

```bash
java -version
```

Expected output:
```text
openjdk version "25..." ...
OpenJDK Runtime Environment ...
OpenJDK 64-Bit Server VM ...
```

And verify `JAVA_HOME`:
```bash
echo $JAVA_HOME
```

---

## 3. Install Clojure CLI

The Clojure core team recommends using the official Clojure Homebrew tap:

```bash
# Tap the official Clojure tools repository
brew tap clojure/tools

# Install clojure CLI and rlwrap
brew install clojure/tools/clojure
```

> **Why `clojure/tools/clojure` instead of `brew install clojure`?**  
> The default `homebrew-core` `clojure` formula declares a hard dependency on Homebrew's default OpenJDK (which may pull down Java 27+). Using `clojure/tools/clojure` installs the official CLI tools without overriding your configured JDK 25 runtime.

### Verify Clojure Installation

Verify both the `clojure` and `clj` commands:

```bash
clojure --version
clj --version
```

Expected output:
```text
Clojure CLI version 1.12.x...
```

You can test launching the Clojure REPL:
```bash
clj
```
Type `(exit)` or press `Ctrl+D` to exit.

---

## 4. Running the Project on macOS

### Platform Aliases

JavaFX requires platform-specific native libraries:
- **Apple Silicon (M1/M2/M3/M4):** use `:mac-arm`
- **Intel Mac (x86_64):** use `:mac`

### 1. Pre-fetch Dependencies

Before running for the first time, download dependencies:

```bash
# Apple Silicon
clj -P -M:dev:laser-show:mac-arm

# Intel Mac
clj -P -M:dev:laser-show:mac
```

### 2. Launch the Application

```bash
# Apple Silicon
clj -M:dev:laser-show:mac-arm

# Intel Mac
clj -M:dev:laser-show:mac
```

Alternatively, to run the standalone GUI without development reloaders:
```bash
# Apple Silicon
clj -M:mac-arm:laser-show

# Intel Mac
clj -M:mac:laser-show
```

### 3. Run Unit & Integration Tests

Run unit tests (excluding UI tests and timing tests):
```bash
# Apple Silicon
clj -M:mac-arm:test

# Intel Mac
clj -M:mac:test
```

Run a specific test namespace:
```bash
clj -M:mac-arm:test -n laser-show.events.grid-handlers-test
```

Run integration tests:
```bash
clj -M:mac-arm:test-integration
```

### 4. Build Standalone Uberjar

To build a standalone distributable JAR for macOS:

```bash
# Apple Silicon
clj -T:build uber :platform :mac-arm

# Intel Mac
clj -T:build uber :platform :mac
```

The resulting JAR is placed at:
```text
target/laser-show-<version>-standalone.jar
```

Run the built JAR:
```bash
java -jar target/laser-show-*-standalone.jar
```

---

## 5. VS Code + Calva Setup (macOS)

1. Install **VS Code** and the **Calva** extension (`betterthantomorrow.calva`).
2. Open the repository root folder in VS Code.
3. Open the Command Palette (`Cmd+Shift+P`).
4. Run: `Calva: Start a Project REPL and Connect (Jack-In)`.
5. Select `deps.edn` as project type.
6. When prompted for aliases:
   - On Apple Silicon, select `:dev`, `:laser-show`, and `:mac-arm`.
   - On Intel Macs, select `:dev`, `:laser-show`, and `:mac`.
7. Once connected, open `dev/user.clj` and run:
   ```clojure
   (start)
   ```

---

## 6. macOS Troubleshooting

### "clj: command not found" or "clojure: command not found"
Ensure `/opt/homebrew/bin` (Apple Silicon) or `/usr/local/bin` (Intel) is in your `PATH`:
```bash
echo 'export PATH="/opt/homebrew/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

### "Unsupported class file major version" / JavaFX initialization failure
This error indicates an incompatible JDK is being invoked instead of JDK 25. Check:
```bash
which java
java -version
```
Make sure `JAVA_HOME` points to JDK 25, and that JDK 25 appears first in your `PATH`.

### MIDI Devices on macOS
macOS natively supports CoreMIDI via the Java Sound API (`javax.sound.midi`). Unlike Linux, you do not need `alsa-utils` or additional drivers. If MIDI devices do not appear, ensure:
1. The MIDI controller is connected via USB and recognized in macOS `Audio MIDI Setup` (Applications → Utilities → Audio MIDI Setup).
2. The laser show app is granted necessary input permissions if prompted.

### Gamepad Input on macOS
The project uses `Jamepad` (`libjamepad`), which includes pre-compiled `.dylib` natives for macOS arm64 and x86_64. If macOS displays a security prompt regarding `libjamepad`:
1. Open **System Settings** → **Privacy & Security**.
2. Scroll to the **Security** section and click **Allow Anyway** if a blocked library is noted.

### Ableton Link Network Broadcasts
Ableton Link communication via Carabiner uses UDP broadcast and multicast on your local network. When first launching, macOS may ask:
> *"laser-show / java would like to find and connect to devices on your local network"*
Click **Allow** to enable Ableton Link synchronization.
