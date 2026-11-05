# Changelog

Todos los cambios notables de kushare-android. Formato basado en
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); versionado
[SemVer](https://semver.org/lang/es/).

## [Sin publicar]

### Añadido
- **Enlace persistente con el PC** (mismo núcleo Rust `kushare-link` que el
  escritorio, vía UniFFI): emparejado con PIN por Wi-Fi o Bluetooth,
  reconexión automática, notificaciones del teléfono en el PC (con icono,
  acciones y respuesta) y del PC en el teléfono, descartar sincronizado, filtro
  por app y direcciones configurables desde ambos lados.
- Pantalla *Dispositivos*: emparejar, PIN, direcciones y filtros.
- **Botón de ajustes rápidos**: encender/apagar kushare, con el estado de la
  conexión como subtítulo; pausa automática sin Bluetooth ni Wi-Fi.
- Recepción de archivos por Bluetooth en segundo plano, guardados en
  *Descargas* con notificación.
- **Interfaz Material 3 Expressive** y *Ajustes* de tema: Sistema, Claro,
  Oscuro y Oscuro OLED (negro puro), con color dinámico.
- `Makefile` (`make install`) y README.

### Cambiado
- Búsqueda de dispositivos solo bajo demanda y reconexión por eventos (ACL,
  Bluetooth encendido, vuelve el Wi-Fi) para ahorrar batería.
- Toolchain: AGP 9.1.1, Kotlin 2.2.21, compileSdk 37, Material 3 1.5.0-rc01.
- `allowBackup=false` para no copiar la clave de identidad.

### Corregido
- El servicio se caía sin Bluetooth: faltaba `ACCESS_NETWORK_STATE`.
- Bucle infinito de notificaciones con KDE Connect (se ignoran otros puentes).

## [0.1.0] - 2026-09-22

### Añadido
- App inicial: envío y recepción de archivos con kushare de escritorio por
  Wi-Fi (mDNS) y Bluetooth RFCOMM.

[Sin publicar]: https://github.com/tysyak/kushare-android/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/tysyak/kushare-android/releases/tag/v0.1.0
