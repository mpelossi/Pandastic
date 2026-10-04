# syntax=docker/dockerfile:1.7
# Targets (README.md, Run it):
#   web    browser phone pair: both phones on 8080 (Basic 5173, Capable 5174)    docker compose up
#   brain  the helper phone's Java brain + Qwen + leaf model, used by web         docker compose up
#   apk    debug APK built with the pinned Android toolchain                      docker build --target apk --output out .

# ---------- web: the browser phone pair ----------
FROM node:24-bookworm-slim AS web
RUN corepack enable
WORKDIR /app/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store CI=true corepack pnpm install --frozen-lockfile
COPY frontend/ ./
# The local phones answer from the same knowledge base as the app.
COPY android/app/src/main/assets/models/knowledge.sqlite /app/android/app/src/main/assets/models/knowledge.sqlite
ENV PANDASTIC_HOST=0.0.0.0 BASIC_PORT=5173 CAPABLE_PORT=5174 PANDASTIC_DEMO_PORT=8080 PANDASTIC_PHONE_STATE_DIR=/state
VOLUME /state
EXPOSE 8080 5173 5174
CMD ["node", "local/run-pair.mjs"]

# ---------- brain: the phone's own Java brain on the JVM (desktop/) ----------
FROM eclipse-temurin:17-jdk-jammy AS brain-build
RUN apt-get update && apt-get install -y --no-install-recommends cmake g++ make git ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build
RUN mkdir -p /opt/pandastic/lib /opt/pandastic/native && cd /opt/pandastic/lib \
    && for jar in com/microsoft/onnxruntime/onnxruntime/1.30.0/onnxruntime-1.30.0.jar \
                  org/xerial/sqlite-jdbc/3.50.3.0/sqlite-jdbc-3.50.3.0.jar org/json/json/20250517/json-20250517.jar; do \
         curl -fsSLO https://repo1.maven.org/maven2/$jar; done
# llama.cpp JNI: android/app/src/main/cpp/llm_jni.cpp unchanged, with desktop/jni/android/log.h.
COPY desktop/jni/ jni/
COPY android/app/src/main/cpp/llm_jni.cpp cpp/
RUN cmake -S jni -B jni-build -DPANDASTIC_CPP=/build/cpp >/dev/null && cmake --build jni-build -j"$(nproc)" >/dev/null \
    && cp jni-build/libpandastic_llm.so /opt/pandastic/native/
# The brain classes exactly as in the APK, plus the Android shims and the HTTP wrapper.
COPY android/app/src/main/java/org/pandastic/relay/ java/
COPY desktop/shim/ shim/
COPY desktop/src/ src/
RUN javac --release 17 -encoding UTF-8 -d classes -cp "/opt/pandastic/lib/*" $(find shim src -name '*.java') \
        $(ls java/brain/*.java | grep -v SqliteKnowledge) java/ReplyWriter.java java/hub/HubPolicy.java \
    && jar --create --file /opt/pandastic/lib/pandastic-brain.jar -C classes .

# The fine-tuned Qwen the app downloads (ml/llm/model.json: GitHub release models-v1, size + SHA-256 checked).
FROM alpine:3.20 AS qwen
RUN apk add --no-cache curl jq
COPY ml/llm/model.json /model.json
RUN name=$(jq -r .name /model.json) && mkdir /qwen \
    && curl -fL --retry 3 -o "/qwen/$name" "$(jq -r .url /model.json)" \
    && [ "$(stat -c %s "/qwen/$name")" = "$(jq -r .bytes /model.json)" ] \
    && echo "$(jq -r .sha256 /model.json)  /qwen/$name" | sha256sum -c -

FROM eclipse-temurin:17-jre-jammy AS brain
COPY --from=qwen /qwen/ /opt/pandastic/files/models/
COPY --from=brain-build /opt/pandastic/ /opt/pandastic/
COPY android/app/src/main/assets/models/ /opt/pandastic/assets/models/
COPY ml/llm/slots.gbnf ml/llm/system_prompt.txt ml/llm/model.json /opt/pandastic/assets/llm/
ENV PANDASTIC_ASSETS=/opt/pandastic/assets PANDASTIC_FILES=/opt/pandastic/files PANDASTIC_BRAIN_PORT=8090
EXPOSE 8090
HEALTHCHECK --interval=3s --timeout=2s --start-period=60s --retries=40 CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8090'
CMD ["java", "-Djava.awt.headless=true", "-Djava.library.path=/opt/pandastic/native", "-cp", "/opt/pandastic/lib/*", \
     "org.pandastic.relay.desktop.BrainServer"]

# ---------- android-build: SDK 35, NDK 28.2, CMake 3.22.1, JDK 17, Node 24 ----------
FROM eclipse-temurin:17-jdk-jammy AS android-build
RUN apt-get update && apt-get install -y --no-install-recommends git unzip ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=web /usr/local/bin/node /usr/local/bin/node
COPY --from=web /usr/local/lib/node_modules/corepack /usr/local/lib/node_modules/corepack
RUN ln -s ../lib/node_modules/corepack/dist/corepack.js /usr/local/bin/corepack && corepack enable
ENV ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
ARG CMDLINE_TOOLS=13114758
RUN curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip \
    && mkdir -p $ANDROID_HOME/cmdline-tools && unzip -q /tmp/tools.zip -d $ANDROID_HOME/cmdline-tools \
    && mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest && rm /tmp/tools.zip \
    && yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null \
    && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install \
       "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;28.2.13676358" "cmake;3.22.1" >/dev/null
WORKDIR /src
COPY . .
# assembleDebug = arm64 + x86_64 (phones and emulators). GRADLE_ARGS="-PphoneOnly assembleRelease" for the phone-only build.
ARG GRADLE_ARGS=assembleDebug
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/root/.local/share/pnpm/store \
    cd android && ./gradlew --no-daemon $GRADLE_ARGS \
    && mkdir -p /out && find app/build/outputs/apk -name '*.apk' -exec cp {} /out/ \;

# ---------- apk: just the files, for --output ----------
FROM scratch AS apk
COPY --from=android-build /out/ /
