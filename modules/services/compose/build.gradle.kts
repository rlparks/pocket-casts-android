plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "au.com.shiftyjelly.pocketcasts.compose"
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    ksp(libs.showkase.processor)

    api(libs.showkase)
    api(libs.compose.material3.adaptive)

    api(projects.modules.services.model)
    api(projects.modules.services.preferences)
    api(projects.modules.services.repositories)
    api(projects.modules.services.ui)

    implementation(platform(libs.compose.bom))

    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.compose.activity)
    implementation(libs.compose.animation)
    implementation(libs.compose.material)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.ui.util)
    implementation(libs.fragment.compose)
    implementation(libs.lottie)
    implementation(libs.lottie.compose)
    implementation(libs.navigation.compose)
    implementation(libs.reorderable)

    implementation(projects.modules.services.images)
    implementation(projects.modules.services.localization)
    implementation(projects.modules.services.utils)
}
