// o Kotlin embutido no AGP 9.4 é 2.2; o Filament 1.77 foi compilado com Kotlin 2.4
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
