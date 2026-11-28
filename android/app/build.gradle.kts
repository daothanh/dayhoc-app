plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "vn.dayhoc.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "vn.dayhoc.app"
        minSdk {
            version = release(28)
        }
        targetSdk {
            version = release(37)
        }
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "env"
    productFlavors {
        create("development") {
            dimension = "env"
            applicationIdSuffix = ".dev"
            // 10.0.2.2 = máy chạy backend, nhìn từ emulator Android. Máy thật: `adb reverse tcp:3000 tcp:3000`
            // rồi build với `-PapiUrl=http://localhost:3000/v1`.
            val apiUrl = project.findProperty("apiUrl") ?: "http://10.0.2.2:3000/v1"
            buildConfigField("String", "API_URL", "\"$apiUrl\"")
            buildConfigField("boolean", "HTTP_LOGGING", "true")
            // Web client ID của Google (khớp GOOGLE_CLIENT_IDS backend) để lấy id_token gửi lên /auth/sso/google.
            buildConfigField(
                "String",
                "GOOGLE_WEB_CLIENT_ID",
                "\"305225338991-7vf98anmjogm9jksgd9kdije4ah8ncgk.apps.googleusercontent.com\"",
            )
        }
        create("staging") {
            dimension = "env"
            applicationIdSuffix = ".stg"
            buildConfigField("String", "API_URL", "\"https://api-staging.dayhoc.app/v1\"")
            buildConfigField("boolean", "HTTP_LOGGING", "false")
            buildConfigField(
                "String",
                "GOOGLE_WEB_CLIENT_ID",
                "\"753323722874-khh3u3aobf813cib4l2m6bvotebld0q7.apps.googleusercontent.com\"",
            )
        }
        create("production") {
            dimension = "env"
            buildConfigField("String", "API_URL", "\"https://api.dayhoc.app/v1\"")
            buildConfigField("boolean", "HTTP_LOGGING", "false")
            buildConfigField(
                "String",
                "GOOGLE_WEB_CLIENT_ID",
                "\"432762025168-e2lo4mengjqb64e8bc2sdoat1vbqpdl8.apps.googleusercontent.com\"",
            )
        }
    }

    buildTypes {
        release {
            // Chưa cấu hình ký: Owner giữ keystore và tự thêm signingConfig khi phát hành.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
