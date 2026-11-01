// SPDX-License-Identifier: GPL-3.0-only
// AGP 9 builds Kotlin itself; kotlin-android is declared (not applied) only to pick the Kotlin version, which the
// Compose compiler plugin must match.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
