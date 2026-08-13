# Mapa de API y modelos — BikeFinder

Contratos verificados contra los DTOs del backend:

- `/home/juan/java-code/api-gateway`
- `/home/juan/java-code/auth-service`
- `/home/juan/java-code/bike-stolen-finder` (12 servicios)

Los tipos son los de los `record` de Java. Ya **no** quedan campos deducidos del consumidor:
donde el front web lee un subconjunto, se marca *(el front ignora …)* para que en Android se
decida a conciencia, no por omisión.

## Hosts

| Base | Origen | Uso |
|---|---|---|
| `API` | `{protocol}//{host}:8000` | Gateway. Todo salvo el arranque de OAuth. |
| `AUTH_SSO_BASE` | `{protocol}//{host}:8084` | auth-service directo, **solo** `/oauth2/authorization/*`. |

## Convenciones transversales

- **Auth**: `Authorization: Bearer {accessToken}`.
- **Refresh**: ante `401`, renovar una vez y reintentar. `4xx` del refresh = sesión muerta;
  `5xx`/`429`/error de red = **no tocar la sesión**.
- **Errores: hay dos formas, no una.** auth-service responde
  `{ status, code, message, timestamp, exception, rootCause }` — **sin** campo `error`.
  theft-report y el gateway responden `{ error, message, timestamp }` — sin `code`.
  Verificado contra el backend corriendo, no deducido. `code`
  (ej. `INVALID_CREDENTIALS`) es el único discriminador legible por máquina: ramificar
  por él y no por el texto de `message`, que está en español. Al usuario, la primera
  frase de `message`.
- **`503` del gateway**: campo `retry` = `safe` | `same-idempotency-key` | `unsafe`.
  Ausente ⇒ asumir `unsafe`.
- **Idempotencia**: `X-Idempotency-Key` en pagos; misma clave en todos los reintentos.
- **`X-User-Id` / `X-User-Email` / `X-User-Role`**: los **inyecta el gateway** desde el JWT
  y **borra incondicionalmente** los que mande el cliente
  (`JwtAuthenticationFilter.HEADERS_QUE_NO_ESCRIBE_EL_CLIENTE`). El `X-User-Id` que manda
  hoy el front es residuo inofensivo. **En Android: no mandarlo.**
- **Tipos**: los IDs de usuario/bici/reporte/pista son `UUID` (string); los de catálogo y
  geografía son `Long`/`Integer`. Fechas: `LocalDate` (`yyyy-MM-dd`), `Instant` (ISO-8601 con
  zona) y `LocalDateTime` (ISO-8601 **sin** zona) conviven — ver "Riesgos".

---

## 1. Autenticación — `auth-service`

| Método | Ruta | Auth | Body |
|---|---|---|---|
| POST | `/auth/login` | — | `{ email, password }` |
| POST | `/auth/register` | — | `{ email, password, fullName }` — password 8..100 |
| POST | `/auth/refresh` | — | `{ refreshToken }` |
| POST | `/auth/logout` | Bearer | `{ refreshToken }` → **204 sin cuerpo** |
| GET | `/auth/me` | Bearer | → `UserInfo` |
| PUT | `/auth/me` | Bearer | `UpdateProfile` → `UserInfo` |
| POST | `/auth/verify-email` | — | `{ token }` |
| POST | `/auth/resend-verification` | — | `{ email }` |
| POST | `/auth/reset-password/request` | — | `{ email }` |
| POST | `/auth/reset-password/confirm` | — | `{ token, newPassword }` |
| GET | `/oauth2/authorization/google` | — | **:8084**, redirección de navegador |

**`AuthResponseDto`** (login / register / refresh):
```kotlin
accessToken: String
refreshToken: String
tokenType: String        // el front lo ignora y hardcodea "Bearer"
expiresIn: Long          // el front lo ignora  ← ver nota
expiresAt: Instant       // el front lo ignora  ← ver nota
user: UserInfo
```
`expiresIn`/`expiresAt` cambian el diseño en Android: el front renueva **reactivamente**
(espera el 401 y reintenta). Con la expiración conocida se puede renovar antes de que
venza y ahorrar el round-trip fallido en cada arranque.

