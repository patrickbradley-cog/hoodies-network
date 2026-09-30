plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.grgit)
    alias(libs.plugins.git.publish)
    alias(libs.plugins.binary.compatibility.validator)
}

gitPublish {
    branch.set("gh-pages")
    contents {
        from("dokka/") {
            into("javadoc/")
        }
    }
    preserve {
        include("static/**")
        include("dokka/**")
        include("index.html")
    }
    commitMessage.set("Publishing a new javadoc")
}

tasks.register("describe") {
    doFirst {
        println(grgit.describe())
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
