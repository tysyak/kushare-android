# kushare-android

App Android de [kushare](https://github.com/tysyak/kushare): transferencia de archivos y **notificaciones sincronizadas** con el PC, al estilo de KDE Connect. La interfaz es Kotlin + Jetpack Compose con **Material 3 Expressive** (formas de `MaterialShapes`, tipografía enfática, esquema de movimiento expresivo y color dinámico). En *Ajustes* se elige el tema: Sistema, Claro, Oscuro u **Oscuro OLED** (negro puro). El protocolo, la criptografía y la lógica del enlace son **el mismo código Rust** que usa el cliente de escritorio, expuesto a Kotlin con [UniFFI](https://mozilla.github.io/uniffi-rs/).

## Qué hace

- **Enviar y recibir archivos** con kushare de escritorio, por Wi-Fi (mDNS) o Bluetooth RFCOMM.
- **Enlace persistente con el PC** (pantalla *Dispositivos*):
  - emparejado con un PIN de 6 dígitos que se confirma en los dos lados, por Wi-Fi o por **Bluetooth** (para redes que aíslan a los dispositivos entre sí);
  - notificaciones del teléfono en el PC y del PC en el teléfono, cada dirección activable por separado desde cualquiera de los dos lados;
  - filtro por app, descartar sincronizado, iconos, y **acciones y respuesta** desde el PC (p. ej. contestar un mensaje de Telegram);
  - reconexión automática, sin volver a pedir el PIN.
- **Teléfono desde el PC** (al estilo KDE Connect): batería, modo de timbre, reproductores (controlar YouTube, TIDAL, Spotify… y *Pausar todo*), salidas de audio, llamadas (contestar, rechazar, colgar, silenciar; con el PC como manos libres por Bluetooth) y galería de fotos (miniaturas y descarga del original).
- **"Enlace Móvil" para Linux** (0.4.0): el PC lee y manda **SMS**, ve el **registro de llamadas**, busca **contactos** y **llama** desde el teléfono, ve sus **notificaciones activas** (con acciones y respuesta), su **fondo de pantalla**, y puede **borrar fotos** (van a la papelera, confirmándolo aquí).
- **Portapapeles compartido**: lo que copia el PC llega al instante; lo del teléfono se manda al abrir kushare, con *Enviar portapapeles* (en su notificación o en *Ajustes*), con el botón de ajustes rápidos *Portapapeles al PC* o con *Compartir → Copiar en el PC*. Android no deja leer el portapapeles en segundo plano.
- **Menú lateral** (☰) al estilo de Enlace Móvil: PC vinculados con su estado, *Agregar PC*, *Portapapeles* (lo último copiado con el PC), *Permisos de la aplicación*, *Ajustes* y *Actualizaciones*.
- **PC desde el teléfono**: su batería, sus reproductores (MPRIS) y su salida de audio, en *Dispositivos*.
- **Actualizaciones** desde *Ajustes*: busca el último release en GitHub, lo descarga y lo instala con el instalador del sistema (que exige la misma firma).
- **Botón en los ajustes rápidos** (la cortina de Wi-Fi, Bluetooth…):
  - encendido mantiene el enlace; apagado detiene kushare del todo;
  - debajo del nombre muestra por dónde está conectado ("PC · Bluetooth");
  - sin Bluetooth ni Wi-Fi kushare se pone **en pausa** solo y se reanuda cuando vuelve alguno.

## Problemas comunes

Ver el [FAQ de kushare](https://github.com/tysyak/kushare/blob/main/FAQ.md): firewall del PC, redes que aíslan dispositivos (usa Bluetooth), KDE Connect, acceso a notificaciones y batería.

## Permisos

| Permiso | Para qué |
|---|---|
| Acceso a notificaciones | Leer las notificaciones para enviarlas al PC. Se concede en los ajustes del sistema; la app lleva hasta ahí. |
| Notificaciones | Mostrar las del PC, el PIN y el aviso del servicio. |
| Dispositivos cercanos (Bluetooth) | Enlace y transferencias por RFCOMM. |
| Servicio en primer plano (*connectedDevice*) | Mantener el enlace con la pantalla apagada. |
| Estado de la red / Wi-Fi | Descubrimiento mDNS y pausa automática sin red. |
| Teléfono y llamadas (opcional) | Avisar en el PC de las llamadas y contestar/colgar desde allí. |
| Fotos (opcional) | Que el PC vea la galería y descargue fotos. |
| No molestar (opcional) | Que el PC pueda poner el teléfono en silencio. |
| SMS (opcional) | Leer y mandar SMS desde el PC. |
| Registro de llamadas, contactos y llamar (opcional) | Llamadas recientes, buscar contactos y llamar desde el PC. |
| Acceso a todos los archivos (opcional) | Solo para que el PC muestre el fondo de pantalla (Android no lo deja leer de otra forma); sin él, el PC usa sus colores. |
| Instalar apps (al actualizar) | Instalar la versión nueva de kushare desde la propia app. |

Los opcionales se conceden en *Ajustes → Funciones del teléfono*. Los reproductores se leen gracias al acceso a notificaciones (no hace falta otro permiso).

`android:allowBackup` está desactivado a propósito: la clave de identidad (`files/link/identity.key`) no debe acabar en una copia de seguridad, porque restaurarla en otro teléfono lo haría pasar por este.

## Consumo de batería

El enlace está pensado para quedarse abierto siempre por Bluetooth sin gastar:

- **No hay escaneo continuo**: la app solo se anuncia (pasivo) y busca dispositivos cuando se pulsa *Buscar*, durante 30 s.
- **Mientras hay enlace no se despierta la CPU**: el lector espera en el socket y el keepalive por Bluetooth es de 4 min. Si el PC se aleja o se apaga, la pila Bluetooth lo detecta sola.
- **Reconexión por eventos**: cuando el sistema avisa de que el PC se conectó por Bluetooth, de que se encendió el Bluetooth o de que volvió el Wi-Fi. Sin eventos solo queda un reintento de respaldo, de 1 a 15 min.

## Estructura

```
app/src/main/java/org/kushare/app/
  MainActivity.kt                 navegación animada entre pantallas y lógica de enviar/recibir
  ui/Theme.kt, ThemeSettings.kt   tema Expressive (color dinámico, OLED, tipografía, formas, movimiento)
  ui/Components.kt                tarjetas asimétricas, iconos en MaterialShapes, microinteracciones
  ui/HomeScreen.kt, SettingsScreen.kt, DevicesScreen.kt, TransferScreens.kt
  ReceivedFiles.kt                archivos recibidos en segundo plano → Descargas + notificación
  KushareService.kt               servicio en primer plano: enlace, pausa sin radios, reconexión BT
  KushareNotificationListener.kt  lee notificaciones y ejecuta acciones/respuestas que pide el PC
  KushareLink.kt                  estado compartido + callbacks del enlace (espejos del PC, PIN)
  KushareSwitch.kt                interruptor general y estado de radios
  KushareTileService.kt           botón de ajustes rápidos
  LinkNsd.kt / NsdHelper.kt       mDNS del enlace / de transferencias
  BluetoothHelper.kt              sockets RFCOMM (transferencias y enlace)
  LinkReceivers.kt                botones de notificaciones y arranque al encender
app/src/main/java-generated/      bindings Kotlin generados por UniFFI (no editar a mano)
rust/                             crate kushare_mobile (cdylib): puente UniFFI sobre los crates de kushare
  src/lib.rs                      transferencias
  src/link.rs                     enlace persistente (LinkService)
  src/bt_bridge.rs                adapta un BluetoothSocket de Kotlin a AsyncRead/AsyncWrite
```

## Compilar

Requisitos: Android SDK con la plataforma **android-37** y el NDK, JDK 17, Rust con el target `aarch64-linux-android` y [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk). Toolchain: Gradle 9.4.1, AGP 9.1.1 (Kotlin integrado; ya no se aplica `org.jetbrains.kotlin.android`), Kotlin 2.2.21, Compose BOM 2026.09.00 y Material 3 **1.5.0-rc01** (Material 3 Expressive exige compileSdk 37 y AGP ≥ 9.1).

Atajo: `make install` hace todo lo de abajo e instala en el teléfono (ver el `Makefile`).

El crate Rust depende por **ruta relativa** de los crates de kushare: hay que clonar [kushare](https://github.com/tysyak/kushare) **al lado** de este repo (`../kushare`).

```sh
rustup target add aarch64-linux-android
cargo install cargo-ndk

# 1. Biblioteca nativa (no está versionada: app/src/main/jniLibs/ está en .gitignore)
cd rust
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release

# 2. Regenerar los bindings de Kotlin (solo si cambió la API exportada en rust/src)
cargo run --features cli --bin uniffi-bindgen -- generate \
  --library target/aarch64-linux-android/release/libkushare_mobile.so \
  --language kotlin --out-dir ../app/src/main/java-generated --no-format

# 3. APK
cd ..
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Después de tocar los crates de kushare (`kushare-link`, `kushare-transfer`, …) hay que repetir el paso 1, y el 2 si cambió algo exportado con `#[uniffi::export]`.

## Firma de release

`make release` genera el APK de producción (`app/build/outputs/apk/release/app-release.apk`): con R8 (quita código y recursos sin usar), no depurable y firmado con tu clave. La clave **no está en el repo**; se lee de `~/.gradle/gradle.properties`:

```properties
KUSHARE_RELEASE_STORE_FILE=/home/<usuario>/.android/kushare-release.jks
KUSHARE_RELEASE_STORE_PASSWORD=…
KUSHARE_RELEASE_KEY_ALIAS=kushare
KUSHARE_RELEASE_KEY_PASSWORD=…
```

Para crear una clave nueva:

```sh
keytool -genkeypair -keystore ~/.android/kushare-release.jks -storetype PKCS12 \
  -alias kushare -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=kushare, O=kushare"
```

**Guarda una copia de la clave y de su contraseña**: las actualizaciones solo se instalan encima si están firmadas con la misma clave. Sin las propiedades, el APK de release sale sin firmar.

## Probar con adb

```sh
# conceder acceso a notificaciones sin pasar por los ajustes
adb shell cmd notification allow_listener org.kushare.app/org.kushare.app.KushareNotificationListener
# añadir el botón a los ajustes rápidos y pulsarlo
adb shell cmd statusbar add-tile org.kushare.app/.KushareTileService
adb shell cmd statusbar click-tile org.kushare.app/.KushareTileService
# publicar una notificación de prueba (debería llegar al PC)
adb shell "cmd notification post -t 'Hola' prueba 'desde el teléfono'"
# logs
adb logcat -s KushareService KushareLink KushareNotif LinkNsd
# estado guardado del enlace
adb shell run-as org.kushare.app cat files/link/peers.toml
```

## Traducir

Todos los textos están en [`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml)
(español). Para otro idioma, copia ese archivo a `res/values-<idioma>/strings.xml`
y traduce los valores sin cambiar los `name`; `%1$s` y `%1$d` son datos que se
rellenan y `%%` es un signo de porcentaje. Incluidos: español y **inglés**
(`values-en`). Android usa el idioma del sistema; en Android 13+ se puede elegir
otro solo para kushare (*Ajustes del sistema → Apps → kushare → Idioma*; añade
el nuevo idioma a `res/xml/locales_config.xml`). Si falta un texto, sale en
español.

## Licencia

GPL-3.0-or-later, como kushare.