**`UserInfo`** — es el DTO de `/auth/me` **y** el anidado en el login (mismo record):
```kotlin
id: String               // UUID
email: String
emailVerified: Boolean?
fullName: String?
phoneNumber: String?
phoneVerified: Boolean?  // el front lo ignora
avatarUrl: String?       // el front lo ignora — hoy muestra avatares estáticos
gender: String?          // MALE | FEMALE | ALIEN | PREFER_NOT_TO_SAY
birthDate: LocalDate?
location: Location?
```
```kotlin
// UserInfo.Location — NO es la misma Location que las de robo/pista
localityId: Int?
localityName, departmentName, provinceName, countryName: String?
```

**`role` no está en el DTO.** El rol viaja **solo** como claim del JWT; el front lo lee
decodificando el token. En Android hay que hacer lo mismo (o pedirlo al backend).

**`UpdateProfile`** (PUT `/auth/me`) — todo opcional, `null` = "no tocar":
```kotlin
fullName: String?
phoneNumber: String?     // regex E.164: ^\+[1-9]\d{1,14}$  → validar en cliente
gender: String?          // MALE | FEMALE | ALIEN | PREFER_NOT_TO_SAY
birthDate: LocalDate?
localityId: Int?
localityName, departmentName, provinceName, countryName: String?
```

---

## 2. Bicicletas — `bike-registration`

| Método | Ruta | Body → Respuesta |
|---|---|---|
| GET | `/api/v1/bicycles` | → `{ bicycles: [BicycleSummary], total }` |
| GET | `/api/v1/bicycles/{id}` | → `Bicycle` |
| DELETE | `/api/v1/bicycles/{id}` | baja por venta |
| POST | `/api/v1/bicycles/from-catalog` | `RegisterFromCatalog` → `Bicycle` |
| POST | `/api/v1/bicycles/manual` | `RegisterManually` → `Bicycle` |
| PATCH | `/api/v1/bicycles/{id}/components` | `{ components }` |
| GET | `/api/v1/bicycles/{id}/photos` | → `{ photos, total }` |
| POST | `/api/v1/bicycles/{id}/photos` | multipart → `PhotoUpload` |
| POST | `/api/v1/bicycles/{id}/report-theft` | `ReportTheft` → `TheftReport` |

**Ojo con las dos formas**: la lista devuelve `BicycleSummary`, **plana**; el detalle devuelve
`Bicycle`, con marca/modelo/año **anidados en `frame`**. No es el mismo modelo.

```kotlin
// BicycleSummary (lista)
id: String; brandName, model, serialNumber, primaryColor: String?
year: Int?; status: BicycleStatus; updatedAt: Instant

// Bicycle (detalle)
id, ownerId: String                  // UUID
registrationType: CATALOG | MANUAL
catalogBikeId, selectedColorwayId, bikeTypeId: Long?
frame: FrameInfo
colors: Colors
components: Map<String, Any>
originalComponents: Map<String, Any> // el front lo ignora  ← ver nota
detailedSpecs: Map<String, Any>      // el front lo ignora
distinguishingMarks: List<Map>       // el front lo ignora  ← ver nota
photos: List<Map>                    // el front lo ignora: repide /photos
purchaseInfo: PurchaseInfo
notes: String?
status: BicycleStatus
createdAt, updatedAt: Instant

FrameInfo:    brandId: Long?, brandName, model, size, serialNumber: String?, year: Int?
Colors:       primaryColorId/secondaryColorId/accentColorId: Long?,
              primaryColor/secondaryColor/accentColor: String?,
              primaryColorCustom: String?, description: String?
PurchaseInfo: purchaseDate: LocalDate?, purchasePrice, estimatedCurrentValue: BigDecimal?,
              currency: String?, purchaseMethod: PurchaseMethod?,
              purchaseReceiptUrl/Data/MimeType
```

**`distinguishingMarks`** ("marcas distintivas") existe en el modelo y el front **nunca lo
usa**: no hay UI para cargarlas ni para verlas. En una app de identificación de bicis robadas
eso es funcionalidad de producto tirada. Vale la pena decidir si Android la implementa.

**`originalComponents`** lo devuelve el backend, y sin embargo el diff de procedencia
(`isOriginal`, `source`, `originalBrand/Model`, `updatedAt`) lo calcula **el cliente** en
`actualizar-componentes.js:120-175` y lo manda ya resuelto en el PATCH. Portar esa lógica
literal o el historial se corrompe. Es el mejor candidato a moverse al backend.

**Enums**:
```
BicycleStatus:  ACTIVE | STOLEN | SOLD | INACTIVE   (solo ACTIVE se puede editar/vender/denunciar)
RegistrationType: CATALOG | MANUAL
PurchaseMethod: PHYSICAL_STORE_NEW | ONLINE_BRAND_OFFICIAL | ONLINE_MARKETPLACE_RETAILER
              | ONLINE_MARKETPLACE_PRIVATE | SECOND_HAND_PRIVATE | GIFT
              | CORPORATE_LEASING | OTHER          (traen displayName en español)
```

