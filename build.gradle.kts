plugins {
    id("inmc.paper-plugin")
}

group = "com.inmc.achievements"
version = "1.0.0"

inmc {
    paper = "26.2"
    pluginName = "inmc-achievements"
}

dependencies {
    compileOnly(libs.vault.api) { isTransitive = false }

    // 셰이딩 대상은 bStats 하나뿐이다.
    implementation(libs.bstats.bukkit)
}

tasks.shadowJar {
    // bStats 는 relocate 가 필수다 (가이드 함정 4).
    relocate("org.bstats", "com.inmc.achievements.lib.bstats")
}
