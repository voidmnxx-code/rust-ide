#!/usr/bin/env python3
"""Build RustPad bootstrap archives from Termux packages.

Termux packages are compiled for the prefix /data/data/com.termux/files/usr.
RustPad uses applicationId `dev.rustpd` (same length as `com.termux`), so every
hardcoded path inside the packages can be rewritten in place (binary-safe)
without changing any file size or offset.

Output: dist/bootstrap-<stage>-aarch64.tar.zst  (+ SHA256SUMS)
Archive paths are relative to $PREFIX (e.g. ./bin/bash), as BootstrapInstaller expects.
"""
import argparse, gzip, hashlib, lzma, os, re, shutil, stat, subprocess, sys, tarfile, tempfile, time
import urllib.request

OLD_ID = "com.termux"
ARCH = "aarch64"

# (required packages, optional packages)
STAGES = {
    "base": (
        ["bash", "coreutils", "findutils", "grep", "sed", "gawk", "tar", "gzip",
         "unzip", "zip", "git", "make", "binutils", "clang", "lld", "rust",
         "ca-certificates", "less", "nano"],
        [],
    ),
    "apk-tools": (["openjdk-17", "aapt2"], []),
    "rust-analyzer": ([], ["rust-analyzer"]),
    # sccache من Termux + مكتبات crates محمّلة مسبقاً (cargo vendor) للعمل بلا اتصال
    "offline-crates": ([], ["sccache"]),
}
# Never installed (package managers / app-bound helpers we do not need).
SKIP = {"apt", "dpkg", "termux-keyring", "termux-licenses", "game-repo", "science-repo"}


def log(*a):
    print(*a, flush=True)


def fetch(url, retries=4):
    """GET with retries (file:// works too, for local tests)."""
    for i in range(retries):
        try:
            with urllib.request.urlopen(url, timeout=120) as r:
                return r.read()
        except Exception as e:  # noqa
            if i == retries - 1:
                raise
            log(f"  retry {url}: {e}")
            time.sleep(3 * (i + 1))


def load_index(repo):
    base = f"{repo}/dists/stable/main/binary-{ARCH}"
    for name, dec in (("Packages.xz", lzma.decompress), ("Packages.gz", gzip.decompress),
                      ("Packages", lambda b: b)):
        try:
            return dec(fetch(f"{base}/{name}")).decode("utf-8", "replace")
        except Exception as e:  # noqa
            log(f"index {name} unavailable: {e}")
    sys.exit("cannot load package index")


def parse_index(text):
    pkgs, provides = {}, {}
    for block in text.split("\n\n"):
        f, key = {}, None
        for line in block.splitlines():
            if line[:1] in (" ", "\t") and key:
                f[key] += "\n" + line.strip()
            elif ":" in line:
                key, _, v = line.partition(":")
                key = key.strip()
                f[key] = v.strip()
        if "Package" in f:
            pkgs[f["Package"]] = f
    for name, f in pkgs.items():
        for p in f.get("Provides", "").split(","):
            p = re.sub(r"\(.*?\)", "", p).strip()
            if p:
                provides.setdefault(p, name)
    return pkgs, provides


def deps_of(f, pkgs, provides):
    out = []
    for field in ("Pre-Depends", "Depends"):
        for item in f.get(field, "").split(","):
            if not item.strip():
                continue
            alts = [re.sub(r"\(.*?\)", "", a).strip() for a in item.split("|")]
            for a in alts:
                if a in pkgs:
                    out.append(a); break
                if a in provides:
                    out.append(provides[a]); break
            else:
                log(f"  warn: unresolved dependency {item.strip()} of {f['Package']}")
    return out


def closure(roots, pkgs, provides, done):
    todo, seen, order = list(roots), set(), []
    while todo:
        n = todo.pop(0)
        if n in seen or n in done or n in SKIP:
            continue
        if n not in pkgs:
            if n in provides:
                todo.append(provides[n]); continue
            sys.exit(f"required package not found in Termux repo: {n}")
        seen.add(n); order.append(n)
        todo.extend(deps_of(pkgs[n], pkgs, provides))
    return order


def download_deb(repo, f, cache):
    dest = os.path.join(cache, os.path.basename(f["Filename"]))
    if not os.path.exists(dest):
        data = fetch(f"{repo}/{f['Filename']}")
        want = f.get("SHA256")
        if want and hashlib.sha256(data).hexdigest() != want:
            raise IOError(f"sha256 mismatch for {f['Package']}")
        with open(dest, "wb") as fh:
            fh.write(data)
    return dest


