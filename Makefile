ANDROID_DIR := android
.DEFAULT_GOAL := run
PACKAGE_NAME ?= org.pandastic.relay

# Prefer an explicitly provided SDK path, then the project's local.properties.
ifeq ($(strip $(SDK_DIR)),)
SDK_DIR := $(or $(strip $(ANDROID_HOME)),$(strip $(ANDROID_SDK_ROOT)),$(shell sed -n 's/^sdk.dir=//p' $(ANDROID_DIR)/local.properties 2>/dev/null),$(HOME)/Android/Sdk)
endif
ifeq ($(strip $(ADB)),)
ADB := $(if $(wildcard $(SDK_DIR)/platform-tools/adb),$(SDK_DIR)/platform-tools/adb,adb)
endif
ifeq ($(strip $(EMULATOR)),)
EMULATOR := $(if $(wildcard $(SDK_DIR)/emulator/emulator),$(SDK_DIR)/emulator/emulator,emulator)
endif
EMULATOR_NAME ?=
BOOT_TIMEOUT ?= 180
EMULATOR_LOG ?= /tmp/pandastic-emulator.log
DEVICE ?=
APK := $(ANDROID_DIR)/app/build/outputs/apk/debug/app-debug.apk
BUILD_APK = (cd "$(ANDROID_DIR)" && ./gradlew assembleDebug)
EMULATOR_TARGET = $(if $(DEVICE),-s "$(DEVICE)",-e)
PHONE_TARGET = $(if $(DEVICE),-s "$(DEVICE)",-d)
EMULATOR_OPTIONS = --adb "$(ADB)" --emulator "$(EMULATOR)" --sdk "$(SDK_DIR)" --name "$(EMULATOR_NAME)" --device "$(DEVICE)" --timeout "$(BOOT_TIMEOUT)" --log "$(EMULATOR_LOG)"

.PHONY: run run-device build release web stop reset-state e2e emulator-basic sms-setup sms-relay sms-phone sms-test human-test

# Gradle also builds React and bundles it into the APK.
build:
	@$(BUILD_APK)

# Side-loadable phone APK (arm64 only, release-optimised). The 542 MB LLM is downloaded in the app or imported.
release:
	@cd $(ANDROID_DIR) && ./gradlew -PphoneOnly assembleRelease
	@ls -lh $(ANDROID_DIR)/app/build/outputs/apk/release/app-release.apk

# Quick UI development in the computer's browser.
web:
	@cd frontend && $(if $(shell command -v corepack 2>/dev/null),corepack pnpm,pnpm) run dev

# Run on an emulator. Reuse an existing emulator when possible.
run:
	@set -eu; \
	serial=$$(bash scripts/android-emulator.sh start $(EMULATOR_OPTIONS)); \
	$(BUILD_APK); \
	"$(ADB)" -s "$$serial" install -r "$(APK)"; \
	"$(ADB)" -s "$$serial" shell am start -W -S -n "$(PACKAGE_NAME)/.FrontendActivity"

# Run on one USB-connected Samsung/Android phone. DEVICE selects a specific serial.
run-device:
	@command -v "$(ADB)" >/dev/null 2>&1 || { echo "adb not found. Add Android SDK platform-tools to PATH." >&2; exit 1; }
	@"$(ADB)" $(PHONE_TARGET) get-state >/dev/null 2>&1 || { echo "No single authorized phone found. Enable USB debugging, accept the phone's USB prompt, and check adb devices. With multiple phones use make run-device DEVICE=YOUR_SERIAL." >&2; exit 1; }
	@$(BUILD_APK)
	@"$(ADB)" $(PHONE_TARGET) install -r "$(APK)"
	@"$(ADB)" $(PHONE_TARGET) shell am start -S -n "$(PACKAGE_NAME)/.FrontendActivity"

stop:
	@bash scripts/android-emulator.sh stop $(EMULATOR_OPTIONS)

# Restore the app to a first-run state on the emulator, or DEVICE=<serial>.
# This clears app-private data, including settings, chat history and imported model files.
reset-state:
	@set -eu; \
	printf 'This clears all Pandastic app data (settings, chats and imported models) on %s.\n' '$(if $(DEVICE),$(DEVICE),the emulator)'; \
	printf 'Type reset to continue: '; \
	read -r answer; \
	if [ "$$answer" != reset ]; then echo 'Reset cancelled.'; exit 1; fi; \
	"$(ADB)" $(if $(DEVICE),-s "$(DEVICE)",-e) shell pm clear "$(PACKAGE_NAME)"

# Tests every UI <-> native connector inside the running debug app (emulator or phone): bridge methods,
# questions, photos, hub settings, SMS round trip. With two devices attached, pass DEVICE=<serial>.
# Extra flags: make e2e ARGS="--photo leaf.jpg"
e2e:
	@ADB="$(ADB)" DEVICE="$(DEVICE)" node scripts/bridge-e2e.mjs --sms $(ARGS)

# SMS lab: helper phone = emulator-5554, Basic phone = emulator-5556, the script is the carrier.
emulator-basic:
	@nohup "$(EMULATOR)" -avd pandastic_basic -port 5556 -no-snapshot-save -no-boot-anim > /tmp/pandastic-basic.log 2>&1 &
	@"$(ADB)" -s emulator-5556 wait-for-device && echo "emulator-5556 is starting (Basic phone)"
sms-setup:
	@ADB="$(ADB)" node scripts/sms-lab.mjs setup $(ARGS)
sms-relay:
	@ADB="$(ADB)" node scripts/sms-lab.mjs relay
sms-phone:
	@ADB="$(ADB)" node scripts/sms-lab.mjs phone
sms-test:
	@ADB="$(ADB)" node scripts/sms-lab.mjs test $(ARGS)

# Human test: with ./run.sh running, reset both phones to their first screen and put the
# test photos in their galleries. Run it before each tester.
human-test:
	@python3 scripts/human_test_prep.py --fresh
