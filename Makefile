# Atajos de compilación de kushare-android. Requisitos y detalle en README.md.
#
#   make            .so + bindings + APK de depuración
#   make install    lo anterior e instalarlo en el teléfono conectado por adb
#   make native     solo la biblioteca Rust (tras tocar los crates de kushare)
#   make bindings   regenerar los bindings Kotlin (tras cambiar la API #[uniffi::export])

ABI      ?= arm64-v8a
TARGET   ?= aarch64-linux-android
PROFILE  ?= release
SO        = rust/target/$(TARGET)/$(PROFILE)/libkushare_mobile.so
APK       = app/build/outputs/apk/debug/app-debug.apk
PKG       = org.kushare.app

.PHONY: all native bindings apk install run logs grant-listener clean

all: apk

native:
	cd rust && cargo ndk -t $(ABI) -o ../app/src/main/jniLibs build --$(PROFILE)

bindings: native
	cd rust && cargo run --features cli --bin uniffi-bindgen -- generate \
	  --library target/$(TARGET)/$(PROFILE)/libkushare_mobile.so \
	  --language kotlin --out-dir ../app/src/main/java-generated --no-format

apk: bindings
	./gradlew assembleDebug

install: apk
	adb install -r $(APK)
	$(MAKE) grant-listener

# Tras reinstalar, Android a veces retira el acceso a notificaciones.
grant-listener:
	adb shell cmd notification allow_listener $(PKG)/$(PKG).KushareNotificationListener

run: install
	adb shell am start -n $(PKG)/.MainActivity

logs:
	adb logcat -s KushareService KushareLink KushareNotif LinkNsd AndroidRuntime

clean:
	./gradlew clean
	cd rust && cargo clean
	rm -rf app/src/main/jniLibs
