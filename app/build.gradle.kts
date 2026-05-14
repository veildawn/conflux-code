import java.net.URL
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// Termux bootstrap bundling
//
// Android 10+ blocks exec() on files inside /data/data/<pkg>/files/ unless the
// app is targetSdk <= 28. To run the proot / busybox / bash ELF binaries from
// the Termux bootstrap, we move them into jniLibs (renamed as lib<name>.so),
// which Android then places in applicationInfo.nativeLibraryDir — a directory
// where exec() is still permitted.
//
// The rest of the prefix filesystem (config files, shared libraries, apt
// metadata, SYMLINKS.txt) is packaged as the raw bootstrap zip in assets/ and
// extracted to filesDir/prefix at runtime by PrefixExtractorImpl.
// ---------------------------------------------------------------------------

val termuxBootstrapVersion = "2026.05.10-r1+apt.android-7"
val termuxBootstrapBaseUrl =
    "https://github.com/termux/termux-packages/releases/download/bootstrap-$termuxBootstrapVersion"

// ABIs we ship bootstraps for. Keep in sync with the ndk.abiFilters list below.
val bootstrapAbis = mapOf(
    "arm64-v8a" to "bootstrap-aarch64.zip",
    "armeabi-v7a" to "bootstrap-arm.zip",
    "x86_64" to "bootstrap-x86_64.zip",
    "x86" to "bootstrap-i686.zip",
)

// Binaries we promote from bootstrap bin/ into jniLibs/<abi>/lib<name>.so so
// they live on an exec-allowed filesystem at runtime. The Termux bootstrap
// itself does not include proot; that is installed via apt after the rootfs
// step (milestone 2). For milestone 1 we only need bash + dash to prove the
// exec-from-nativeLibraryDir pipeline works end-to-end. `xz` is required by
// the bundled-rootfs extraction path — tar -J shells out to it.
val execBinariesToExtract = listOf("bash", "dash", "tar", "xz")

// ---------------------------------------------------------------------------
// Termux apt .deb packages (milestone 2). Each ABI gets the proot + libtalloc
// binaries pulled from the Termux stable repo. The archives are extracted at
// build time; their exec-files are promoted into jniLibs so the Android
// linker can load them from nativeLibraryDir.
// ---------------------------------------------------------------------------
val termuxAptMirror = "https://packages.termux.dev/apt/termux-main"
val termuxPackagesByAbi: Map<String, List<Pair<String, String>>> = mapOf(
    // ABI -> list of (short-name, deb-relative-path-under-pool)
    "arm64-v8a" to listOf(
        "proot" to "pool/main/p/proot/proot_5.1.107-71_aarch64.deb",
        "libtalloc" to "pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb",
    ),
    "armeabi-v7a" to listOf(
        "proot" to "pool/main/p/proot/proot_5.1.107-71_arm.deb",
        "libtalloc" to "pool/main/libt/libtalloc/libtalloc_2.4.3_arm.deb",
    ),
    "x86_64" to listOf(
        "proot" to "pool/main/p/proot/proot_5.1.107-71_x86_64.deb",
        "libtalloc" to "pool/main/libt/libtalloc/libtalloc_2.4.3_x86_64.deb",
    ),
    "x86" to listOf(
        "proot" to "pool/main/p/proot/proot_5.1.107-71_i686.deb",
        "libtalloc" to "pool/main/libt/libtalloc/libtalloc_2.4.3_i686.deb",
    ),
)

// Executable files inside the proot deb that need to live in nativeLibraryDir.
// Keep in sync with the runtime dispatch in BootstrapManagerImpl.
val prootExecRenames: Map<String, String> = mapOf(
    "data/data/com.termux/files/usr/bin/proot" to "libproot.so",
    "data/data/com.termux/files/usr/libexec/proot/loader" to "libproot-loader.so",
    "data/data/com.termux/files/usr/libexec/proot/loader32" to "libproot-loader32.so",
)

val downloadCacheDir =
    layout.buildDirectory.dir("termux-bootstrap-cache").get().asFile

val termuxBootstrapAssetsDir =
    layout.buildDirectory.dir("generated/termux/assets").get().asFile
val termuxBootstrapJniLibsDir =
    layout.buildDirectory.dir("generated/termux/jniLibs").get().asFile

