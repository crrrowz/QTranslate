# syntax=docker/dockerfile:1
FROM gradle:jdk21 AS builder

USER root

# Install native dependencies and JDK 11 (required by buildSrc toolchain)
RUN apt-get update && apt-get install -y --no-install-recommends \
    libx11-dev \
    libxtst-dev \
    pkg-config \
    fontconfig \
    libfreetype6 \
    libasound2t64 \
    openjdk-11-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

ENV GRADLE_OPTS="-Xmx3g -XX:MaxMetaspaceSize=768m -Dorg.gradle.daemon=false"
ENV _JAVA_OPTIONS="-Xmx3g -XX:MaxMetaspaceSize=768m"

COPY . .
RUN chmod +x gradlew || true

# Build portable and standalone jar distribution
RUN gradle assemblePortable assembleAppOnly --no-daemon

# Packager stage: uses Wine and Windows JDK to package QTranslate.exe
FROM gradle:jdk21 AS packager

USER root

RUN apt-get update && apt-get install -y --no-install-recommends \
    wine64 \
    wine \
    xvfb \
    curl \
    unzip \
    zip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /packager

# Download and extract Windows x64 Temurin JDK 21
RUN curl -fL --progress-bar "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk" -o /tmp/win-jdk.zip && \
    unzip -q /tmp/win-jdk.zip -d /opt && \
    mv /opt/jdk-21* /opt/win-jdk && \
    rm /tmp/win-jdk.zip

COPY --from=builder /app /app
COPY --from=builder /app/build/release/QTranslate-1.5.0.zip /packager/QTranslate-1.5.0.zip

# Package Windows app-image (QTranslate.exe) using package script logic under Wine
RUN bash -c 'set -e; \
    mkdir -p /packager/work/portable; \
    unzip -q /packager/QTranslate-1.5.0.zip -d /packager/work/portable; \
    DIST=/packager/work/portable/QTranslate; \
    mkdir -p /packager/work/package-input; \
    cp "$DIST/QTranslate.jar" /packager/work/package-input/QTranslate.jar; \
    \
    MODULES="java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.security.jgss,java.xml.crypto,jdk.charsets,jdk.unsupported,jdk.crypto.ec,jdk.localedata,jdk.zipfs"; \
    jlink --module-path /opt/win-jdk/jmods --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages --compress=2 --output /packager/work/runtime-image; \
    \
    mkdir -p /packager/work/app-image; \
    xvfb-run -a wine64 /opt/win-jdk/bin/jpackage.exe \
        --type app-image \
        --name QTranslate \
        --input "Z:\\packager\\work\\package-input" \
        --main-jar QTranslate.jar \
        --main-class com.github.ahatem.qtranslate.app.MainKt \
        --app-version "1.5.0" \
        --icon "Z:\\app\\ui-swing\\src\\main\\resources\\icons\\app\\icon.ico" \
        --runtime-image "Z:\\packager\\work\\runtime-image" \
        --dest "Z:\\packager\\work\\app-image" \
        --java-options "-Dfile.encoding=UTF-8"; \
    \
    IMAGE=/packager/work/app-image/QTranslate; \
    for dir in plugins languages themes icons LICENSES THIRD_PARTY_LICENSES; do \
        cp -r "$DIST/$dir" "$IMAGE/$dir"; \
    done; \
    for file in LICENSE NOTICE.md portable-plugin-ids.txt; do \
        cp "$DIST/$file" "$IMAGE/$file"; \
    done; \
    \
    mkdir -p /packager/output; \
    cd /packager/work/app-image && zip -q -r /packager/output/QTranslate-1.5.0-windows-x64.zip QTranslate; \
    cp -r /packager/work/app-image/QTranslate /packager/output/windows-app-image; \
    cp /packager/QTranslate-1.5.0.zip /packager/output/; \
    cp /app/build/release/QTranslate-App-1.5.0.jar /packager/output/; \
'

# Final export stage
FROM scratch AS artifacts
COPY --from=packager /packager/output/ /
