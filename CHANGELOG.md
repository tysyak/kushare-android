# Changelog

Todos los cambios notables de kushare-android. Formato basado en
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); versionado
[SemVer](https://semver.org/lang/es/).

## [Sin publicar]

## [0.3.0] - 2026-11-06

### Añadido
- Comparte con el PC la **batería**, el **modo de timbre**, los
  **reproductores** (sesiones multimedia de cualquier app), las **salidas de
  audio** y el **estado de las llamadas**.
- Obedece al PC: controlar reproductores (y *Pausar todo*), cambiar el
  timbre, contestar/rechazar/colgar y silenciar el micrófono.
- **Galería para el PC**: miniaturas por páginas y la foto original a
  petición (requiere el permiso de fotos).
- En *Dispositivos*, por cada PC: su batería, sus reproductores con controles
  y su salida de audio (elegible).
- *Ajustes → Funciones del teléfono*: permisos opcionales (llamadas, fotos,
  No molestar) y el selector de salida de audio del sistema.

### Cambiado
- **Esquema de colores** violeta (#9d06d8) con acento rosa, igual que en el
  escritorio; el color dinámico (Material You) pasa a estar desactivado por
  defecto.
- Icono de la app en el color nuevo.

## [0.2.0] - 2026-11-05

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

[Sin publicar]: https://github.com/tysyak/kushare-android/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/tysyak/kushare-android/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/tysyak/kushare-android/releases/tag/v0.2.0
[0.1.0]: https://github.com/tysyak/kushare-android/commit/8d1f720
