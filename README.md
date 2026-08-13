# BikeFinder Android

Cliente Android nativo de BikeFinder, la plataforma de registro y recuperación de
bicicletas robadas. Migración del [front web](https://github.com/juantevez) a Kotlin +
Jetpack Compose, contra el mismo backend.

## Estado

**En construcción.** Funciona el camino de login → listado → alta de bicicleta con fotos.
El resto de las pantallas son marcadores de posición.

| Pantalla | Estado |
|---|---|
| Login (email + contraseña) | ✅ |
| Mis bicicletas (listado) | ✅ |
| Registrar bicicleta (catálogo y manual, con fotos) | ✅ |
| Detalle de bicicleta | ⬜ placeholder |
| Componentes, denuncia, plan de búsqueda, pistas, perfil | ⬜ placeholder |
| Registro de cuenta y login con Google | ⬜ sin implementar |
| Panel de administración | ❌ fuera de alcance — sigue siendo web |

El registro por email y el SSO no están: el OAuth social necesita un client ID de tipo
Android y un `redirect_uri` propio, y eso toca backend y consola de Google, no sólo el
cliente.

## Requisitos

- JDK 17
- Android Studio (o `./gradlew` a secas)
- Un dispositivo con Android 7.0+ (`minSdk 24`) o un emulador
- El backend de BikeFinder corriendo localmente — ver abajo

## Backend: el subconjunto mínimo

El stack completo arrastra Kafka, Elasticsearch y Selenium. Para trabajar en la app
alcanza con estos servicios:

| Servicio | Para qué |
|---|---|
| `bikefinder-postgres`, `redis` | base y rate limiting |
| `api-gateway` (8000) | todas las llamadas menos el arranque de OAuth |
| `auth-service` (8084) | login, refresh, perfil |
| `bike-registration` | bicicletas y catálogo |
| `media-service` + `kafka` + `s3-storage-app` | sólo si vas a probar fotos |

Se levanta **desde la raíz del repo del backend**, no desde `deploy/docker/`: el `.env`
de la raíz define `COMPOSE_FILE`, y sin él las credenciales de la base salen vacías y los
servicios entran en crash-loop con `Unable to determine Dialect without JDBC metadata`.

```bash
cd ~/java-code/bike-stolen-finder
docker compose up -d bike-registration
docker compose up -d media-service s3-storage-app   # sólo para fotos
```

Sin `media-service` la app anda igual: el alta funciona y las fotos fallan con un aviso
que dice que la bicicleta quedó registrada.

## Cómo conectar el teléfono al backend

La app apunta a `http://localhost:8000`, y `adb reverse` hace que ese `localhost` sea el
de tu máquina:

```bash
adb reverse tcp:8000 tcp:8000
adb reverse tcp:8084 tcp:8084
./gradlew installDebug
```

Hay que volver a correr `adb reverse` cada vez que se reconecta el dispositivo. **Si la
app no conecta, es lo primero a revisar.**

Se eligió sobre las dos alternativas: `10.0.2.2` sólo existe dentro del emulador, y la IP
de la LAN hay que perseguirla porque el DHCP se la cambia al router. `adb reverse` no
depende de wifi ni de IPs, y sirve igual en el emulador.

Para apuntar a otro backend sin recompilar está el override en runtime de
`ApiEnvironment`, equivalente al `localStorage.setItem('apiBase', …)` del front web.

## Tests

```bash
./gradlew test
```

Son 59, en tres grupos:

- **Contrato de DTOs** — que los modelos Kotlin coincidan con los `record` de Java.
- **Lógica** — renovación de sesión, traducción de errores, cascada del wizard.
- **Integración** — contra el backend **realmente corriendo**.

Los de integración se saltean solos si el gateway no responde, así que no rompen el build
de alguien sin el stack levantado. Que aparezcan como *skipped* es información: no hay
que confundirlo con que pasaron.

Son los únicos que pueden detectar que un DTO dejó de coincidir con el backend — los
demás usan payloads escritos a mano, que por definición coinciden con lo que el cliente
espera. Así aparecieron el formato de fecha de `media-service` y la forma de error de
`auth-service`.

## Arquitectura

```
data/
  local/       ApiEnvironment (a qué backend apunta), TokenStore (DataStore)
  remote/      ApiResult, SessionManager, interceptores, DTOs, interfaces Retrofit
  repository/  Auth, Bicycle, Catalog, Photo
di/            módulos de Hilt
ui/            theme (portado de theme.css), navigation, y una carpeta por pantalla
```

Decisiones que no se ven leyendo el código:

- **`ApiResult` tiene cuatro casos, no dos**: `Success`, `NoNetwork`, `HttpError` y
  `Malformed`. El último existe porque "no pude interpretar la respuesta" significa que
  el contrato cambió; confundirlo con un error del servidor esconde el problema.
- **Una sesión sólo se pierde cuando el servidor dijo que el token no vale.** Un 5xx, un
  429 o un fallo de red no la tocan. En un teléfono la conexión se corta sola varias
  veces por día, y desloguear por eso hace la app inusable.
- **Hay dos clientes OkHttp.** El del refresh no lleva `Authenticator`, o un 401 en
  `/auth/refresh` dispararía un refresh que dispara otro.
- **El botón "Reintentar" sólo aparece cuando reintentar no puede duplicar nada.** Un 503
  del gateway significa que se cortó la espera, no que la operación no haya ocurrido.
- **`X-Idempotency-Key` va como parámetro explícito**, no la pone un interceptor: tiene
  que ser la misma en todos los reintentos de un pago.
- **Sin dynamic color**: la paleta crema/dorada es identidad de marca.

El mapa completo de endpoints, modelos y decisiones de mapeo está en
[`docs/API-MAP.md`](docs/API-MAP.md).

## Privacidad: el consentimiento de GPS

Al subir fotos, el usuario puede autorizar que se analice la ubicación embebida en ellas
para validar futuras denuncias. Es opcional y por defecto está apagado.

El permiso `ACCESS_MEDIA_LOCATION` se pide en runtime **sólo al marcar ese checkbox**.
Desde Android 10 el sistema le quita la ubicación a las fotos que entrega salvo que la
app tenga el permiso y pida el original: sin él, el checkbox sería decorativo — el
usuario autorizaría analizar un dato que el sistema ya borró.

## Pendientes conocidos

1. **Tokens sin cifrar en reposo.** `EncryptedSharedPreferences` está deprecado y cifrar
   con Keystore es trabajo real. Quedan excluidos del backup, que es el mínimo. Antes de
   producción: cifrar, o acortar la vida del refresh token para que robarlo valga poco.
2. **OAuth social sin resolver** — el ítem de mayor fricción.
3. **Tipografía sin portar**: el front usa Cormorant Garamond y DM Sans; la app se ve con
   la fuente del sistema.
4. **Credenciales de prueba en el código** (`BackendIntegrationTest`). Sólo sirven contra
   un backend local, pero conviene sacarlas a variables de entorno.
5. **Fechas inconsistentes en el backend**: `media-service` serializa `LocalDateTime` como
   array JSON y el resto como ISO. El cliente tolera ambas, pero el arreglo de fondo es
   del servidor.
