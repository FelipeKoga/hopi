import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    explicitApi()

    // The public API dump that :checkNoopParity compares with hopi-perf's.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    android {
        namespace = "dev.koga.hopi.perf.noop"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // No dependency at all: never hopi-core.
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// The real module's JVM runtime classpath, which DefaultsParityTest loads in a classloader of its
// own to compare the real defaults with the noop's. A separate configuration, passed as a system
// property: the noop's test runtime classpath itself never holds hopi-perf or hopi-core.
val realPerfJvm: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
    }
}

dependencies {
    realPerfJvm(project(":hopi-perf"))
}

tasks.named<Test>("jvmTest") {
    val realClasspath: FileCollection = realPerfJvm
    inputs.files(realClasspath).withNormalizer(ClasspathNormalizer::class).withPropertyName("realPerfJvm")
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dhopi.realPerfClasspath=${realClasspath.asPath}") }
}