def install_deb(deb, stage_dir, old_id):
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["dpkg-deb", "-x", deb, tmp], check=True)
        # make sure we can traverse/delete odd permissions
        subprocess.run(["chmod", "-R", "u+rwX", tmp], check=True)
        src = os.path.join(tmp, "data/data", old_id, "files/usr")
        if not os.path.isdir(src):
            log(f"  warn: no prefix content in {os.path.basename(deb)}")
            return
        shutil.copytree(src, stage_dir, symlinks=True, dirs_exist_ok=True)


def patch_tree(stage_dir, old_id, new_id):
    assert len(old_id) == len(new_id), "application id must be the same length as com.termux"
    old = f"/data/data/{old_id}".encode()
    new = f"/data/data/{new_id}".encode()
    files = links = 0
    for d, _, names in os.walk(stage_dir):
        for n in names:
            p = os.path.join(d, n)
            if os.path.islink(p):
                t = os.readlink(p)
                if old.decode() in t:
                    os.unlink(p); os.symlink(t.replace(old.decode(), new.decode()), p); links += 1
                continue
            if not os.path.isfile(p):
                continue
            with open(p, "rb") as fh:
                data = fh.read()
            if old in data:
                mode = os.stat(p).st_mode
                os.chmod(p, mode | stat.S_IWUSR)
                with open(p, "wb") as fh:
                    fh.write(data.replace(old, new))
                os.chmod(p, mode)
                files += 1
    log(f"  patched {files} files, {links} symlinks")


def add_android_sdk(stage_dir, sdk_dir, new_id):
    """d8/apksigner jars + android.jar from the Android SDK, plus launcher scripts."""
    share = os.path.join(stage_dir, "share/android")
    os.makedirs(share, exist_ok=True)
    bt_root = os.path.join(sdk_dir, "build-tools")
    bt = sorted(os.listdir(bt_root), key=lambda v: [int(x) if x.isdigit() else 0 for x in re.split(r"[.\-rc]+", v)])[-1]
    for jar in ("d8.jar", "apksigner.jar"):
        shutil.copy2(os.path.join(bt_root, bt, "lib", jar), share)
    plat_root = os.path.join(sdk_dir, "platforms")
    plats = sorted((p for p in os.listdir(plat_root) if p.startswith("android-") and p[8:].isdigit()),
                   key=lambda p: int(p[8:]))
    shutil.copy2(os.path.join(plat_root, plats[-1], "android.jar"), share)
    prefix = f"/data/data/{new_id}/files/usr"
    scripts = {
        "d8": f'exec "{prefix}/bin/java" -cp "{prefix}/share/android/d8.jar" com.android.tools.r8.D8 "$@"',
        "apksigner": f'exec "{prefix}/bin/java" -jar "{prefix}/share/android/apksigner.jar" "$@"',
    }
    os.makedirs(os.path.join(stage_dir, "bin"), exist_ok=True)
    for name, body in scripts.items():
        p = os.path.join(stage_dir, "bin", name)
        with open(p, "w") as fh:
            fh.write(f"#!{prefix}/bin/sh\n{body}\n")
        os.chmod(p, 0o755)
    log(f"  added SDK files (build-tools {bt}, {plats[-1]})")


def link_jvm_tools(stage_dir):
    """Termux creates bin/java etc. in postinst (update-alternatives); we do it here."""
    jvm = os.path.join(stage_dir, "lib/jvm")
    if not os.path.isdir(jvm):
        return
    os.makedirs(os.path.join(stage_dir, "bin"), exist_ok=True)
    n = 0
    for d in sorted(os.listdir(jvm)):
        bindir = os.path.join(jvm, d, "bin")
        if not os.path.isdir(bindir):
            continue
        for tool in sorted(os.listdir(bindir)):
            dst = os.path.join(stage_dir, "bin", tool)
            if not os.path.lexists(dst):
                os.symlink(os.path.join("..", "lib", "jvm", d, "bin", tool), dst)
                n += 1
    log(f"  linked {n} JVM tools into bin/")


VENDOR_MANIFEST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vendor", "Cargo.toml")

# مشروع اختبار يجب أن يُبنى بلا اتصال اعتماداً على المكتبات المحمّلة فقط
VERIFY_TOML = """[package]
name = "verify"
version = "0.1.0"
edition = "2021"

[dependencies]
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tokio = { version = "1", features = ["full"] }
rand = "0.8"
anyhow = "1"
clap = { version = "4", features = ["derive"] }
regex = "1"
jni = "0.21"
"""


