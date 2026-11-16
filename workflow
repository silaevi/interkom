name: Build MotoIntercom APK

on:
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-24.04

    steps:
      # ОБНОВЛЕНО: с v4 до v5
      - name: Checkout project
        uses: actions/checkout@v5

      # ОБНОВЛЕНО: с v4 до v5
      - name: Setup Java 17
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          api-level: 34
          build-tools: 34.0.0

      - name: Accept Android SDK licenses
        run: |
          yes | sdkmanager --licenses || test "${PIPESTATUS[0]}" -eq 141

      - name: Install Android SDK 34
        run: |
          sdkmanager "platforms;android-34" "build-tools;34.0.0"

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Build APK
        run: ./gradlew :app:assembleDebug --stacktrace --no-daemon

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: MotoIntercom-APK
          path: '**/outputs/apk/debug/*.apk'
          if-no-files-found: error
