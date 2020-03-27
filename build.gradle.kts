plugins {
    kotlin("jvm") version "1.3.70"
    application
}

repositories {
    jcenter()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.http4k:http4k-core:3.239.0")
    implementation("org.http4k:http4k-server-jetty:3.239.0")
    implementation("org.http4k:http4k-format-jackson:3.239.0")
    implementation("io.lettuce:lettuce-core:5.2.2.RELEASE")
    implementation("org.apache.commons:commons-pool2:2.4.3")
}

application {
    mainClassName = "bottle.AppKt"
}