// ---------------------------------------------------------------------------
// Pre-baked Ubuntu rootfs (Node.js + Claude CLI already installed). Produced
// offline by `app/build-support/build-rootfs.sh` and checked into
// `app/build-support/prebuilt-rootfs/`. The Gradle task below just copies the
// per-ABI tarballs into assets/ so the runtime can extract them on first boot
// without needing a network download.
//
// If a tarball for an ABI is missing the build still succeeds: the APK
// simply does not bundle that variant, and the runtime falls back to its
// network install path (the pre-existing behaviour).
// ---------------------------------------------------------------------------
val prebuiltRootfsDir = rootProject.file("app/build-support/prebuilt-rootfs")
val prebuiltRootfsAssetsDir =
    layout.buildDirectory.dir("generated/rootfs/assets").get().asFile

val downloadTermuxBootstrap by tasks.registering {
    description = "Downloads Termux bootstrap zips for each supported ABI."
    group = "termux"

    val outputFiles = bootstrapAbis.values.map { File(downloadCacheDir, it) }
    outputs.files(outputFiles)
    outputs.upToDateWhen {
        outputFiles.all { it.exists() && it.length() > 1_000_000 /* > 1 MB sanity */ }
    }

    doLast {
        downloadCacheDir.mkdirs()
        bootstrapAbis.forEach { (_, fileName) ->
            val target = File(downloadCacheDir, fileName)
            if (target.exists() && target.length() > 1_000_000) {
                logger.lifecycle("Termux bootstrap already cached: ${target.name} (${target.length() / 1024} KB)")
                return@forEach
            }
            downloadFileToPath("$termuxBootstrapBaseUrl/$fileName", target)
        }
    }
}

/**
 * Downloads a URL to [target], following redirects and verifying
 * Content-Length. Throws GradleException on failure.
 */
fun downloadFileToPath(url: String, target: File) {
    target.parentFile.mkdirs()
    target.delete()

    val tmp = File(target.parentFile, "${target.name}.part")
    tmp.delete()

    var currentUrl = url
    var redirects = 0
    while (true) {
        if (redirects > 5) throw GradleException("Too many redirects while downloading $url")
        val conn = URL(currentUrl).openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "ClaudeMobile-build/1.0")
        conn.connect()
        val code = conn.responseCode
        if (code in 300..399) {
            val loc = conn.getHeaderField("Location")
                ?: throw GradleException("HTTP $code without Location header for $currentUrl")
            conn.disconnect()
            currentUrl = loc
            redirects++
            continue
        }
        if (code != 200) {
            throw GradleException("HTTP $code fetching $currentUrl")
        }
        val expectedLen = conn.contentLengthLong
        logger.lifecycle("Downloading $url (${if (expectedLen > 0) "${expectedLen / 1024} KB" else "size unknown"})")
        conn.inputStream.use { input ->
            tmp.outputStream().use { out -> input.copyTo(out, 32 * 1024) }
        }
        conn.disconnect()
        if (expectedLen > 0 && tmp.length() != expectedLen) {
            tmp.delete()
            throw GradleException("Short download for ${target.name}: got ${tmp.length()}, expected $expectedLen")
        }
        break
    }
    if (!tmp.renameTo(target)) {
        throw GradleException("Failed to move $tmp to $target")
    }
}

val termuxDebCacheDir = layout.buildDirectory.dir("termux-deb-cache").get().asFile

val downloadTermuxProotDebs by tasks.registering {
    description = "Downloads proot + libtalloc .deb packages for each ABI."
    group = "termux"

    val outputFiles = termuxPackagesByAbi.flatMap { (abi, pkgs) ->
        pkgs.map { (shortName, relPath) ->
            File(termuxDebCacheDir, "$abi/$shortName.deb").also {
                inputs.property("$abi.$shortName", relPath)
            }
        }
    }
    outputs.files(outputFiles)
    outputs.upToDateWhen { outputFiles.all { it.exists() && it.length() > 10_000 } }

    doLast {
        termuxDebCacheDir.mkdirs()
        termuxPackagesByAbi.forEach { (abi, pkgs) ->
            val abiDir = File(termuxDebCacheDir, abi).apply { mkdirs() }
            pkgs.forEach { (shortName, relPath) ->
                val target = File(abiDir, "$shortName.deb")
                if (target.exists() && target.length() > 10_000) {
                    logger.lifecycle("Termux .deb cached: $abi/$shortName.deb (${target.length() / 1024} KB)")
                    return@forEach
                }
                downloadFileToPath("$termuxAptMirror/$relPath", target)
            }
        }
    }
}