def add_vendor(stage_dir):
    dest = os.path.join(stage_dir, "share/rustpad/vendor")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    log("  cargo vendor ...")
    subprocess.run(["cargo", "vendor", "--manifest-path", VENDOR_MANIFEST, dest],
                   check=True, stdout=subprocess.DEVNULL)
    n = len(os.listdir(dest))
    size = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(dest) for f in fs)
    log(f"  vendored {n} crates, {size / 1e6:.0f} MB")
    # تحقق: مشروع منفصل يجب أن يُحلَّ ويُفحص بلا شبكة اعتماداً على هذا المجلد فقط
    with tempfile.TemporaryDirectory() as t:
        os.makedirs(os.path.join(t, "src"))
        open(os.path.join(t, "Cargo.toml"), "w").write(VERIFY_TOML)
        open(os.path.join(t, "src/main.rs"), "w").write("fn main() {}\n")
        env = dict(os.environ, CARGO_HOME=os.path.join(t, "home"), CARGO_NET_OFFLINE="true")
        r = subprocess.run(
            ["cargo", "check", "--offline",
             "--config", 'source.crates-io.replace-with="vendored"',
             "--config", f'source.vendored.directory="{dest}"'],
            cwd=t, env=env, capture_output=True, text=True)
        if r.returncode != 0:
            log(r.stdout[-2000:]); log(r.stderr[-4000:])
            sys.exit("vendor verification FAILED (offline build of the test project)")
    log("  vendor verification OK (offline cargo check passed)")


def pack(stage_dir, out_file):
    tar = subprocess.Popen(
        ["tar", "--numeric-owner", "--owner=0", "--group=0", "-C", stage_dir, "-cf", "-", "."],
        stdout=subprocess.PIPE)
    with open(out_file, "wb") as fh:
        z = subprocess.Popen(["zstd", "-19", "-T0", "-q", "-c"], stdin=tar.stdout, stdout=fh)
        tar.stdout.close()
        if z.wait() or tar.wait():
            raise RuntimeError("packing failed")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="https://packages.termux.dev/apt/termux-main")
    ap.add_argument("--app-id", default="dev.rustpd")
    ap.add_argument("--out", default="dist")
    ap.add_argument("--work", default="work")
    ap.add_argument("--sdk-dir", default=os.environ.get("ANDROID_HOME", ""))
    ap.add_argument("--stages", default=",".join(STAGES))
    ap.add_argument("--only-packages", default="", help="testing: override required packages of every stage")
    ap.add_argument("--no-pack", action="store_true")
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)
    cache = os.path.join(a.work, "debs"); os.makedirs(cache, exist_ok=True)
    pkgs, provides = parse_index(load_index(a.repo))
    log(f"index: {len(pkgs)} packages")

    done, sums = set(), []
    selected = a.stages.split(",")
    for stage in STAGES:
        if stage not in selected:
            # مراحل لن تُبنى الآن: نحسب حزمها فقط حتى لا تتكرر داخل المراحل المبنية
            req, opt = STAGES[stage]
            if not a.only_packages:
                try:
                    done.update(closure(list(req) + [o for o in opt if o in pkgs], pkgs, provides, done))
                except SystemExit:
                    pass
            continue
        required, optional = STAGES[stage]
        if a.only_packages:
            required, optional = a.only_packages.split(","), []
        log(f"== stage {stage}")
        roots = list(required)
        for o in optional:
            if o in pkgs:
                roots.append(o)
            else:
                log(f"  optional package missing: {o}")
        order = closure(roots, pkgs, provides, done)
        log(f"  {len(order)} packages: {' '.join(order)}")
        stage_dir = os.path.join(a.work, "stage-" + stage)
        shutil.rmtree(stage_dir, ignore_errors=True); os.makedirs(stage_dir)
        for n in order:
            install_deb(download_deb(a.repo, pkgs[n], cache), stage_dir, OLD_ID)
        done.update(order)
        patch_tree(stage_dir, OLD_ID, a.app_id)
        link_jvm_tools(stage_dir)
        if stage == "apk-tools":
            if not a.sdk_dir:
                sys.exit("--sdk-dir (or ANDROID_HOME) required for apk-tools stage")
            add_android_sdk(stage_dir, a.sdk_dir, a.app_id)
        if stage == "offline-crates":
            add_vendor(stage_dir)
        if a.no_pack:
            continue
        out = os.path.join(a.out, f"bootstrap-{stage}-{ARCH}.tar.zst")
        pack(stage_dir, out)
        h = hashlib.sha256(open(out, "rb").read()).hexdigest()
        sums.append(f"{h}  {os.path.basename(out)}")
        log(f"  {out}: {os.path.getsize(out) / 1e6:.1f} MB")
        shutil.rmtree(stage_dir, ignore_errors=True)
    if sums:
        with open(os.path.join(a.out, "SHA256SUMS-" + "_".join(selected)), "w") as fh:
            fh.write("\n".join(sums) + "\n")


if __name__ == "__main__":
    main()