**Requests de alta** — además de lo que manda el front, ambas aceptan campos que ignora:
`componentOverrides` (catálogo), `components`/`detailedSpecs`/`colorDescription` (manual), y
`purchaseReceiptUrl` / `purchaseReceiptData` / `purchaseReceiptMimeType` (ambas). Ese último
grupo es un adjunto de comprobante de compra que la app podría capturar con la cámara.

**Fotos** — `multipart/form-data`:
```
file, photoType, setAsPrimary, gpsAnalysisConsent
PhotoType: GENERAL | FRONT | SIDE_LEFT | SIDE_RIGHT | SERIAL_NUMBER | DETAIL | DAMAGE | RECEIPT
```
```kotlin
// PhotoResponse
id, bicycleId: String; fileName, contentType, description, downloadUrl: String?
fileSizeBytes: Long; photoType: PhotoType; isPrimary: Boolean
uploadedAt: LocalDateTime
exif: Exif?    // latitude, longitude, dateTime, cameraMake, cameraModel, orientation
```
El backend **ya devuelve el EXIF parseado**, incluido GPS. El front nunca lo muestra. Subida
con concurrencia 3 y tolerancia a fallas parciales: la bici ya existe cuando las fotos suben.

---

## 3. Catálogo — `bike-registration`

**Público**: `/api/v1/catalog/**` está en los `public-paths` del gateway
(`application.yml:658`) y responde 200 sin token — verificado. El front web le manda
Bearer igual (usa `fetchWithAuth`), lo cual es inofensivo pero innecesario: el catálogo
se puede precargar antes de que el usuario tenga sesión.

| Método | Ruta |
|---|---|
| GET | `/api/v1/catalog/form-data` |
| GET | `/api/v1/catalog/brands/{brandId}/bikes` |
| GET | `/api/v1/catalog/bikes/{catalogBikeId}` |
| GET | `/api/v1/catalog/size-systems/{sizeSystemId}/sizes` |

```kotlin
InitialFormData: frameBrands: [Brand], bikeTypes: [BikeType], colors: [Color],
                 speedConfigs: [SpeedConfig]      // el front ignora speedConfigs
Brand:      id: Long, name, slug, country, logoUrl      // logoUrl sin usar
BikeType:   id: Long, name, slug, description, iconName, sizeSystemId: Long
Color:      id: Long, name, nameEs, hexCode, colorFamily
CatalogBike: id, brandId, bikeTypeId, sizeSystemId: Long, brandName, modelName,
             bikeTypeName, frameMaterial, groupsetBrand, groupsetModel,
             speedConfig, brakeType: String?, modelYear: Int?
Colorway:   id, catalogBikeId: Long, colorwayCode, colorwayName, finish, imageUrl: String?,
            primaryColor/secondaryColor/accentColor + sus IDs, isDefault: Boolean
FrameSize:  id: Long, sizeCode, sizeLabel: String, sizeCmEquivalent: Double?,
            riderHeightMinCm, riderHeightMaxCm: Int?
CatalogBikeDetails: bike, brand, bikeType, colorways[], availableSizes[], components[]
```
`hexCode` en `Color` permite pintar muestras de color reales en el selector, en vez de la
lista de texto que hay hoy. Candidato #1 a Room: es estático.

---

## 4. Geografía — `location-service` (sin auth)

| Método | Ruta | Respuesta |
|---|---|---|
| GET | `/api/v1/countries` | `{ countries, total }` |
| GET | `/api/v1/countries/{id}/level1` | `{ items, total, countryId }` |
| GET | `/api/v1/level1/{id}/level2` | `{ items, total, … }` |
| GET | `/api/v1/level2/{id}/localities` | `{ items, total, … }` |

```kotlin
Country:     id: Int, name, nameLocal, isoCode2, isoCode3
AdminLevel1: id, countryId: Int, name, isoCode, type, displayOrder: Int
AdminLevel2: id, adminLevel1Id: Int, name, type
Locality:    id, adminLevel2Id: Int, name, type, postalCode,
             latitude, longitude: Double?
```
`Locality` trae **lat/lng**: se puede centrar el mapa al elegir localidad sin geocoding.
También existe una búsqueda por texto (`SearchResultsResponse` con `LocalityFull`, que trae
la jerarquía completa desnormalizada) — mejor UX que cuatro selects encadenados en móvil.

