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
            // 10.0.2.2 es el loopback del host visto desde el emulador. En un
            // teléfono físico esto no resuelve: hay que apuntar a la IP de la
            // máquina en la LAN, y para eso está el override en runtime
            // (ApiEnvironment) — el equivalente del localStorage.setItem('apiBase')
            // que usa el front web, que existe porque el DHCP cambia la IP.
            buildConfigField("String", "DEFAULT_API_BASE", "\"http://10.0.2.2:8000\"")
            buildConfigField("String", "DEFAULT_AUTH_SSO_BASE", "\"http://10.0.2.2:8084\"")
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