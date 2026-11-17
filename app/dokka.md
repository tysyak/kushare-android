# Module kushare-android

App Android de **kushare**: comparte archivos con el PC (Quick Share por Wi-Fi o Bluetooth) y mantiene con él un
**enlace persistente** al estilo de KDE Connect o Enlace Móvil. Por ese enlace pasan las notificaciones sincronizadas,
la batería, la multimedia, las llamadas, los SMS, las fotos, el portapapeles, la webcam y el micrófono.

El protocolo, el cifrado y el framing están en Rust: son los crates de `kushare`, que se cargan con **UniFFI**
(`uniffi.kushare_mobile`, generado y no documentado aquí; su referencia es `cargo doc` en `rust/`). En Kotlin está lo
que solo Android sabe hacer: los servicios del sistema, los permisos, Bluetooth RFCOMM, NSD y la interfaz en Compose.

## Consumo de batería

El enlace está pensado para quedarse abierto todo el día:

- El control va siempre por **Bluetooth**. La Wi-Fi solo se abre para lo pesado, como fotos o la webcam, y se cierra a
  los 30 s.
- No hay escaneo continuo. La reconexión se dispara por eventos del sistema, con un respaldo que espera de 1 a 15 min.
- El keepalive por Bluetooth es de 4 min, y cada lado omite su ping si acaba de recibir tráfico.
- El estado (batería, reproductores, timbre…) solo se manda cuando cambia. La posición de la música no se reenvía mientras
  avance con normalidad, porque el PC ya la extrapola.

Medido en un Ulefone Armor 28 Ultra (Android 16): **0,23 mAh en 3,5 h**, con 15 s de CPU, lo que equivale al 0,08 % del
consumo del teléfono.

# Package org.kushare.app

Servicios, receptores y pantallas de sistema de la app:

- [KushareService] es el servicio en primer plano del enlace: escucha por TCP y RFCOMM, anuncia por mDNS y reconecta.
- [KushareLink] hace de puente con el `LinkService` de Rust y guarda el estado observable que usa la interfaz.
- [KushareNotificationListener] reenvía las notificaciones y ejecuta lo que pide el PC sobre ellas: descartarlas, pulsar
  una acción o responder.
- [PhoneFeatures] y [PhoneData] atienden las funciones extra: batería, multimedia, llamadas, fotos, SMS y contactos.
- [PhoneCameras], [Mic] y [QrScan] son la webcam y el micrófono para el PC y el escáner de QR.

# Package org.kushare.app.ui

Interfaz en Jetpack Compose con Material 3 Expressive: Inicio, Dispositivos, Enviar y Recibir, Portapapeles, Permisos y
Ajustes, además del tema (Sistema, Claro, Oscuro y OLED).
