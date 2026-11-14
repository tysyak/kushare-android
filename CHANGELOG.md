# Changelog

Todos los cambios notables de kushare-android. Formato basado en
[Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/); versionado
[SemVer](https://semver.org/lang/es/).

## [Sin publicar]

### Añadido
- **Encontrar teléfono** (pedido desde el PC): suena por el canal de alarma a
  todo volumen aunque esté en silencio o vibración (y quita No molestar si
  kushare tiene ese acceso), vibra y parpadea la linterna; pantalla de kushare
  con un botón blanco grande «Presiona para silenciar». Se para sola a los
  5 min y deja el volumen y el modo como estaban. Permisos opcionales nuevos en
  *Permisos*: pantalla completa (con el teléfono bloqueado) y «Mostrar sobre
  otras apps» (para abrirla también si se está usando).
- Los reproductores mandan su carátula (~200 px), posición y duración al PC.

## [0.6.0] - 2026-11-13

### Añadido
- Fotos de los contactos para el PC: el registro de llamadas y los contactos
  llevan la clave de la foto (`photo_key`) y el PC pide las miniaturas que le
  faltan (`ContactPhotosRequest`), que se leen con el permiso de contactos que
  ya existía.

## [0.5.1] - 2026-11-10

### Cambiado
- El menú lateral y la tarjeta de inicio muestran «Bluetooth + Wi-Fi» en
  cuanto el PC comprueba que se alcanzan por la red (no solo mientras se
  transfiere), y vuelven a «Bluetooth» al salir de la Wi-Fi.

## [0.5.0] - 2026-11-07

### Añadido
- **Conexión híbrida** (por defecto): el control sigue por Bluetooth y lo
  pesado (fotos, SMS, listas) va por Wi-Fi cuando el PC está en la misma
  red, en un enlace que se abre solo cuando hace falta y se cierra a los
  30 s sin uso. El teléfono comunica su IP de Wi-Fi al PC por Bluetooth.
  *Ajustes → Conexión → Conexión híbrida*; apagado = todo por Bluetooth.
- El menú lateral muestra «Conectado · Bluetooth + Wi-Fi» mientras se usa
  el enlace de datos.

## [0.4.1] - 2026-11-07

### Añadido
- **Menú lateral** al estilo de Enlace Móvil: este teléfono, los PC
  vinculados con su estado (*Conectado · Bluetooth*), *Agregar PC*, y
  *Portapapeles*, *Permisos de la aplicación*, *Ajustes*, *Buscar
  actualizaciones* y *Envíanos comentarios*.
- Pantalla **Portapapeles**: lo último copiado desde y hacia el PC (solo en
  memoria; tocar una entrada la copia aquí) y un aviso breve «Copiado desde
  el PC» al recibir (desactivable).
- Pantalla **Permisos de la aplicación** (acceso a notificaciones y
  funciones opcionales).

### Cambiado
- Los errores se muestran con mensajes comprensibles («El PC no respondió…»,
  «No hay conexión con el PC», «Falta un permiso…») en vez del texto técnico,
  que queda solo en el log.

## [0.4.0] - 2026-11-07

### Añadido
- Para el PC (cada función con su permiso opcional en *Ajustes →
  Funciones del teléfono*):
  - **SMS**: conversaciones, mensajes y mandar SMS; avisa al PC de los nuevos.
  - **Registro de llamadas y contactos**, y **llamar** desde el PC.
  - **Notificaciones activas**: la lista completa, con acciones y respuesta.
  - **Fondo de pantalla** (con «acceso a todos los archivos»; sin él, sus colores).
  - **Borrar fotos** desde el PC (Android pide confirmarlo con un aviso).
- **Portapapeles compartido**: lo que copia el PC llega al instante; lo del
  teléfono se manda al abrir kushare, con el botón *Enviar portapapeles* de
  su notificación, con el botón de ajustes rápidos *Portapapeles al PC* o
  con *Compartir → Copiar en el PC*. No se manda lo marcado como sensible.
- **Fotos por Bluetooth**: no se reenvían las miniaturas que el PC ya tiene;
  las descargas de fondo van en trozos pequeños y a baja prioridad.
- **Actualizaciones**: *Ajustes → Actualizaciones* busca en GitHub, descarga
  el APK y lo instala con el instalador del sistema (misma firma).
- **Inglés** (`values-en`) y selección de idioma por app (Android 13+).

## [0.3.1] - 2026-11-07

### Añadido
- Avisa al PC de las fotos nuevas al instante (`PhotosChanged`).
- Textos en `res/values/strings.xml`, listos para traducir.
- Archivo `LICENSE` (GPL-3.0-or-later).

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
