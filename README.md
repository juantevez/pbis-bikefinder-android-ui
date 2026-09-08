# BikeFinder Android

Cliente Android nativo de BikeFinder, la plataforma de registro y recuperación de
bicicletas robadas. Migración del [front web](https://github.com/juantevez) a Kotlin +
Jetpack Compose, contra el mismo backend.

## Estado

**En construcción**, pero ya no quedan pantallas de relleno: los caminos del dueño de la
bici y del informante están completos de punta a punta.

| Pantalla | Estado |
|---|---|
| Login (email + contraseña, segundo factor, recuperar contraseña) | ✅ |
| Dashboard (resumen + grilla de acciones + tema) | ✅ |
| Mis bicicletas (listado y baja) | ✅ |
| Registrar bicicleta (catálogo y manual, con fotos) | ✅ |
| Detalle de bicicleta (fotos, componentes, pistas recibidas) | ✅ |
| Actualizar componentes (con alta y baja de fotos) | ✅ |
| Plan de búsqueda (pago) | ✅ |
| Denunciar robo (alta y corrección de una ya presentada) | ✅ |
| Mis denuncias (PDF privado y público, pistas) | ✅ |
| Mi perfil (datos, ubicación, tema, avisos, segundo factor) | ✅ |
| Dejar una pista y conversación con el informante (sin sesión, por deep link) | ✅ |
| Registro de cuenta y login con Google | ⬜ sin implementar |
| Consumir desde la app el link de verificación de mail o de reset | ⬜ vive en el front web |
| Panel de administración | ❌ fuera de alcance — sigue siendo web |

El registro por email y el SSO no están: el OAuth social necesita un client ID de tipo
Android y un `redirect_uri` propio, y eso toca backend y consola de Google, no sólo el
cliente.

De la verificación de mail y del reset de contraseña se porta **la mitad que la app puede
resolver sola** —pedir el mail—; elegir la contraseña nueva o consumir el token del link
sigue pasando por el navegador, porque `EmailAdapter` arma el link como `frontendUrl +
"/reset-password.html?token="` y atraparlo desde la app necesita un deep link declarado en
`auth-service`.

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
| `auth-service` (8084) | login, segundo factor, refresh, perfil |
| `bike-registration-service` | bicicletas, componentes y catálogo |
| `location-service` | la cascada geográfica del perfil y de la denuncia |
| `theft-report-service` | denuncias, PDF y pistas |
| `dashboard-aggregator` | los números del dashboard (la grilla anda sin él) |
| `payment-service` | el plan de búsqueda |
| `media-service` + `kafka` + `storage-service` | sólo si vas a probar fotos |
| `notification-service` + `mailhog` | los avisos y los mails de verificación/reset |

**`api-gateway` y `auth-service` viven en repos aparte** (`~/java-code/api-gateway` y
`~/java-code/auth-service`, cada uno con su `deploy/docker/docker-compose.yml`); el resto
está en el monorepo.

El monorepo se levanta **desde la raíz del repo, no desde `deploy/docker/`**: el `.env` de
la raíz define `COMPOSE_FILE`, y sin él las credenciales de la base salen vacías y los
servicios entran en crash-loop con `Unable to determine Dialect without JDBC metadata`.

```bash
cd ~/java-code/bike-stolen-finder
docker compose up -d bike-registration-service location-service theft-report-service
docker compose up -d media-service storage-service   # sólo para fotos
```

Sin `media-service` la app anda igual: el alta funciona y las fotos fallan con un aviso
que dice que la bicicleta quedó registrada.

## Cómo conectar el teléfono al backend

La app apunta a `http://localhost:8000`, y `adb reverse` hace que ese `localhost` sea el
de tu máquina. Está scripteado:

```bash
scripts/dev-reverse.sh      # abre los túneles 8000 y 8084 y los verifica desde el teléfono
./gradlew installDebug
```

Hay que volver a correrlo cada vez que se reconecta el dispositivo, se reinicia el
servidor adb (Android Studio arranca el suyo) o se reinicia el teléfono. Es idempotente.
**Si la app no conecta, es lo primero a revisar**: el request muere en la capa de red del
teléfono y el gateway ni se entera, así que no hay nada en sus logs.

El script no se conforma con abrir los túneles: sondea los dos puertos **desde el
teléfono**, porque que el backend conteste en la máquina no dice nada sobre si el túnel
funciona, que es justo lo que falla en silencio.

Se eligió `adb reverse` sobre las dos alternativas: `10.0.2.2` sólo existe dentro del
emulador, y la IP de la LAN hay que perseguirla porque el DHCP se la cambia al router.
`adb reverse` no depende de wifi ni de IPs, y sirve igual en el emulador.

Para apuntar a otro backend sin recompilar está el override en runtime de
`ApiEnvironment`, equivalente al `localStorage.setItem('apiBase', …)` del front web.

## Tests

```bash
./gradlew test
./gradlew :app:koverHtmlReportDebug   # cobertura navegable; koverLogDebug para el número
```

Son 256, en tres grupos:

- **Contrato de DTOs** — que los modelos Kotlin coincidan con los `record` de Java.
- **Lógica** — renovación de sesión, traducción de errores, cascada del wizard, diff de la
  corrección de denuncias, payload de componentes y de pistas.
- **Integración** — contra el backend **realmente corriendo**.

Los de integración se saltean solos si el gateway no responde **o si no existe la cuenta
de prueba**, así que no rompen el build de alguien sin el stack levantado. Que aparezcan
como *skipped* es información: no hay que confundirlo con que pasaron. El sondeo de la
cuenta no es un lujo: vive en el postgres de desarrollo y no la crea ninguna migración, así
que al recrear la base los tests fallaban con `HTTP 401 Unauthorized` —un mensaje que
apunta a las credenciales del cliente y no a que falta el fixture—.

Son los únicos que pueden detectar que un DTO dejó de coincidir con el backend — los
demás usan payloads escritos a mano, que por definición coinciden con lo que el cliente
espera. Así aparecieron el formato de fecha de `media-service` y la forma de error de
`auth-service`.

Kover tiene una tarea por variante de Android; la útil es la de debug, que es donde corren
los tests.

## Arquitectura

```
data/
  local/       ApiEnvironment (a qué backend apunta), TokenStore (DataStore),
               ThemeStore, PaymentKeyStore, DeviceLocation
  remote/      ApiResult, SessionManager, interceptores, DTOs,
               interfaces Retrofit (auth, bicycles, thefts, misc, Nominatim)
  repository/  Auth, Bicycle, Catalog, Photo, Theft, PublicTip, Payment,
               Dashboard, Geo, Geocoding, Notification
di/            módulos de Hilt
ui/            theme (portado de theme.css), navigation, y una carpeta por pantalla:
               login, dashboard, bikes, addbike, bikedetail, updatecomponents,
               reporttheft, reports, subscription, profile, tips, tipform,
               conversation, common
```

Decisiones que no se ven leyendo el código:

### Red y sesión

- **`ApiResult` tiene cuatro casos, no dos**: `Success`, `NoNetwork`, `HttpError` y
  `Malformed`. El último existe porque "no pude interpretar la respuesta" significa que
  el contrato cambió; confundirlo con un error del servidor esconde el problema.
- **Una sesión sólo se pierde cuando el servidor dijo que el token no vale.** Un 5xx, un
  429 o un fallo de red no la tocan. En un teléfono la conexión se corta sola varias
  veces por día, y desloguear por eso hace la app inusable.
- **Hay dos clientes OkHttp.** El del refresh no lleva `Authenticator`, o un 401 en
  `/auth/refresh` dispararía un refresh que dispara otro.
- **Serializar el refresh no alcanzaba: hay que saltearlo.** El `Mutex` evitaba la
  estampida, pero las requests que esperaban entraban igual a renovar una por una, y cada
  rotación de más es una ventana en la que otro cliente logueado con la misma cuenta se
  queda con un token recién consumido. Ahora `refresh()` recibe el access token que se
  comió el 401 y, si el guardado ya es otro, devuelve `Ok` sin pedir nada.
- **El botón "Reintentar" sólo aparece cuando reintentar no puede duplicar nada.** Un 503
  del gateway significa que se cortó la espera, no que la operación no haya ocurrido.
- **Las fotos van por el `ImageLoader` de la app, no por el de Coil.** El `downloadUrl` de
  `media-service` es la clave del archivo, no una URL: hay que pedirla por
  `/api/files/download` con el Bearer. Coil se arma su propio cliente HTTP, con lo cual
  cada foto daría 401 y la galería quedaría vacía sin error visible.

### Login y perfil

- **El segundo factor reemplaza el formulario, no agrega campos debajo.** La contraseña ya
  se validó, y dejarla a la vista invita a corregirla, que es justo lo que no hay que
  hacer. El challenge vive en el `UiState` y no se persiste: vale cinco minutos y muere
  con el ViewModel. Que `mfaToken` no sea null **es** el estado del segundo paso, así que
  dato y estado no pueden desincronizarse.
- **El alta del segundo factor no dibuja un QR, a diferencia de la web**, y no es una
  simplificación: el usuario ya está en el teléfono, así que escanear un código dibujado en
  esa misma pantalla es imposible. Se abre la app de autenticación con la URI `otpauth://`
  —que es lo que el QR contiene— por intent local, y queda la clave en Base32 para cargarla
  a mano. Los códigos de recuperación se muestran una sola vez: el backend guarda sólo su
  hash.
- **El pedido de reset se avisa como exitoso aunque el servidor conteste error**, igual que
  la web: cualquiera puede escribir una dirección ajena y un "esa cuenta no existe"
  volvería la pantalla una forma de averiguar qué mails están registrados. El reenvío del
  mail de verificación **sí** muestra el error, porque se dispara sobre el mail del usuario
  logueado —no hay nada que averiguar— y porque su 400 "ya está verificado" importa:
  tragarlo dejaría al usuario esperando un mail que nunca se mandó.
- **`PUT /auth/me` no es un parche para la ubicación**: los cuatro campos se asignan siempre,
  así que un null los borra. Con `location-service` caído las listas quedaban vacías y
  guardar el perfil vaciaba la ubicación sin un solo error a la vista; ahora se reenvía lo
  que ya estaba guardado, pero **sólo si hubo error del catálogo**: la lista también está
  vacía cuando el usuario limpió el nivel de arriba a propósito, y ahí resucitar el valor
  viejo sería ignorarlo.

### Denuncia y pago

- **`X-Idempotency-Key` va como parámetro explícito**, no la pone un interceptor: tiene
  que ser la misma en todos los reintentos de un pago.
- **La clave del pago vive en disco, no en el ViewModel.** En la web está en
  `sessionStorage` para sobrevivir a un F5; acá el riesgo es peor: al ViewModel lo mata el
  sistema cuando necesita memoria mientras el usuario está en la app de su banco, que es
  justo el momento de un pago dudoso. Se descarta sólo ante un final conocido —cobrado o
  rechazado—; un 503 o un fallo de red la conservan, que es cuando reusarla evita el
  segundo cobro.
- **`PROCESSING` no da el plan por pagado.** El front web trata el 201 como final y manda
  a denunciar con un cobro que todavía puede fallar. Acá se consulta el pago hasta que el
  estado sea terminal; si no resuelve, el resultado es *incierto* y no *fallido*, y el
  botón lo dice — si el usuario cree que reintentar le cobra dos veces, abandona.
- **La denuncia no se manda sin ubicación, y se valida en el cliente.** Alcanza con la
  localidad o con el punto del GPS; la calle sola no cuenta, porque tampoco cuenta para el
  backend. Enviar y que el servidor rechace no es equivalente: el reporte se persiste
  antes de los pasos best-effort, así que un error tardío convive con una denuncia ya
  creada y el reintento devuelve "ya existe un reporte activo".
- **La corrección manda sólo las secciones que cambiaron**, diffeando contra una
  instantánea tomada al hidratar. Los tres PATCH son independientes y no hay transacción
  entre ellos: si el segundo falla, lo que escribió el primero ya quedó escrito, así que se
  dice cuál entró y la instantánea avanza para que reintentar mande sólo lo que falta. Los
  textos van crudos y no con `ifBlank { null }` como en el alta: un null significa "no
  toques este campo", y sólo una cadena vacía borra de verdad. Cada PATCH marca los PDF
  como stale — el anterior puede estar en manos de la policía.
- **"Corregir la denuncia" sólo aparece si el status es `ACTIVE`.** El backend contesta 409
  sobre una `FOUND` o `CLOSED`, así que ofrecerlo sería prometer un error.
- **Al terminar la denuncia se aterriza en el listado**, no en la pantalla de antes: es
  desde ahí que salen los dos PDF. Y el formulario sale del back stack, porque volver atrás
  sobre uno ya enviado sólo puede llevar a denunciar dos veces la misma bici.
- **La recompensa es un sí/no, sin monto ni moneda.** El backend dejó de guardar el importe:
  la cifra la acuerdan el dueño y quien encuentra la bici.
- **Los dos PDF viven juntos y se explican.** El privado lleva calle, hora, descripción y
  contacto —es el que va a la policía—; el público omite todo eso y sólo muestra la zona.
  Son documentos distintos, no dos formatos del mismo, así que la pantalla dice cuál es
  cuál: elegir qué se comparte es una decisión del usuario. El público va **sin**
  `Authorization`, igual que en `dashboard.js`: el endpoint es público y sugerir que hace
  falta sesión para algo pensado para pasar de mano en mano está mal.
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
- **Los rótulos geográficos salen del dato, no de Argentina.** `location-service` devuelve
  el `type` de cada nivel, así que con Chile cargado dice Provincia / Comuna donde antes
  decía Departamento o partido / Localidad, y el próximo país no necesita tocar la app. El
  diccionario guarda artículo y plural porque los textos de ayuda tienen que concordar
  ("No se pudieron cargar **las comunas**"). En el perfil hay un caso que los selects no
  resuelven: la vista de sólo lectura guarda los nombres ya elegidos, así que el tipo se le
  pide al catálogo con `GET /localities/{id}`; si falla quedan los rótulos por defecto,
  que es una etiqueta y no justifica romper la vista. Port de `js/location-labels.js`.

### Bicicletas y fotos

- **El número de serie es obligatorio en los dos modos del alta**, aunque el backend lo
  siga aceptando vacío: sin serie la bici no se puede identificar cuando aparece, que es
  todo el punto de registrarla. Por eso el 409 —serie ya tomada— se traduce en vez de caer
  en el error genérico: es el único caso donde el usuario puede hacer algo concreto.
- **El tope de fotos es 4, no 8**, que es el que hace cumplir la pantalla de edición de la
  web: con 8, una bici podía nacer por encima del tope que la edición después aplicaba. Las
  que no entran se avisan una sola vez y con el motivo.
- **Las fotos se reescalan antes de subirlas** (2000px, JPEG 85) conservando el EXIF, que es
  de donde salen el GPS —con consentimiento— y los datos de cámara; de paso resuelve el
  HEIC, que `media-service` no sabe leer. A diferencia del navegador **no se toca la
  orientación**: `BitmapFactory` no aplica la rotación del EXIF al decodificar, así que
  corregir el tag a 1 —como hace la web, donde `createImageBitmap` sí rota— dejaría las
  fotos acostadas.
- **El lightbox abre con la miniatura debajo del original.** El original tarda ~0,3s contra
  los ~0,02s de la miniatura, y sin nada debajo el visor abre en blanco justo después de un
  tap. No va por `placeholderMemoryCacheKey`: la clave de memoria de Coil incluye el tamaño
  pedido, y la entrada de la grilla es de 140dp.
- **La grilla del detalle pide la miniatura; el original queda para el lightbox.** Cuatro
  fotos son ~1,2 MB en original contra ~120 KB en miniatura, y se estaban bajando enteras
  para pintar cuadrados de 140 dp. Las fotos sin miniatura caen al original.
- **La galería envuelve, no es un carrusel.** Con una `LazyRow` entraban dos miniaturas
  justas en un teléfono de 360 dp y la tercera arrancaba en el borde sin asomar: una bici
  con cuatro fotos aparentaba tener dos. La web nunca fue un carrusel —`.photo-gallery` es
  un `repeat(auto-fill, minmax(120px, 1fr))`—, así que va `FlowRow`.
- **Las fotos se agregan y se borran fuera del formulario de componentes.** Impactan al
  toque contra media-service, no al apretar "Guardar": si colgaran del guardado, "Cancelar"
  tendría que poder deshacer un DELETE que ya ocurrió. Es la misma decisión que tomó la web
  al sumar la sección, y por eso la sección va debajo de los botones. El borrado pide
  confirmación y avisa aparte si la foto es la principal, que es la que se ve en el listado
  y en la denuncia.
- **Una subida cortada no es una subida fallida.** `PhotoUploadResult` tiene tres casos, no
  dos: el servidor pudo haber guardado la foto y haberse perdido sólo la respuesta —pasó el
  4/9/2026 en la web: quedó en S3 y en la base, con miniatura, y el teléfono mostró error—.
  Ahí el aviso manda a mirar la grilla, que se refresca contra el backend, en vez de decir
  "no se pudo" e invitar a reintentar y duplicar. Un `Malformed` cae del mismo lado: llega
  después de un 2xx, así que la foto está.
- **Ahí las fotos se suben de a una, y en el alta de a tres.** No es una inconsistencia: en
  el alta el usuario está esperando el resultado del registro y no hay lista donde mirar; en
  componentes está mirando la grilla, y en una conexión mala el paralelo hace caer las
  subidas todas juntas.
- **El PATCH de componentes reemplaza el mapa entero, no manda un delta**, así que el
  payload arranca copiando todo lo que había y sólo pisa las 15 claves con formulario: sin
  eso, guardar desde el teléfono borraría las nueve claves que la web declara pero no
  dibuja, y cualquier cosa escrita por otro cliente. La metadata de procedencia
  (`isOriginal`, `source`, `originalBrand`) es la única memoria de qué pieza vino de
  fábrica: no se puede recalcular y en una denuncia es parte de lo que identifica a la bici.
- **El DELETE de una bici es un `deactivate()` del lado del backend**, no un borrado: la
  bici sigue existiendo con otro estado, y eso es lo que después le permite al comprador
  reclamarla con el número de serie. La confirmación lleva el nombre de la bici y no un
  "¿estás seguro?" genérico, porque el paso previo es un tap en una lista donde dos bicis
  de la misma marca se ven casi iguales.

### El lado del informante

- **Las dos pantallas de pistas se abren sin sesión, por deep link.** Quien las usa es un
  tercero que escaneó un cartel: no tiene cuenta, no conoce la app y no le debe nada a
  nadie. Hay un solo campo obligatorio, la descripción; ni el punto del mapa se exige,
  porque alguien que vio la bici pasar y no sabe marcar la esquina igual tiene algo que
  aportar. El contacto es opcional y va **explicado**: la aclaración de que el dueño decide
  (nunca al revés) es lo que separa dejar un teléfono de no dejar nada.
- **La calle sólo viaja si la confirman.** El backend respeta la dirección que le mandan y
  no la vuelve a geocodificar, así que mandar una que nadie miró fijaría como cierta una
  adivinanza de OSM sobre el dato que después lee la policía. Sin confirmar viajan sólo las
  coordenadas.
- **La conversación es deliberadamente pobre** comparada con un chat: sin adjuntos, sin
  estados de lectura, sin "escribiendo". El canal existe para que el dueño pueda pedir un
  detalle más, no para que dos desconocidos negocien.
- **Se entrega el `conversationToken`.** El backend lo devolvía en la respuesta del POST
  desde siempre y el front web lo tiraba, así que el dueño podía escribir en un hilo que el
  otro extremo no tenía forma de abrir. Ahora aparece en la pantalla de éxito.

### Presentación

- **Sin dynamic color**: la paleta crema/dorada es identidad de marca.
- **El tema son tres opciones, no dos.** Con sólo claro/oscuro hay que elegir un default
  arbitrario, y ese default le rompe el modo oscuro automático a quien ya lo tiene
  configurado en el teléfono. El desplegable dice además **qué modo rige**, no sólo cuál
  está elegido: con "Automático" —que es el default— no son la misma información.
- **La preferencia de tema va en un DataStore propio**, no en el de la sesión:
  `TokenStore.clear()` vacía su almacenamiento entero al cerrar sesión, así que guardarla
  ahí hacía que la app volviera al tema del sistema en cada logout. Y `enableEdgeToEdge()`
  se reaplica con la detección forzada, porque decidía los iconos de las barras del sistema
  mirando el tema del dispositivo y no el nuestro.
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
3. **Los links de mail no vuelven a la app.** Verificar la cuenta y elegir la contraseña
   nueva pasan por el navegador; atraparlos necesita un deep link declarado en
   `auth-service`.
4. **El pago corre contra el stub del backend.** `payment-service` en dev aprueba
   cualquier token salvo `reject-token`. Contra Mercado Pago real el token lo tiene que
   generar su SDK del lado del cliente, y eso todavía no está: hoy `cardTokenFor` arma un
   token de juguete. El número de tarjeta ya no sale del teléfono en ninguno de los dos
   casos.
5. **Credenciales de prueba en el código** (`BackendIntegrationTest`). Sólo sirven contra
   un backend local, pero conviene sacarlas a variables de entorno. La cuenta que usan no
   la crea ninguna migración: vive en el postgres de desarrollo y hay que recrearla a mano
   si se rehace la base.
6. **Fechas inconsistentes en el backend**: `media-service` serializa `LocalDateTime` como
   array JSON y el resto como ISO. El cliente tolera ambas (`FlexibleLocalDateTime`), pero
   el arreglo de fondo es del servidor.