---

## 5. Denuncias — `theft-report`

| Método | Ruta |
|---|---|
| GET | `/api/v1/my-theft-reports` → `{ reports, total }` |
| GET | `/api/v1/my-theft-reports/tips/unread-count` → `{ total, porReporte }` |
| GET | `/api/v1/theft-reports/{id}/pdf/generate` → `PdfGenerated` |
| GET | `/api/v1/stolen-bikes/{reportId}/pdf/generate` | variante pública |

**`ReportTheft`** (POST a `/api/v1/bicycles/{id}/report-theft`) — con los límites del backend:
```kotlin
theftDate: LocalDate
theftTimeApprox: String?     // max 50
theftLocation: TheftLocation?
theftDescription: String?    // max 5000
contactPhone: String?        // max 50
contactEmail: String?        // @Email, max 255
contactPublic: Boolean
rewardOffered: Boolean
rewardAmount: BigDecimal?    // >= 0, 10 enteros + 2 decimales
rewardCurrency: String?      // ISO 4217, ^[A-Z]{3}$
```

**`TheftLocation`** (compartida por denuncia y pista):
```kotlin
localityId: Int?
streetType: String?      // max 20
streetName: String?      // max 200
streetNumber: String?    // max 20
intersection: String?    // max 200
reference: String?       // max 500
latitude: Double?        // -90..90    ← validado en backend
longitude: Double?       // -180..180
precision: String?       // max 20; "EXACT" en pistas
// solo en respuesta:
formattedAddress: String?    // el front lo ignora y arma la dirección a mano
```

**`TheftReport`** (respuesta):
```kotlin
id, bicycleId, reportedBy: String   // UUID
status: ACTIVE | FOUND | CLOSED
theftDate: LocalDate; theftTimeApprox: String?
theftLocation: TheftLocation?; theftDescription: String?
contact: { phone, email, isPublic }
reward:  { offered, amount, currency, formatted }
reportedAt, updatedAt: Instant; foundAt, closedAt: Instant?
sightingsCount: Int                  // el front lo ignora
```
El front solo lee `id`, `status`, `theftDate`, `bicycleId`: **ignora `foundAt`, `closedAt` y
`sightingsCount`**, y por eso no distingue visualmente una denuncia FOUND de una CLOSED.

**PDF** — `PdfGenerated`: `presignedUrl`, `version: Int`, `wasRegenerated: Boolean`,
`fileSizeBytes: Long`. Hay además historial de versiones (`PdfVersionResponse` con
`isStale`, `expiresAt`) que el front no expone. En Android: `DownloadManager` + la
`presignedUrl`. `isStale` permite avisar que el PDF quedó viejo tras editar la denuncia.

También existen `UpdateTheftDetails`, `UpdateContact`, `UpdateReward` y `AddSighting`:
**editar una denuncia ya creada no está implementado en el front**, y el backend lo soporta.

---

## 6. Pistas y conversaciones — `theft-report`

**Lado dueño** (Bearer), bajo `/api/v1/theft-reports/{reportId}/tips`:
`GET …/tips` · `GET …/tips/stats` · `GET …/tips/{tipId}` · `GET …/tips/{tipId}/messages` ·
`POST …/tips/{tipId}/mark-read` · `POST …/tips/{tipId}/convert-to-sighting` ·
`POST …/tips/{tipId}/messages`

**Lado informante** (público, token en la URL):
`GET /api/v1/tips/{token}/info` · `POST /api/v1/tips/{token}` ·
`GET|POST /api/v1/conversations/{token}`

```kotlin
SubmitTip:  sightingDate: LocalDate, sightingTimeApprox: String? (max 50),
            sightingLocation: TheftLocation?, description: String (obligatorio, max 5000),
            informantContact: String? (max 255, sin validación de formato a propósito),
            wantsReply: Boolean

TipSubmitted: tipId: String, conversationToken: String, message: String
Tip:        id, theftReportId: String, sightingDate: LocalDate,
            sightingTimeApprox, locationDescription, description, status: String?,
            canReply: Boolean, submittedAt: LocalDateTime, readAt: LocalDateTime?,
            latitude, longitude: Double?, informantContact: String?
TipList:    { tips: [Tip], total: Int, unread: Int }
TipStats:   { total, unread, replied, converted }
Message:    id, tipId: String, senderType: String, message: String,
            sentAt: LocalDateTime, readAt: LocalDateTime?, isRead: Boolean
Conversation: { tipId, messages: [Message], totalMessages, unreadCount, canReply }

TipStatus: NEW | READ | REPLIED | CONVERTED_TO_SIGHTING
```

