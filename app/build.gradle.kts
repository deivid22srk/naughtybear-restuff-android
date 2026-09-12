plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.deivid22srk.restuff"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deivid22srk.restuff"
        minSdk = 26
        targetSdk = 35
        // versionCode acompanha o número do run do GitHub Actions: cada build
        // nova instala por cima da anterior (in-place upgrade).
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"

        // ABI única: o recomp (SIMDE/NEON) + SDL3 + Vulkan visam arm64 moderno.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // A lib nativa (librestuff.so) é construída por scripts/build_android_native.sh
    // (CMake + NDK, incluindo codegen do recomp) e empacotada de jniLibs.
    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }

    // Keystore de debug VERSIONADO no repo (keystore/debug.keystore, senha
    // padrão "android"): todas as builds de CI saem com a MESMA assinatura,
    // então o usuário atualiza o app por cima sem desinstalar — preserva os
    // dados e a permissão SAF da pasta do jogo. (Keystore de debug não é
    // segredo; nunca use esta configuração para publicar na Play Store.)
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // OBRIGATÓRIO p/ AdrenoTools (driver Turnip custom): os hooks
            // (libmain_hook.so etc.) precisam existir COMO ARQUIVOS em
            // nativeLibraryDir — com descompactação desligada o Android lê
            // os .so direto do APK e o hookLibDir do adrenotools não aponta
            // para arquivos reais (o hook falha e o driver do sistema é
            // usado em silêncio, ou 0 devices são enumerados).
            useLegacyPackaging = true
            // ARM PERF/diagnóstico: preserva as line tables (-gline-tables-
            // only do alvo restuff) no APK. Sem isto o llvm-strip do AGP pode
            // remover .debug_line da lib embalada — e o CI só distribui APK,
            // o .so não-stripado ficaria inacessível. Com as tabelas no APK,
            // qualquer backtrace "module+0xOFFSET" do crash handler vira
            // arquivo:linha offline via llvm-symbolizer contra o próprio APK.
            keepDebugSymbols += "**/librestuff.so"
        }
    }
}

dependencies {
    // Núcleo Android
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Jetpack Compose (BOM alinha todas as versões)
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    // Ícones completos (Folder, ErrorOutline etc.) — core tem só o subconjunto básico
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ViewModel + ciclo de vida Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Navegação entre a tela de seleção e a tela de Configurações
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // DocumentFile — navegação em pastas via Storage Access Framework
    implementation("androidx.documentfile:documentfile:1.0.1")
}
