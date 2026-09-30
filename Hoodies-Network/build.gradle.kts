import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    alias(libs.plugins.dokka)
    jacoco
    alias(libs.plugins.git.publish)
    alias(libs.plugins.kotlin.kapt)
}
apply(from = "../jacoco.gradle")

val moduleArtifactId = "hoodies-networkandroid"
val moduleGroupId = "com.gap.androidlibraries"
val versionName = "1.0.1"

android {
    namespace = "com.gap.hoodies_network"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        targetSdk = 32

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            enableAndroidTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.finalizedBy("jacocoTestReport")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/main/kotlin")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        sarifReport = true
        baseline = file("lint-baseline.xml")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Keep the pre-AGP 8 Kotlin module name so internal member names and META-INF/*.kotlin_module stay stable.
tasks.withType<KotlinCompile>().configureEach {
    val variant = Regex("^compile(Debug|Release)Kotlin$").find(name)?.groupValues?.get(1)?.lowercase()
    if (variant != null) {
        compilerOptions.moduleName.set("${project.name}_$variant")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    maxParallelForks = 1
}

publishing {
    publications {
        create<MavenPublication>("aar") {
            groupId = moduleGroupId
            version = versionName
            artifactId = moduleArtifactId
            afterEvaluate {
                artifact(tasks.named("bundleReleaseAar"))
            }
            // generate pom nodes for dependencies
            pom.withXml {
                val dependenciesNode = asNode().appendNode("dependencies")
                configurations["implementation"].allDependencies.forEach { dependency ->
                    if (dependency.name != "unspecified") {
                        val dependencyNode = dependenciesNode.appendNode("dependency")
                        dependencyNode.appendNode("groupId", dependency.group)
                        dependencyNode.appendNode("artifactId", dependency.name)
                        dependencyNode.appendNode("version", dependency.version)
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/gapinc/hoodies-network")
            credentials {
                username = System.getenv("GPR_USER")
                password = System.getenv("GPR_KEY")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.gson)
    implementation(files("libs/http-2.2.1.jar"))
    implementation(files("libs/sun-common-server.jar"))
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.mockito.android)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)
    kapt(libs.androidx.room.compiler)
}

tasks.named<org.jetbrains.dokka.gradle.DokkaTask>("dokkaJavadoc") {
    outputDirectory.set(file("${rootDir}/dokka"))
}

configurations.all {
    resolutionStrategy.force(libs.objenesis.get().toString())
}