Dos reglas de negocio que hay que respetar en la UI:

1. **`informantContact` solo viene si la denuncia ofrece recompensa** (`OwnerTipController`);
   `null` en cualquier otro caso. Es dato externo sin verificar — el DTO pide explícitamente
   mostrarlo con disclaimer.
2. **`TipSubmitted.conversationToken` lo descarta el front.** El informante envía la pista y
   nunca recibe el link para seguir la conversación, aunque el backend se lo dio. Es un bug
   de producto: `wantsReply` queda medio muerto. En Android, guardarlo y ofrecer el hilo.

`POST /api/v1/tips/{token}` responde **429** con `message` propio ante rate limit.
Los endpoints por token son App Links naturales.

---

## 7. Pagos — `payment-service`

`POST /api/v1/payments` — Bearer + `X-Idempotency-Key`.

```kotlin
CreatePayment:
  externalOrderId: String       // obligatorio; derivarlo de la idempotency key
  amount: BigDecimal            // >= 0.01, 15 enteros + 2 decimales
  currency: String              // ISO 4217
  payerEmail: String            // @Email
  description: String           // max 255
  cardToken: String             // tokenizado client-side
  paymentMethodId: String       // "visa" | "master"
  installments: Int             // 1..48   ← el front siempre manda 1

PaymentResponse:
  paymentId, externalOrderId, status, currency, payerEmail, description: String
  amount: BigDecimal
  gatewayReference, failureReason: String?
  createdAt, updatedAt: Instant

PaymentStatus: PENDING | PROCESSING | COMPLETED | FAILED | CANCELLED
```
`PROCESSING` no es terminal: el cliente **no puede asumir** que la respuesta trae el
resultado final. El front hoy no maneja ese estado. En Android hace falta polling del pago o
esperar el evento — es un caso real, no teórico, con un gateway de pagos de por medio.

`installments` acepta hasta 48 y el front fuerza 1: cuotas es una feature disponible sin
tocar backend.

Planes (Vigía 9.99/2m, Sabueso 18.99/4m, Comando 26.99/6m) están **hardcodeados en el
front** — no hay endpoint que los sirva. Duplicarlos en Android es un segundo lugar donde
cambiar un precio.

---

## 8. Notificaciones — `notification-service`

`GET|PUT /api/v1/notification-preferences` · `POST …/unsubscribe`

```kotlin
UpdateRequest: emailEnabled: Boolean, whatsappNumber: String?, whatsappEnabled: Boolean,
               telegramChatId: String?, telegramEnabled: Boolean, locale: String?  // def. es-AR
Response:      userId: String, email: String, …los mismos…, anyChannelEnabled: Boolean
```
El PUT **reemplaza el estado completo**, no parchea. El email **no se manda**: el backend lo
toma de `X-User-Email` que inyecta el gateway desde el token, "para que nadie pueda derivar
sus avisos a una casilla ajena".

`anyChannelEnabled: false` ⇒ los avisos se registran como SKIPPED y no se envía nada. El
front no lo muestra: el usuario puede quedarse sin notificaciones sin enterarse. En Android
conviene advertirlo. Cuando entre FCM, sumar un canal `push` acá.

---

## 9. Dashboard y archivos

`GET /api/dashboard/usuario/resumen` · `GET /api/files/download?fileKey={urlEncoded}`

```kotlin
ResumenUsuario:
  totalBicicletas, totalComponentes, totalReportesActivos: Int
  estadoCuenta: String                  // hoy siempre "Activa"
  bicicletas: [BicicletaResumen]
BicicletaResumen: id, marca, modelo, año, estado: String, totalComponentes: Int
```
Este DTO está **en español** y es plano, a diferencia de todo el resto de la API. Es un
agregador con su propio contrato: en Android va como modelo aparte, sin intentar unificarlo
con `Bicycle`.

---

## 10. Admin — *fuera del alcance de la app*

`/admin/reviews/…`, `/api/dashboard/admin/resumen`, `/api/dashboard/fraude/…`
(`FraudReviewStatus: PENDING | APPROVED | REJECTED`). Que sigan siendo web.

---

## Resumen

