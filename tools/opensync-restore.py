#!/usr/bin/env python3
"""
OpenSync – odzyskiwanie kopii na komputerze, bez telefonu.

Wymaga Pythona 3.8+ i pakietu „cryptography”:  pip install cryptography

Repozytorium musi być dostępne jako katalog (np. zamontowany udział NAS lub skopiowany folder).

  python opensync-restore.py REPO list
  python opensync-restore.py REPO files  ID_KOPII [--grep TEKST]
  python opensync-restore.py REPO restore ID_KOPII CEL [--include ŚCIEŻKA ...]
  python opensync-restore.py REPO check  [--full]

ID_KOPII może być pełnym identyfikatorem, jego początkiem albo słowem „latest”.
Hasło jest pobierane interaktywnie albo ze zmiennej środowiskowej OPENSYNC_PASSWORD.
"""
import argparse
import base64
import getpass
import hashlib
import hmac
import json
import os
import sys
import zlib
from datetime import datetime, timezone

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.exceptions import InvalidTag
except ImportError:
    sys.exit("Brak pakietu „cryptography”. Zainstaluj: pip install cryptography")


class Repo:
    def __init__(self, root, password):
        self.root = root
        cfg_path = os.path.join(root, "config")
        if not os.path.isfile(cfg_path):
            sys.exit(f"W {root} nie ma repozytorium OpenSync (brak pliku config)")
        with open(cfg_path, encoding="utf-8") as f:
            cfg = json.load(f)
        if cfg.get("format") != "opensync-repo":
            sys.exit("To nie jest repozytorium OpenSync")
        kdf = cfg["kdf"]
        kek = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), base64.b64decode(kdf["salt"]), kdf["iterations"], 32)
        try:
            master = self._open(kek, base64.b64decode(cfg["key"]), b"opensync-master-key")
        except InvalidTag:
            sys.exit("Nieprawidłowe hasło")
        self.enc, self.mac = master[:32], master[32:]

    @staticmethod
    def _open(key, sealed, aad):
        if len(sealed) < 29 or sealed[0] != 1:
            raise InvalidTag()
        return AESGCM(key).decrypt(sealed[1:13], sealed[13:], aad)

    def _unpack(self, sealed, aad):
        plain = self._open(self.enc, sealed, aad)
        if plain[0] == 0:
            return plain[1:]
        if plain[0] == 1:
            return zlib.decompress(plain[1:])
        raise ValueError("Nieznany format bloku")

    def blob(self, blob_id):
        with open(os.path.join(self.root, "data", blob_id[:2], blob_id), "rb") as f:
            data = self._unpack(f.read(), blob_id.encode())
        if hmac.new(self.mac, data, hashlib.sha256).hexdigest() != blob_id:
            raise ValueError(f"Uszkodzony fragment {blob_id}")
        return data

    def snapshots(self):
        d = os.path.join(self.root, "snapshots")
        names = [n for n in os.listdir(d) if not n.endswith(".part")] if os.path.isdir(d) else []
        return sorted(names, reverse=True)

    def snapshot(self, sid):
        with open(os.path.join(self.root, "snapshots", sid), "rb") as f:
            return json.loads(self._unpack(f.read(), f"doc:snapshot:{sid}".encode()).decode("utf-8"))

    def resolve(self, prefix):
        snaps = self.snapshots()
        if prefix == "latest" and snaps:
            return snaps[0]
        hits = [s for s in snaps if s.startswith(prefix)]
        if len(hits) != 1:
            sys.exit("Nie znaleziono kopii" if not hits else "Podany początek pasuje do wielu kopii")
        return hits[0]


def human(n):
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


def when(ms):
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M")


def safe_join(base, rel):
    target = os.path.normpath(os.path.join(base, *rel.split("/")))
    if not target.startswith(os.path.normpath(base)):
        raise ValueError(f"Niebezpieczna ścieżka: {rel}")
    return target


def main():
    p = argparse.ArgumentParser(description="Odzyskiwanie kopii OpenSync")
    p.add_argument("repo", help="katalog repozytorium (np. Z:\\backup\\OpenSync)")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("list", help="lista kopii")
    pf = sub.add_parser("files", help="pliki w kopii")
    pf.add_argument("snapshot")
    pf.add_argument("--grep", default="")
    pr = sub.add_parser("restore", help="przywróć pliki")
    pr.add_argument("snapshot")
    pr.add_argument("target")
    pr.add_argument("--include", nargs="*", default=[], help="tylko te pliki lub foldery")
    pc = sub.add_parser("check", help="sprawdź spójność")
    pc.add_argument("--full", action="store_true", help="odszyfruj i sprawdź wszystkie fragmenty")
    a = p.parse_args()

    password = os.environ.get("OPENSYNC_PASSWORD") or getpass.getpass("Hasło repozytorium: ")
    repo = Repo(a.repo, password)

    if a.cmd == "list":
        for sid in repo.snapshots():
            s = repo.snapshot(sid)
            size = sum(f[1] for f in s["files"])
            print(f"{sid}  {when(s['time'])}  {s.get('jobName', '')} · {s.get('device', '')} · {len(s['files'])} plików · {human(size)}")
    elif a.cmd == "files":
        s = repo.snapshot(repo.resolve(a.snapshot))
        for path, size, _mtime, _chunks in s["files"]:
            if a.grep.lower() in path.lower():
                print(f"{human(size):>10}  {path}")
    elif a.cmd == "restore":
        sid = repo.resolve(a.snapshot)
        s = repo.snapshot(sid)
        inc = [i.strip("/") for i in a.include]
        files = [f for f in s["files"] if not inc or any(f[0] == i or f[0].startswith(i + "/") for i in inc)]
        errors = 0
        for n, (path, size, mtime, chunks) in enumerate(files, 1):
            out = safe_join(a.target, path)
            os.makedirs(os.path.dirname(out), exist_ok=True)
            try:
                with open(out + ".part", "wb") as f:
                    for c in chunks:
                        f.write(repo.blob(c))
                os.replace(out + ".part", out)
                if mtime > 0:
                    os.utime(out, (mtime / 1000, mtime / 1000))
                print(f"[{n}/{len(files)}] {path}")
            except Exception as e:  # noqa: BLE001 – raportujemy i jedziemy dalej
                errors += 1
                print(f"[{n}/{len(files)}] BŁĄD {path}: {e}", file=sys.stderr)
        print(f"Przywrócono {len(files) - errors} z {len(files)} plików do {a.target}")
        sys.exit(1 if errors else 0)
    elif a.cmd == "check":
        used = set()
        for sid in repo.snapshots():
            for f in repo.snapshot(sid)["files"]:
                used.update(f[3])
        missing = [b for b in used if not os.path.isfile(os.path.join(a.repo, "data", b[:2], b))]
        bad = 0
        if a.full:
            for b in used - set(missing):
                try:
                    repo.blob(b)
                except Exception:  # noqa: BLE001
                    bad += 1
        print(f"Kopie: {len(repo.snapshots())}, fragmenty: {len(used)}, brakujące: {len(missing)}" + (f", uszkodzone: {bad}" if a.full else ""))
        sys.exit(1 if missing or bad else 0)


if __name__ == "__main__":
    main()
