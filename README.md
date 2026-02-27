# assistant-android

Aplicacion Android nativa (Kotlin + XML) con flujo:

1. Capturar voz con `SpeechRecognizer`
2. Convertir voz a texto
3. Enviar texto al backend por `POST /chat` con OkHttp
4. Mostrar respuesta
5. Reproducir respuesta con `TextToSpeech`
6. Revisar actualizaciones OTA Android por `GET /mobile/update/latest`

## Requisitos

- Android Studio (version reciente)
- Android SDK instalado
- Emulador o dispositivo fisico
- Backend `assistant-server` ejecutandose en `http://localhost:8000`

## Abrir y ejecutar

1. Abrir Android Studio.
2. Seleccionar `Open` y elegir la carpeta `assistant-android`.
3. Esperar a que termine el `Gradle Sync`.
4. Ejecutar la app en emulador o dispositivo.

## Configuracion de URL del backend

La URL esta en:

- `app/src/main/java/com/proyectoj/assistant/network/ApiClient.kt`

Por defecto:

```kotlin
const val CHAT_URL = "http://10.0.2.2:8000/chat"
```

`10.0.2.2` funciona en emulador Android para acceder al `localhost` de tu PC.

Tambien soporta `CLOUDFLARE_PUBLIC_BASE_URL` (BuildConfig) para priorizar una URL publica HTTPS.
Si no esta disponible, cae al discovery por LAN.

## OTA update (sin USB)

- Boton en la app: `Revisar actualizacion`.
- Flujo:
  - Consulta `GET /mobile/update/latest`.
  - Si `version_code` remoto es mayor al local, descarga APK.
  - Abre instalador Android (con confirmacion del usuario).
- Requiere permiso para instalar apps desconocidas cuando Android lo solicite.

## Permisos incluidos

- `android.permission.RECORD_AUDIO`
- `android.permission.INTERNET`
- `android.permission.REQUEST_INSTALL_PACKAGES`

## Errores comunes

- "Microphone permission is required":
  - Concede permiso de microfono al abrir la app.
- Error de red / timeout:
  - Verifica que `assistant-server` este corriendo en el puerto `8000`.
  - Si usas dispositivo fisico, reemplaza `10.0.2.2` por la IP local de tu PC.
- SpeechRecognizer no disponible:
  - Asegura que el dispositivo tenga servicios de reconocimiento de voz.
- TextToSpeech no disponible:
  - Instala o habilita motor TTS en configuracion del dispositivo.