- **~50 endpoints**; **~40** entran en la app.
- **12 servicios** en `bike-stolen-finder`, más auth-service y el gateway.
- **~25 modelos** (más de los ~15 que estimé leyendo solo el front: la lista y el detalle de
  bici son modelos distintos, y varias respuestas son wrappers con `total`).
- **3 modos de autenticación**: Bearer, token público en URL, y sin auth (geografía).

## Estado: modelos generados

Los DTOs Kotlin están en `app/src/main/java/pbis/bike/finder/data/remote/dto/`, derivados de
los `record` de Java. Cada archivo cita la clase de la que sale. Compilan y tienen 12 tests
de contrato en `app/src/test/.../DtoContractTest.kt` (todos en verde).

| Archivo | Cubre |
|---|---|
| `ApiConventions.kt` | Json compartido, zona horaria, montos, `ApiErrorDto`, `RetryAdvice` |
| `AuthDto.kt` | login, registro, refresh, perfil, `Gender`, regex E.164 |
| `BicycleDto.kt` | alta, detalle, resumen, componentes, fotos, EXIF, enums |
| `CatalogDto.kt` | marcas, tipos, colores, modelos, colorways, talles |
| `GeoDto.kt` | los 4 niveles + búsqueda por texto |
| `TheftDto.kt` | denuncia, ubicación, PDF, bicis robadas |
| `TipDto.kt` | pistas, stats, conversación, mensajes |
| `PaymentDto.kt` | pago, `PaymentStatus`, planes |
| `NotificationDto.kt` | preferencias por canal |
| `DashboardDto.kt` | resumen del agregador |

**No están generados con openapi-generator.** springdoc existe, pero bajar los specs exige
levantar el stack (Kafka, Elasticsearch, Selenium) y el spec sale de esos mismos records. Si
en algún momento se corre el stack completo, conviene bajar los `.json` y enchufar el
generador al build; estos modelos quedarían como referencia de las decisiones de mapeo.

Los payloads de los tests están escritos a mano siguiendo los records: cubren forma y tipos,
no datos reales. Reemplazarlos por respuestas capturadas del stack es lo que convertiría esos
tests en detección real de cambios de contrato.

### Decisiones de mapeo

| Backend | Kotlin | Por qué |
|---|---|---|
| `UUID` | `String` | No hay ganancia en tipar; evita un serializer más |
| `Instant` | `kotlinx.datetime.Instant` | Trae offset, no ambiguo |
| `LocalDateTime` | `kotlinx.datetime.LocalDateTime` + `BackendTimeZone` | Ver abajo |
| `LocalDate` | `kotlinx.datetime.LocalDate` | Es un día, no un instante |
| `BigDecimal` | `String` + `toBigDecimalAmount()` | `Double` redondea plata |
| `Map<String,Object>` | `JsonObject` | Sin esquema en el backend |
| enums | `enum class` | Salvo `gender`, que va como `String` |

`gender` es la excepción: si el backend suma un valor al `@Pattern`, un enum en el DTO de
respuesta haría fallar la deserialización del perfil **entero**. Se valida al construir el
request y se tolera lo que venga al leerlo.

`TZ: America/Argentina/Buenos_Aires` está en el docker-compose de los tres repos, así que los
`LocalDateTime` se resuelven contra esa zona — **no** contra la del dispositivo. En web daba
igual porque el navegador estaba en el mismo huso que el servidor; un teléfono viaja, y con
el celular en España la hora de una pista se correría tres horas. En una denuncia de robo la
hora es evidencia. Sigue siendo una convención de despliegue y no del contrato: si un
servicio se despliega en otra zona, esto miente en silencio.

## Estado: Fase 0 (infraestructura)

Compila, genera APK debug, 20 tests unitarios en verde. **Ninguna pantalla real
todavía**: el grafo de navegación está armado con placeholders.

| Pieza | Dónde | Nota |
|---|---|---|
| Cliente HTTP | `di/NetworkModule.kt` | OkHttp + Retrofit + kotlinx-serialization |
| Base de API en runtime | `data/local/ApiEnvironment.kt` | Override persistido; el DHCP mueve la IP del backend |
| Tokens | `data/local/TokenStore.kt` | DataStore, excluido del backup |
| Renovación de sesión | `data/remote/SessionManager.kt` | `Ok` / `Expired` / `NoNetwork` |
| Bearer + reintento en 401 | `data/remote/AuthInterceptors.kt` | `Authenticator` de OkHttp |
| 8 interfaces de API | `data/remote/api/` | ~40 endpoints |
| Tema M3 | `ui/theme/` | Portado de `theme.css` / `theme-dark.css` |
| Navegación | `ui/navigation/` | 13 rutas tipadas, placeholders |