val prepareTermuxBootstrap by tasks.registering {
    description = "Splits Termux bootstraps and proot .debs into assets + jniLibs."
    group = "termux"
    dependsOn(downloadTermuxBootstrap, downloadTermuxProotDebs)

    inputs.files(downloadTermuxBootstrap.get().outputs.files)
    inputs.files(downloadTermuxProotDebs.get().outputs.files)
    inputs.file(rootProject.file("app/build-support/extract-deb.py"))
    outputs.dir(termuxBootstrapAssetsDir)
    outputs.dir(termuxBootstrapJniLibsDir)

    doLast {
        termuxBootstrapAssetsDir.deleteRecursively()
        termuxBootstrapJniLibsDir.deleteRecursively()
        termuxBootstrapAssetsDir.mkdirs()
        termuxBootstrapJniLibsDir.mkdirs()

        val debExtractDir = layout.buildDirectory.dir("termux-deb-extracted").get().asFile
        debExtractDir.deleteRecursively()
        debExtractDir.mkdirs()

        // Maps the dynamic-linker "DT_NEEDED" name (e.g. "libreadline.so.8.3")
        // to the packaged jniLibs filename ("libreadline-so-8-3.so"). This map
        // is the same for every ABI; we emit it once into assets/ so the
        // runtime can rebuild prefix/lib/ symlinks that point at the files in
        // applicationInfo.nativeLibraryDir.
        val soNameToJniName = linkedMapOf<String, String>()

        bootstrapAbis.forEach { (abi, fileName) ->
            val zipFile = File(downloadCacheDir, fileName)
            if (!zipFile.exists()) {
                throw GradleException("Missing bootstrap zip: $zipFile")
            }

            // Copy the zip itself into assets/prefix/ so the runtime can pick
            // the archive by ABI. Android packages assets/ as-is (no re-zip).
            val abiAssetsDir = File(termuxBootstrapAssetsDir, "prefix").apply { mkdirs() }
            val targetZip = File(abiAssetsDir, "bootstrap-$abi.zip")
            zipFile.copyTo(targetZip, overwrite = true)

            val abiJniDir = File(termuxBootstrapJniLibsDir, abi).apply { mkdirs() }
            ZipFile(zipFile).use { zip ->
                // 1) Exec binaries → jniLibs as lib<bin>.so
                execBinariesToExtract.forEach { binName ->
                    val entry = zip.getEntry("bin/$binName") ?: return@forEach
                    val out = File(abiJniDir, "lib$binName.so")
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    logger.lifecycle("Staged ${out.name} for $abi (${out.length()} bytes)")
                }

                // 2) Shared libraries from lib/. Android packaging strips any
                //    filename that doesn't match the `lib*.so` pattern, so
                //    versioned names like `libreadline.so.8.3` are rewritten
                //    to `libreadline-so-8-3.so`. The runtime reconstructs the
                //    original name via symlinks.
                val entries = zip.entries().toList()
                for (entry in entries) {
                    if (entry.isDirectory) continue
                    val name = entry.name
                    if (!name.startsWith("lib/")) continue
                    val base = name.removePrefix("lib/")
                    if (base.contains('/')) continue
                    if (!base.contains(".so")) continue

                    val jniName = sanitizeSoName(base)
                    val out = File(abiJniDir, jniName)
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    soNameToJniName.putIfAbsent(base, jniName)
                }
            }

            // 3) Extract proot + libtalloc debs for this ABI and promote
            //    their exec-files into the same jniLibs directory. Shared
            //    libraries (libtalloc.so.*) go through the same sanitize +
            //    symlink pipeline as the bootstrap libs.
            val pkgs = termuxPackagesByAbi[abi].orEmpty()
            pkgs.forEach { (shortName, _) ->
                val deb = File(termuxDebCacheDir, "$abi/$shortName.deb")
                if (!deb.exists()) return@forEach
                val pkgExtractDir = File(debExtractDir, "$abi/$shortName").apply { mkdirs() }
                val debComponentsDir = File(pkgExtractDir, "components").apply { mkdirs() }

                // Run extract-deb.py to split the .ar file.
                val python = findPythonInterpreter()
                val extractScript = rootProject.file("app/build-support/extract-deb.py")
                providers.exec {
                    executable = python
                    args = listOf(extractScript.absolutePath, deb.absolutePath, debComponentsDir.absolutePath)
                }.result.get()

                val dataTar = File(debComponentsDir, "data.tar.xz")
                if (!dataTar.exists()) {
                    throw GradleException("Missing data.tar.xz in $deb")
                }
                val contentsDir = File(pkgExtractDir, "contents").apply { mkdirs() }
                providers.exec {
                    executable = "tar"
                    args = listOf("-xJf", dataTar.absolutePath, "-C", contentsDir.absolutePath)
                }.result.get()

                promoteDebFilesToJniLibs(
                    abi = abi,
                    pkgName = shortName,
                    contentsRoot = contentsDir,
                    abiJniDir = abiJniDir,
                    soNameToJniName = soNameToJniName,
                )
            }

            logger.lifecycle(
                "Packaged ${abiJniDir.listFiles()?.size ?: 0} files into jniLibs/$abi"
            )
        }

        // Emit the name map alongside the bootstrap zips.
        val mapFile = File(termuxBootstrapAssetsDir, "prefix/lib-name-map.txt")
        mapFile.parentFile.mkdirs()
        mapFile.writeText(
            buildString {
                append("# Generated by prepareTermuxBootstrap.\n")
                append("# Format: <dt-needed-name>\\t<jniLibs-filename>\n")
                for ((soName, jniName) in soNameToJniName) {
                    append(soName).append('\t').append(jniName).append('\n')
                }
            }
        )
        logger.lifecycle("Wrote ${soNameToJniName.size} entries to ${mapFile.name}")
    }
}

