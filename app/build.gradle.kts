plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    id("com.google.protobuf") version "0.9.4"
}

android {
    namespace = "com.signalquest.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.signalquest.example"
        minSdk = 30
        //noinspection ExpiredTargetSdkVersion If we were going to deploy to Google Play, we would need to update this.
        targetSdk = 32
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    task("testClasses")
}

protobuf {
    protoc {
        val protocPlatform = if (project.hasProperty("protoc_platform")) {
            project.property("protoc_platform") as String
        } else {
            ""
        }
        artifact = "com.google.protobuf:protoc:3.9.2${if (protocPlatform.isNotEmpty()) ":$protocPlatform" else ""}"
    }

    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.activity)
    implementation(files("./libs/signalquest-release.aar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation(libs.datastore)
    implementation(libs.datastore.rxjava3)
    implementation(libs.protobuf.javalite)
}