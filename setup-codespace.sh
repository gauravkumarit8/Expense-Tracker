#!/usr/bin/env bash
# Run this once inside your GitHub Codespace to get an Android build
# environment ready. See REQUIREMENTS.md "Dev Environment Setup" for the
# manual step-by-step version of what this script does.
set -e

echo "== 1. Installing JDK 17 =="
sudo apt-get update -y
sudo apt-get install -y openjdk-17-jdk unzip

echo "== 2. Installing Android command-line tools =="
export ANDROID_HOME="$HOME/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME/cmdline-tools"
curl -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q cmdline-tools.zip
mv cmdline-tools latest
rm cmdline-tools.zip

echo "== 3. Setting environment variables =="
echo "export ANDROID_HOME=$ANDROID_HOME" >> ~/.bashrc
echo "export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools" >> ~/.bashrc
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

echo "== 4. Accepting licenses + installing platform/build-tools =="
yes | sdkmanager --licenses > /dev/null
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "== 5. Done. Restart your shell or run: source ~/.bashrc =="
