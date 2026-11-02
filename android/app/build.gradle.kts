import java.util.Properties
import java.security.MessageDigest
import groovy.json.JsonSlurper

// Load release keystore values from android/key.properties when available.
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val llamaRuntimeManifest = rootProject.file("../assets/bin/llama_server_manifest.json")
val llamaRuntime = JsonSlurper().parse(llamaRuntimeManifest) as Map<*, *>
val llamaMinSdk = (llamaRuntime["minSdk"] as Number).toInt()
// Opt-in parallel test package: preserves installations signed with a different key.
val miniCpmExperimental = providers.gradleProperty("miniCpmExperimental").orNull == "true"

android {
    namespace = "com.arkanefans.servllama"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.arkanefans.servllama"
        if (miniCpmExperimental) applicationIdSuffix = ".minicpmexperimental"
        manifestPlaceholders["appLabel"] = if (miniCpmExperimental) {
            "ServLlama MiniCPM（实验）"
        } else {
            "ServLlama"
        }
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // Official CPU binaries target API 28; Snapdragon binaries target API 34.
        minSdk = maxOf(28, llamaMinSdk)
        targetSdk = 35
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    packaging {
        jniLibs {
            // llama-server is executed as a child process, so the libraries
            // must exist as real files in nativeLibraryDir.
            useLegacyPackaging = true
            // Preserve the checksum-pinned official runtime byte-for-byte.
            // This also prevents the NDK from stripping Hexagon DSP ELFs.
            keepDebugSymbols += setOf(
                "**/libggml*.so", "**/libllama*.so", "**/libmtmd.so",
                // MNN is stripped by its reproducible native packaging script.
                // Do not change those manifest-pinned bytes during APK assembly.
                "**/libMNN.so", "**/libmnn_engine_jni.so",
                "**/libminicpm-server.so",
            )
        }
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/MANIFEST.MF",
            )
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProperties["storeFile"] as String?
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
            }
            storePassword = keystoreProperties["storePassword"] as String?
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
        }
    }

    buildTypes {
        configureEach {
            // Flutter 3.35 sets build-type ABI defaults, which override the
            // defaultConfig filter. All ServLlama engines require arm64.
            ndk.abiFilters.clear()
            ndk.abiFilters += "arm64-v8a"
        }
        release {
            // Falls back to debug signing until android/key.properties is configured.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

flutter {
    source = "../.."
}

val verifySpeechNative by tasks.registering {
    val manifest = rootProject.file("../native/speech/android-arm64-v8a.json")
    val library = file("src/main/jniLibs/arm64-v8a/libservllama_crispasr.so")
    inputs.files(manifest, library)
    doLast {
        check(library.isFile && manifest.isFile) {
            "Build the pinned CrispASR library with tool/build_speech_native.ps1 before packaging."
        }
        val metadata = JsonSlurper().parse(manifest) as Map<*, *>
        val expected = metadata["library"] as Map<*, *>
        val digest = MessageDigest.getInstance("SHA-256")
        library.inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == expected["sha256"]) {
            "CrispASR binary and build manifest disagree. Rebuild the pinned speech bundle."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifySpeechNative) }

val verifyLlamaNative by tasks.registering {
    val directory = file("src/main/jniLibs/arm64-v8a")
    inputs.file(llamaRuntimeManifest)
    inputs.dir(directory)
    doLast {
        val metadata = JsonSlurper().parse(llamaRuntimeManifest) as Map<*, *>
        val libraries = metadata["libraries"] as List<*>
        check(libraries.isNotEmpty()) { "Prepare the pinned official llama.cpp runtime first." }
        libraries.forEach { item ->
            val record = item as Map<*, *>
            val name = record["name"] as String
            check(name.matches(Regex("lib[A-Za-z0-9_.-]+\\.so"))) { "Invalid native library name" }
            val library = directory.resolve(name)
            check(library.isFile) { "Missing $name; run tool/prepare_llama_runtime.py." }
            val digest = MessageDigest.getInstance("SHA-256")
            library.inputStream().use { stream ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actual == record["sha256"]) { "$name differs from the official runtime manifest." }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyLlamaNative) }

val verifyMiniCpmNative by tasks.registering {
    val manifest = rootProject.file("../assets/bin/minicpm_server_manifest.json")
    val library = file("src/main/jniLibs/arm64-v8a/libminicpm-server.so")
    inputs.files(manifest, library)
    doLast {
        check(manifest.isFile && library.isFile) {
            "Build the pinned MiniCPM runtime before packaging (native/minicpm/README.md)."
        }
        val metadata = JsonSlurper().parse(manifest) as Map<*, *>
        check(metadata["abi"] == "arm64-v8a") { "MiniCPM runtime ABI mismatch" }
        val libraries = metadata["libraries"] as List<*>
        check(libraries.size == 1) { "MiniCPM must package one isolated executable" }
        val record = libraries.single() as Map<*, *>
        check(record["name"] == "libminicpm-server.so") { "Unexpected MiniCPM library" }
        val digest = MessageDigest.getInstance("SHA-256")
        library.inputStream().use { stream ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == record["sha256"] && library.length() == (record["bytes"] as Number).toLong()) {
            "MiniCPM executable and build manifest disagree"
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyMiniCpmNative) }
