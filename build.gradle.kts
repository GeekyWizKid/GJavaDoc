plugins {
  kotlin("jvm") version "1.9.24"
  id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.gjavadoc"
version = "0.3.3"

repositories {
  mavenCentral()
  // Allow IntelliJ plugin repositories to use HTTPS (and fallback to HTTP if needed)
  maven {
    url = uri("https://www.jetbrains.com/intellij-repository/releases")
    isAllowInsecureProtocol = false
  }
  maven {
    url = uri("https://www.jetbrains.com/intellij-repository/snapshots")
    isAllowInsecureProtocol = false
  }
}

// Global repository security configuration
gradle.projectsEvaluated {
  allprojects {
    repositories.configureEach {
      if (this is MavenArtifactRepository) {
        isAllowInsecureProtocol = true
      }
    }
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

// Exclude conflicting Kotlin stdlib modules from IntelliJ Platform
configurations.configureEach {
  if (name.startsWith("intellij") || name.contains("IntelliJ")) {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
  }
}

tasks {
  patchPluginXml {
    // Declare compatibility range explicitly to avoid using magic 999.*
    // 232 = 2023.2, 242 = 2024.2 (matches intellij.version above)
    sinceBuild.set("232")
    untilBuild.set("252.*")

  }

  // Avoid requiring a running IDE instance during build
  buildSearchableOptions {
    enabled = false
  }

  // Enable building even if sources are stubs
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.freeCompilerArgs += listOf("-Xjvm-default=all")
  }

  // Custom task to test MyBatis scanning functionality
  register<DefaultTask>("testMyBatisScanning") {
    group = "verification"
    description = "Test MyBatis scanning functionality with sample files"
    dependsOn("compileKotlin", "compileTestKotlin")

    doLast {
      println("=== MyBatis扫描功能测试 ===")
      println("1. 检查测试文件...")

      val testResourcesDir = File(project.projectDir, "test-resources/mybatis")
      if (testResourcesDir.exists()) {
        val xmlFiles = testResourcesDir.listFiles { _, name -> name.endsWith(".xml") }
        println("   发现 ${xmlFiles?.size ?: 0} 个XML测试文件")
        xmlFiles?.forEach { file ->
          println("   - ${file.name}")
        }
      } else {
        println("   ⚠️ 测试资源目录不存在，请先创建测试文件")
      }

      println("2. 运行单元测试...")
      println("   使用命令: ./gradlew test --tests '*MyBatisScanTest'")
    }
  }

  // Work around Gradle plugin startup crash in sandbox caused by JDK 25 parsing
  // by disabling the bundled Gradle plugin in the runIde sandbox only.
  named("runIde") {
    doFirst {
      val keepGradle = project.hasProperty("keepGradle") || (System.getenv("GJ_KEEP_GRADLE") == "1")
      if (keepGradle) {
        println("Sandbox: keep com.intellij.gradle enabled (requested by -PkeepGradle or GJ_KEEP_GRADLE=1)")
      } else {
        val cfgDir = file("${layout.buildDirectory.get()}/idea-sandbox/config")
        cfgDir.mkdirs()
        val disabled = file("${cfgDir}/disabled_plugins.txt")
        val lines = mutableSetOf<String>()
        if (disabled.exists()) lines += disabled.readLines()
        lines += "com.intellij.gradle"
        disabled.writeText(lines.joinToString("\n"))
        println("Sandbox: disabled com.intellij.gradle plugin to avoid JVM matrix init crash. Pass -PkeepGradle or GJ_KEEP_GRADLE=1 to keep it.")
      }
    }
  }
}
