"""Rebuild only Foxlet's Kotlin layer using this project's Windows toolchain."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
from urllib.request import urlopen
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[3]
BUILD = ROOT / 'app/build/foxlet-compat'
SOURCE_COMMIT = '0eff2a15032362115848a496ce1df8ed9fc68650'
SOURCE = f'https://raw.githubusercontent.com/yinvoke/foxlet-translate/{SOURCE_COMMIT}/'
RELEASE = 'https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/'
BUILD.mkdir(parents=True, exist_ok=True)
original = urlopen(RELEASE + 'foxlet-v0.5.0.aar').read()
assert hashlib.sha256(original).hexdigest() == '5dbb384907d69c2aba129fd15dba30dda94ede0fa556dc6a167d041ee479ec0e'
tree = json.load(urlopen(f'https://api.github.com/repos/yinvoke/foxlet-translate/git/trees/{SOURCE_COMMIT}?recursive=1'))
for entry in tree['tree']:
    name = entry['path']
    prefix = 'foxlet/src/main/kotlin/'
    if name.startswith(prefix) and name.endswith('.kt'):
        target = BUILD / 'src/main/kotlin' / name[len(prefix):]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(urlopen(SOURCE + name).read())
with ZipFile(io.BytesIO(original)) as aar:
    with ZipFile(io.BytesIO(aar.read('classes.jar'))) as classes:
        for name in classes.namelist():
            if name.startswith('io/github/yinvoker/foxlet/') and not name.endswith(('.class', '/')):
                target = BUILD / 'src/main/resources' / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(classes.read(name))
subprocess.run(['patch', '-p2', '-i', str(ROOT / 'app/libs/foxlet/preload.patch')], cwd=BUILD, check=True)
(BUILD / 'settings.gradle.kts').write_text('pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\nrootProject.name = "foxlet-bibi-compat"\n')
(BUILD / 'build.gradle.kts').write_text('''plugins { kotlin("jvm") version "2.2.10" }
repositories { google(); mavenCentral() }
kotlin { jvmToolchain(21); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies {
    compileOnly(files(System.getenv("LOCALAPPDATA") + "/Android/Sdk/platforms/android-37.0/android.jar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
''')
subprocess.run(['gradlew-win', '-p', 'app/build/foxlet-compat', 'jar', '--console=plain'], cwd=ROOT, check=True)
jar = next((BUILD / 'build/libs').glob('*.jar')).read_bytes()
with ZipFile(io.BytesIO(original)) as aar, ZipFile(ROOT / 'app/libs/foxlet-v0.5.0.aar', 'w', ZIP_DEFLATED) as result:
    for entry in aar.infolist():
        result.writestr(entry, jar if entry.filename == 'classes.jar' else aar.read(entry.filename))
