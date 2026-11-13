// 标准 Gradle 构建（可选）。需要联网下载 IDE 发行包；
// 离线环境请直接用 build-offline.sh。
plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.tomato"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2021.3")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

intellijPlatform {
    pluginConfiguration {
        id = "com.tomato.worknotes"
        name = "Work Notes"
        version = "0.1.0"
        ideaVersion {
            sinceBuild = "213"
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