fun findPythonInterpreter(): String {
    val candidates = listOf("python3", "python")
    for (c in candidates) {
        val r = providers.exec {
            executable = "/usr/bin/env"
            args = listOf(c, "--version")
            isIgnoreExitValue = true
        }
        if (r.result.get().exitValue == 0) return c
    }
    throw GradleException("Neither python3 nor python found on PATH; cannot extract .deb packages.")
}

// ---------------------------------------------------------------------------
// Copy pre-baked rootfs tarballs from app/build-support/prebuilt-rootfs/ into
// assets/rootfs/. Registered unconditionally so it wires into the Android
// asset merge graph; FAILS the build when no tarball is present, since the
// runtime no longer has an online install fallback.
// ---------------------------------------------------------------------------
val bundlePrebuiltRootfs by tasks.registering {
    description = "Copies pre-baked rootfs tarballs into the app assets."
    group = "termux"

    // Declare both the source directory and the set of files inside it as
    // inputs so Gradle properly re-runs the task when a tarball is added,
    // removed, or updated.
    inputs.dir(prebuiltRootfsDir).withPathSensitivity(PathSensitivity.RELATIVE).optional(true)
    outputs.dir(prebuiltRootfsAssetsDir)

    doLast {
        val assetsTargetDir = File(prebuiltRootfsAssetsDir, "rootfs")
        assetsTargetDir.deleteRecursively()
        assetsTargetDir.mkdirs()

        if (!prebuiltRootfsDir.isDirectory) {
            throw GradleException(
                "No prebuilt-rootfs directory at ${prebuiltRootfsDir.path}. " +
                    "Run app/build-support/build-rootfs.sh <abi> first; the runtime " +
                    "no longer has an online install fallback."
            )
        }

        val tarballs = prebuiltRootfsDir.listFiles { f ->
            f.isFile && f.name.startsWith("rootfs-") && f.name.endsWith(".tar.xz")
        }?.sortedBy { it.name } ?: emptyList()

        if (tarballs.isEmpty()) {
            throw GradleException(
                "No prebuilt rootfs tarballs found in ${prebuiltRootfsDir.path}. " +
                    "Run app/build-support/build-rootfs.sh <abi> for every ABI listed " +
                    "in ndk.abiFilters before packaging the APK."
            )
        }

        for (tarball in tarballs) {
            val target = File(assetsTargetDir, tarball.name)
            tarball.copyTo(target, overwrite = true)
            logger.lifecycle(
                "Bundled ${tarball.name} " +
                    "(${tarball.length() / (1024 * 1024)} MB) into assets/rootfs/"
            )
        }

        // Copy manifest.tsv (sha256 + sizes + file count per ABI) alongside
        // the tarballs. Required for the runtime to compute a real-time
        // extraction progress percentage.
        val manifest = File(prebuiltRootfsDir, "manifest.tsv")
        if (!manifest.exists()) {
            throw GradleException(
                "Missing ${manifest.path}. Re-run build-rootfs.sh so it regenerates the manifest."
            )
        }
        manifest.copyTo(File(assetsTargetDir, "manifest.tsv"), overwrite = true)
    }
}

/**
 * Walks a [.deb → data.tar] extraction rooted at [contentsRoot] and copies
 * the files we care about into [abiJniDir].
 *
 * - Exec files listed in [prootExecRenames] are renamed to their canonical
 *   `lib<name>.so` placeholders (so Android's packaging accepts them) and
 *   become executable inside nativeLibraryDir at install time.
 * - Shared libraries under `files/usr/lib/` go through the same
 *   sanitize-and-map pipeline used for the bootstrap's lib/ directory, so
 *   the runtime can rebuild the original versioned filenames via symlinks.
 */
