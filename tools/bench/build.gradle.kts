// Desktop benchmark harness (not part of the app): runs the same WhisperEngine Kotlin code and JNI bridge
// against a host build of libastra_whisper, with real models, and prints accuracy (WER/CER) and speed.
//   cmake -S engine-whisper/src/main/cpp -B build/native-host -DCMAKE_BUILD_TYPE=Release && cmake --build build/native-host
//   ASTRA_NATIVE_DIR=build/native-host ASTRA_REFERENCE="…" ./gradlew :tools:bench:run --args="model.bin audio.wav"
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    main {
        kotlin.srcDirs(
            rootProject.file("engine-whisper/src/main/kotlin"),
            rootProject.file("scripts/native-smoke"),
        )
    }
}

dependencies {
    implementation(project(":core:transcript"))
    implementation(project(":core:audio"))
    implementation(project(":core:text"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("NativeSmokeKt")
}

tasks.named<JavaExec>("run") {
    val nativeDir = System.getenv("ASTRA_NATIVE_DIR")?.let { rootProject.file(it).absolutePath } ?: ""
    val backendDir = System.getenv("ASTRA_BACKEND_DIR")?.let { rootProject.file(it).absolutePath } ?: ""
    jvmArgs("-Djava.library.path=$nativeDir", "-Dastra.libdir=$backendDir")
    workingDir = rootProject.projectDir
}