Decisiones que no son obvias desde el código:

- **`baseUrl` de Retrofit es un placeholder**; el host real lo reescribe un
  interceptor en cada request. Retrofit exige una base fija al construirse, y acá
  la base cambia en runtime.
- **Dos clientes OkHttp**: el del refresh no lleva `Authenticator`, o un 401 en
  `/auth/refresh` dispararía un refresh que dispara otro, sin fondo.
- **`Mutex` en el refresh**: si cinco requests reciben 401 a la vez, sólo una
  renueva. Sin eso se mandan cinco refresh, cuatro con un token que la primera ya
  rotó, y el backend los rechaza — la sesión se cae por intentar salvarla.
- **La red no navega.** `SessionManager` emite un evento y la navegación decide.
  En web esto era un `window.location.href` desde el módulo de sesión.
- **`X-Idempotency-Key` va como parámetro explícito**, no la pone un interceptor:
  tiene que ser la misma en todos los reintentos de un pago, y eso lo sabe quien
  orquesta el pago, no la capa de red.
- **Sin dynamic color**: la paleta crema/dorada es identidad de marca.
- **Logging `BODY` sólo en debug**: el cuerpo de `/auth/login` lleva la contraseña
  y el de `/auth/refresh`, el refresh token.

Deuda abierta de esta fase:

1. **Tokens sin cifrar en reposo.** `EncryptedSharedPreferences` está deprecado;
   cifrar con Keystore es trabajo real. Alternativa: acortar la vida del refresh
   token. Están excluidos del backup, que es lo mínimo.
2. **OAuth social sin resolver.** Sigue siendo el ítem de mayor fricción y toca
   backend y consola de Google.
3. **Tipografía sin portar**: Cormorant Garamond y DM Sans. Hoy se ve con la
   fuente del sistema, o sea sin la identidad de la marca.
4. **Verificado sólo en parte contra el backend corriendo** — ver abajo.

## Backend mínimo para la Fase 1

Levantar el stack completo arrastra Kafka, Elasticsearch (`-Xmx1g`) y Selenium por la
cadena de `dashboard-aggregator`. Para **login → ver-bici** alcanza con:

| Servicio | Estado | Para qué |
|---|---|---|
| `bikefinder-postgres`, `redis` | ya corrían | — |
| `api-gateway`, `auth-service` | ya corrían | login, refresh, perfil |
| `bike-registration` | agregado, `-Xmx256m` | `/api/v1/bicycles`, catálogo |

Se levanta **desde la raíz del repo**, no desde `deploy/docker/`: el `.env` de la raíz
define `COMPOSE_FILE`, y sin él `DB_USER`/`DB_PASSWORD` salen vacías y el servicio entra
en crash-loop con `Unable to determine Dialect without JDBC metadata`.

```
cd /home/juan/java-code/bike-stolen-finder && docker compose up -d bike-registration
```

Lo que **no** funciona con este subconjunto, y hay que tenerlo presente al armar
pantallas: geografía (`location-service`, da 503), fotos de bicis (`media-service`),
denuncias y pistas (`theft-report`), y el resumen del dashboard
(`dashboard-aggregator`).

Smoke test contra el gateway, ya corrido:

| Llamada | Resultado |
|---|---|
| `GET /api/v1/catalog/form-data` | 200 sin token — el catálogo es público |
| `GET /api/v1/countries` | 503 — location-service apagado |
| `POST /auth/login` (credenciales malas) | 401 con `code: INVALID_CREDENTIALS` |

## Estado: Fase 1 (login → ver-bici)

**39 tests en verde, 7 de ellos contra el backend realmente corriendo.** APK debug
generado. La UI **no se ejecutó**: no hay dispositivo ni AVD creado en la máquina.

| Pieza | Dónde |
|---|---|
| `ApiResult` + `apiCall` | `data/remote/ApiResult.kt` |
| Repositorios | `data/repository/` |
| Traducción de errores a texto | `ui/common/ErrorMessages.kt` |
| Login | `ui/login/` |
| Listado de bicicletas | `ui/bikes/` |
| Destino inicial según sesión | `MainViewModel.kt` |

`ApiResult` tiene cuatro casos y no dos: `Success`, `NoNetwork`, `HttpError` y
`Malformed`. El último existe porque "no pude interpretar la respuesta" significa que el
contrato cambió o que el DTO está mal — confundirlo con un error del servidor esconde
justamente el problema que hay que arreglar.

