name: Build MotoIntercom APK

on:
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-24.04

    steps:
      # Обновлено с v4 до v5. 
      # Теперь действие официально работает на Node.js 24 и выше.
      - name: Checkout project
        uses: actions/checkout@v5

      # Обновлено с v4 до v5. 
      # Исправляет предупреждение об устаревании setup-java.
      - name: Setup Java 17
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      # Настройка Android SDK остается прежней, она не зависит от Node.js
      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          api-level: 34
          build-tools: 34.0.0

      # Принудительный запуск Gradle на Java 17 через Wrapper
      # (опционально, но рекомендуется для изоляции)
      - name: Ensure Gradle uses Java 17
        run: |
          echo "org.gradle.java.home=$JAVA_HOME" >> "$GITHUB_ENV"

      - name: Build APK
        run: ./gradlew :app:assembleDebug --stacktrace --no-daemon

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: MotoIntercom-APK
          path: '**/outputs/apk/debug/*.apk'
          if-no-files-found: error