fun promoteDebFilesToJniLibs(
    abi: String,
    pkgName: String,
    contentsRoot: File,
    abiJniDir: File,
    soNameToJniName: MutableMap<String, String>,
) {
    val termuxRoot = File(contentsRoot, "data/data/com.termux/files/usr")
    if (!termuxRoot.exists()) {
        logger.warn("$pkgName: no usr/ root in extracted deb at $termuxRoot")
        return
    }

    var staged = 0
    termuxRoot.walkTopDown().forEach { file ->
        if (!file.isFile) return@forEach
        val rel = file.relativeTo(termuxRoot).invariantSeparatorsPath
        val debRel = "data/data/com.termux/files/usr/$rel"

        // (a) Hardcoded exec-file renames (proot, loaders).
        prootExecRenames[debRel]?.let { renamed ->
            val out = File(abiJniDir, renamed)
            file.copyTo(out, overwrite = true)
            staged++
            logger.lifecycle("Staged ${out.name} for $abi from $pkgName (${out.length()} bytes)")
            return@forEach
        }

        // (b) Shared libraries inside usr/lib.
        if (rel.startsWith("lib/") && !rel.substringAfter("lib/").contains('/')) {
            val base = rel.removePrefix("lib/")
            if (!base.contains(".so")) return@forEach
            val jniName = sanitizeSoName(base)
            val out = File(abiJniDir, jniName)
            file.copyTo(out, overwrite = true)
            soNameToJniName.putIfAbsent(base, jniName)
            staged++
            return@forEach
        }
    }
    logger.lifecycle("Promoted $staged files from $pkgName ($abi)")
}

/**
 * Android strips jniLibs files whose name is not `lib*.so` exactly. We mangle
 * versioned names so the packaging step keeps them:
 *   libreadline.so.8.3  →  libreadline-so-8-3.so
 *   libc++_shared.so    →  libc++_shared.so
 * The mapping is reversible at runtime (see PrefixExtractorImpl).
 */
fun sanitizeSoName(original: String): String {
    if (original.endsWith(".so") && !original.contains(".so.")) {
        return original
    }
    // Replace every `.` before ".so" or inside the version tail with '-'.
    val sb = StringBuilder()
    for ((i, c) in original.withIndex()) {
        sb.append(if (c == '.' && i != original.length - 3) '-' else c)
    }
    var mangled = sb.toString()
    if (!mangled.endsWith(".so")) mangled += ".so"
    return mangled
}

android {
    namespace = "com.claudemobile.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.claudemobile.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ship only the ABIs we have Termux bootstraps for.
        ndk {
            abiFilters += bootstrapAbis.keys
        }
    }

    sourceSets {
        named("main") {
            assets.srcDirs(termuxBootstrapAssetsDir, prebuiltRootfsAssetsDir)
            jniLibs.srcDirs(termuxBootstrapJniLibsDir)
        }
    }

    androidResources {
        // xz is already a compressed stream; do not let aapt re-compress it.
        // (aapt treats .tar as compressible by default which would waste I/O
        // on the 100+ MB rootfs images without shrinking them measurably.)
        noCompress += listOf("xz", "tar.xz", "tar")
    }

    // The Termux ELF binaries are not real shared libraries; they must not be
    // stripped or page-aligned for load. useLegacyPackaging=true also keeps them
    // extracted on install so exec() / dlopen() sees a real file.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Every lib we ship from the Termux bootstrap — keep its original
            // bits (no strip) because they include non-standard segments.
            keepDebugSymbols += listOf("**/*.so")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
            }
        }
    }

    lint {
        enable += setOf("UnusedResources", "MissingPermission", "ComposeUnstableCollections")
        warningsAsErrors = false
        abortOnError = true
    }
}

composeCompiler {
    enableStrongSkippingMode = true
}

// Ensure Termux assets/jniLibs are generated before anything consumes them.
// `merge{Variant}Assets` and `merge{Variant}JniLibFolders` are the standard
// AGP task names that pick up files from android.sourceSets.
afterEvaluate {
    tasks.matching { task ->
        val name = task.name
        name.startsWith("merge") &&
            (name.endsWith("Assets") || name.endsWith("JniLibFolders"))
    }.configureEach {
        dependsOn(prepareTermuxBootstrap)
        dependsOn(bundlePrebuiltRootfs)
    }
}

dependencies {
    implementation(project(":feature-chat"))
    implementation(project(":feature-settings"))
    implementation(project(":feature-sessions"))
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-bridge"))
    implementation(project(":core-ui"))
    implementation(project(":core-common"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.3")

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