El botón de "Reintentar" sólo aparece cuando reintentar no puede duplicar nada
(`isSafeToRetry`). En un listado siempre se puede, pero la regla se respeta desde la
primera pantalla para que no haya que acordarse en las que sí escriben.

### Hallazgos de correr contra el backend real

1. **`expiresIn` viene en milisegundos**, no en segundos: llega `900000` mientras el JWT
   trae `exp - iat = 900`. Va contra la convención de OAuth 2 (RFC 6749). Leerlo como
   segundos daría una expiración a 10 días y la renovación anticipada nunca se
   dispararía.
2. **El refresh rota los dos tokens.** Guardar sólo el `accessToken` dejaría al siguiente
   refresh usando uno ya invalidado.
3. **Actuator no es alcanzable desde el host, por diseño.** `/actuator/…` está en el
   puerto **9095**, que el compose no publica: sólo se llega desde dentro de
   `bike-network`, de donde scrapea Prometheus
   (`api-gateway/doc/TECHNICAL_INFO.md` §2.6). Desde afuera se inspecciona con
   `docker exec api-gateway wget -qO- localhost:9095/actuator/health`.

   Y aunque estuviera publicado, `/actuator/health` sería mala sonda: es la vista de
   **diagnóstico** y devuelve 503 cuando Redis se cae, a propósito (§5.1). Las sondas
   de vida son `/actuator/health/liveness` y `/actuator/health/readiness`, que sólo
   miran `ping`. El razonamiento del documento es el mismo que se aplica a la sesión en
   [SessionManager]: si el liveness mirara la vista de diagnóstico, una caída de Redis
   reiniciaría un gateway que rutea perfecto — "nos quedamos sin rate limiting" se
   volvería "se cayó la API".

   Por eso el test de integración sondea `/api/v1/catalog/form-data`: prueba que el
   gateway rutee **y** que `bike-registration` esté sirviendo, que es exactamente lo
   que esos tests necesitan.

## Correcciones a lo que dije antes de leer el backend

- **Sí hay OpenAPI.** `springdoc` está en el `pom.xml` de la mayoría de los servicios, con
  configuración en sus `application.yml`. Los specs se sirven en `/v3/api-docs` de cada
  servicio. Se pueden generar los modelos Kotlin con `openapi-generator` en vez de escribirlos
  a mano — mi recomendación anterior de "generar un spec" era innecesaria: ya existe.
- **`X-User-Id` no es un agujero.** El gateway lo borra incondicionalmente antes de rutear,
  precisamente porque `/api/v1/tips/**` y `/api/v1/conversations/**` son rutas públicas.
  El header del front es residuo: en Android, simplemente no mandarlo.

## Riesgos concretos

1. **Tres tipos de fecha conviviendo.** `Instant` (con zona) en bicis, denuncias y pagos;
   `LocalDateTime` (sin zona) en pistas, mensajes y fotos; `LocalDate` en fechas de robo y
   avistamiento. Un `LocalDateTime` sin zona obliga a asumir el timezone del servidor — con
   una app móvil que viaja, eso es un bug esperando. Definir la convención antes de escribir
   los adapters de kotlinx-datetime.
2. **`BigDecimal` en montos.** No mapear a `Double` en Kotlin: usar `String` en el DTO y
   `BigDecimal` en el dominio. En pagos, un redondeo es plata real.
3. **`Map<String, Any>` en componentes.** `components`, `detailedSpecs` y
   `distinguishingMarks` no tienen esquema en el backend. kotlinx.serialization necesita
   `JsonObject` o un modelo propio del cliente. Es la parte del contrato que menos garantías
   da, y encima es donde vive la lógica de procedencia que calcula el cliente.
4. **`PROCESSING` en pagos** (punto 7): la app no puede asumir resultado final en la respuesta.
5. **Bug latente en el front**: `actualizar-componentes.js:63` lee `bikeData.frame.bikeTypeName`,
   que **no existe** en `FrameInfoResponse` — el tipo de bici sale siempre vacío. En Android,
   el nombre hay que resolverlo desde `bikeTypeId` contra el catálogo.
6. **Funcionalidad de backend sin UI**: marcas distintivas, edición de denuncias, historial de
   PDFs, EXIF de fotos, búsqueda de localidad por texto, cuotas de pago. No es deuda de la
   migración, pero es la ocasión para decidir qué entra.
