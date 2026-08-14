plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "pbis.bike.finder"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "pbis.bike.finder"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // `localhost` desde el dispositivo, resuelto por `adb reverse`:
            //
            //   adb reverse tcp:8000 tcp:8000
            //   adb reverse tcp:8084 tcp:8084
            //
            // Es lo que hace que el teléfono conectado por USB alcance el backend
            // que corre en esta máquina. Se eligió sobre las dos alternativas:
            //
            //   - `10.0.2.2` sólo existe dentro del emulador; en un teléfono real
            //     no resuelve nada.
            //   - La IP de la LAN funciona, pero hay que perseguirla: el DHCP se
            //     la cambia al router y entonces la app deja de conectar sin que
            //     nada haya cambiado en el código. Es exactamente la queja que
            //     motivó el override por `localStorage` en el front web.
            //
            // `adb reverse` no depende de wifi ni de IPs, y sirve igual en el
            // emulador. El precio es que hay que volver a correrlo cada vez que se
            // reconecta el dispositivo — si la app no conecta, es lo primero a
            // revisar. Para apuntar a otro backend sin recompilar está el override
            // en runtime de ApiEnvironment.
            buildConfigField("String", "DEFAULT_API_BASE", "\"http://localhost:8000\"")
            buildConfigField("String", "DEFAULT_AUTH_SSO_BASE", "\"http://localhost:8084\"")
        }
        release {
            optimization {
                enable = false
            }
            // Placeholders: no hay entorno productivo todavía. Con HTTPS, además,
            // hay que revisar la política de cleartext del manifest.
            buildConfigField("String", "DEFAULT_API_BASE", "\"https://api.bikefinder.invalid\"")
            buildConfigField("String", "DEFAULT_AUTH_SSO_BASE", "\"https://auth.bikefinder.invalid\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // kotlinx-datetime se apoya en java.time, que recién existe en API 26.
        // Con minSdk 24 hace falta desugaring o revienta en runtime en los dos
        // niveles más viejos que soportamos.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.coil.compose)
    // Mapa. Son los mismos tiles de OpenStreetMap que usa el Leaflet del front
    // web, y a diferencia de Google Maps no pide API key ni un proyecto con
    // facturación habilitada.
    implementation(libs.osmdroid.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    ksp(libs.hilt.compiler)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}