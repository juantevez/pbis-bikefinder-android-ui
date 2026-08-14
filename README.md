# BikeFinder Android

Cliente Android nativo de BikeFinder, la plataforma de registro y recuperación de
bicicletas robadas. Migración del [front web](https://github.com/juantevez) a Kotlin +
Jetpack Compose, contra el mismo backend.

## Estado

**En construcción.** Funciona el camino de login → dashboard → listado → alta de bicicleta
con fotos. El resto de las pantallas son marcadores de posición.

| Pantalla | Estado |
|---|---|
| Login (email + contraseña) | ✅ |
| Dashboard (resumen + grilla de acciones) | ✅ |
| Mis bicicletas (listado) | ✅ |
| Registrar bicicleta (catálogo y manual, con fotos) | ✅ |
| Denunciar robo | ✅ |
| Detalle de bicicleta | ⬜ placeholder |
| Componentes, plan de búsqueda, pistas, perfil | ⬜ placeholder |
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

Son 95, en tres grupos:

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
- **Las fuentes van bundleadas, no descargadas.** Cormorant Garamond y DM Sans (las mismas
  del front web) viven en `res/font` en vez de bajarse con `ui-text-google-fonts`. Cuestan
  ~670 KB de APK; a cambio la app abre con la tipografía de marca sin conexión y sin Google
  Play Services. Son instancias estáticas porque `res/font` recién soporta fuentes
  variables desde API 26 y el `minSdk` es 24. Licencias en [`licenses/`](licenses).
- **Sólo se bundlean los pesos que la web usa**: Cormorant 400/600 y DM Sans 400/500. El
  `<link>` de Google Fonts del front pide más de los que aplica, y el único 700 del CSS
  está en el panel de administración, que no se porta. Los tamaños de título salen del
  CSS; los de cuerpo y etiquetas no, porque la web baja a 11-13px y en un teléfono eso no
  se lee.
- **La denuncia no se manda sin ubicación, y se valida en el cliente.** Alcanza con la
  localidad o con el punto del GPS; la calle sola no cuenta, porque tampoco cuenta para el
  backend. Enviar y que el servidor rechace no es equivalente: el reporte se persiste
  antes de los pasos best-effort, así que un error tardío convive con una denuncia ya
  creada y el reintento devuelve "ya existe un reporte activo".
- **El punto del mapa propone la localidad, no la da por buena.** El PDF público omite la
  calle a propósito —es dato sensible, ver `OpenPdfGenerator.java:515`— y muestra sólo
  provincia, partido y localidad, los tres derivados de `localityId`. Un reporte hecho
  marcando el mapa salía entonces sin ninguna ubicación pública, mientras el privado se
  veía completo y no delataba nada. Ahora el nombre que devuelve OSM se busca en
  `/localities/search` y la localidad encontrada se propone junto con la calle, para que
  el usuario confirme. Sólo se propone con nombre **idéntico**: el backend busca por
  substring, y proponer "Villa Morón" para "Morón" es peor que no proponer nada. Entre
  homónimos desempata la provincia de OSM; si sigue habiendo empate no se propone.
- **El punto de la denuncia va como `APPROXIMATE`, no `EXACT`.** `EXACT` es de las pistas,
  donde el informante marca dónde vio la bici. Acá el punto sale del teléfono de quien
  denuncia, que no necesariamente estaba ahí cuando se la robaron.
- **La ubicación se toma con `LocationManager`, sin Google Play Services.** Un botón no
  justifica arrastrar esa dependencia, que además no está en todos los teléfonos.
- **La grilla del dashboard no depende del resumen.** Si `dashboard-aggregator` falla, los
  números muestran el error y las tarjetas siguen navegando. En el front web esa misma
  respuesta alimentaba también los selectores de bici, así que un fallo dejaba media
  pantalla muerta.

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
3. **Falta el paso de cobro previo a la denuncia.** En el front web, "Reportar robo" no
   abre el formulario: manda a `suscripcion.html?bikeId=…` (`dashboard.js:227` y
   `ver-bici.js:380`), donde se elige un plan de búsqueda —9.99 / 18.99 / 26.99 USD— y se
   paga con `POST /api/v1/payments`; recién con el `201` salta a
   `reportar-robo.html?bikeId=…&plan=…`. La app entra directo al formulario y saltea el
   cobro. Portarlo implica pantalla de planes, datos de tarjeta y replicar el manejo de
   `X-Idempotency-Key` de `suscripcion.js`: la clave se conserva en un 503 —que no prueba
   que el cobro no haya ocurrido— y se descarta en un 201 o un 422.
4. **Credenciales de prueba en el código** (`BackendIntegrationTest`). Sólo sirven contra
   un backend local, pero conviene sacarlas a variables de entorno.
5. **Fechas inconsistentes en el backend**: `media-service` serializa `LocalDateTime` como
   array JSON y el resto como ISO. El cliente tolera ambas, pero el arreglo de fondo es
   del servidor.
