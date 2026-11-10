"""Package immutable official MetaCubeX database snapshots; never fetch 'latest'."""
import hashlib
import pathlib
import urllib.request

REVISION = "eb50c83a6c152b82847d7fddc9b0e5d895f2ee86"
ASSETS = {
    "geoip.metadb": ("geoip.metadb", "f1c738b02773b766afd7a67592d37d3b40ce6a900751660635e28db5f7b294bf"),
    "GeoIP.dat": ("geoip.dat", "90b78ab861c1cdc3f871f305f9ceedfb8d6d6329e3a40295e6773e057297384c"),
    "GeoSite.dat": ("geosite.dat", "ab2c9f7217c91632057a8bff7810a6eb02ce05a8be4342cf7319ec1970d29733"),
    "ASN.mmdb": ("GeoLite2-ASN.mmdb", "3f729dfdbca46d6bc83fe084e15e335cef702b46f9d9afb8feaede36180959f4"),
}
destination = pathlib.Path("app/src/main/assets/mihomo-geodata")
destination.mkdir(parents=True, exist_ok=True)
manifest = []
for name, (source, expected) in ASSETS.items():
    url = f"https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/{REVISION}/{source}"
    with urllib.request.urlopen(url, timeout=120) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError(f"Checksum mismatch: {source}")
    (destination / name).write_bytes(data)
    manifest.append(f"{expected}  {name}\n")
(destination / "SHA256SUMS").write_text("".join(manifest), encoding="utf-8")
