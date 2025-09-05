plugins {
  kotlin("jvm") version "1.9.24"
  id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.gjavadoc"
version = "0.2.1"

repositories {
  mavenCentral()
  maven {
    url = uri("https://maven.aliyun.com/nexus/content/groups/public/")
  }
}

kotlin {
  jvmToolchain(17)
}

intellij {
  // Target IDE and bundled plugins
  // Use a modern IDE platform for better forward-compatibility (242 = 2024.2)
  version.set("2024.2")
  plugins.set(listOf("java"))
}

dependencies {
  // Optional: drop WALA jars into libs/ to enable runtime WALA analysis without Maven Central
  implementation(files("libs/*"))

  // WALA (online via Maven Central). If resolution fails, try 1.5.4.
  implementation("com.ibm.wala:com.ibm.wala.core:1.6.0")
  implementation("com.ibm.wala:com.ibm.wala.util:1.6.0")
  implementation("com.ibm.wala:com.ibm.wala.shrike:1.6.0")

  // Tests for small utilities (no IntelliJ APIs involved)
  testImplementation(kotlin("test"))
  testImplementation("junit:junit:4.13.2")
}

tasks {
  patchPluginXml {
    // Declare compatibility range explicitly to avoid using magic 999.*
    // 232 = 2023.2, 242 = 2024.2 (matches intellij.version above)
    sinceBuild.set("232")
    untilBuild.set("999.*")

  }

  // Avoid requiring a running IDE instance during build
  buildSearchableOptions {
    enabled = false
  }

  // Enable building even if sources are stubs
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.freeCompilerArgs += listOf("-Xjvm-default=all")
  }

  // Work around Gradle plugin startup crash in sandbox caused by JDK 25 parsing
  // by disabling the bundled Gradle plugin in the runIde sandbox only.
  named("runIde") {
    doFirst {
      val cfgDir = file("${buildDir}/idea-sandbox/config")
      cfgDir.mkdirs()
      val disabled = file("${cfgDir}/disabled_plugins.txt")
      val lines = mutableSetOf<String>()
      if (disabled.exists()) lines += disabled.readLines()
      lines += "com.intellij.gradle"
      disabled.writeText(lines.joinToString("\n"))
      println("Sandbox: disabled com.intellij.gradle plugin to avoid JVM matrix init crash")
    }
  }
}
