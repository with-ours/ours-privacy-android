import json
from pathlib import Path
from xml.etree import ElementTree
publication = Path("oursprivacy-android/build/publications/maven")
metadata = json.loads((publication / "module.json").read_text())
version = next(line.split("=", 1)[1] for line in Path("gradle.properties").read_text().splitlines() if line.startswith("VERSION_NAME="))
assert metadata["component"]["version"] == version
assert all(dep.get("module") != "kotlin-stdlib" for variant in metadata["variants"] for dep in variant.get("dependencies", []))
pom = ElementTree.parse(publication / "pom-default.xml")
assert all(node.text != "kotlin-stdlib" for node in pom.findall(".//{*}artifactId"))
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
assert pom.findtext("m:groupId", namespaces=namespace) == "com.oursprivacy"
assert pom.findtext("m:artifactId", namespaces=namespace) == "oursprivacy-android"
assert pom.findtext("m:version", namespaces=namespace) == version
local = Path("build/maven-local/com/oursprivacy/oursprivacy-android") / version
assert (local / f"oursprivacy-android-{version}.aar").is_file()
assert (local / f"oursprivacy-android-{version}.pom").read_bytes() == (publication / "pom-default.xml").read_bytes()
assert (local / f"oursprivacy-android-{version}.module").read_bytes() == (publication / "module.json").read_bytes()
print(f"Unsigned local Maven metadata verified: com.oursprivacy:oursprivacy-android:{version}")
