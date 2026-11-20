#!/usr/bin/env python3
"""
يبني حزمة المحتوى التعليمي (بدون إنترنت) كمرحلة Bootstrap اسمها "learn":
  share/rustpad/learn/{rustlings,exercism,book,rbe,rbp}
المصادر مثبّتة على commits محددة (كلها MIT / MIT-Apache). المخرجات:
  dist/bootstrap-learn-aarch64.tar.zst  و  dist/SHA256SUMS-learn
"""
import argparse, glob, hashlib, json, os, shutil, subprocess, sys

SOURCES = {
    "rustlings": ("https://github.com/rust-lang/rustlings.git", "f7b9b3c5882a1a43275130b95142d94f847526f5"),
    "exercism":  ("https://github.com/exercism/rust.git",       "0a9b22d667da5a226bf480b0152ae29dfba99545"),
    "book":      ("https://github.com/rust-lang/book.git",      "5c23042f85595490ad9f7412c4c6f57fe0b330d5"),
    "rbe":       ("https://github.com/rust-lang/rust-by-example.git", "dcc14f912bf8790792a2df082eddc82ab2539229"),
    "rbp":       ("https://github.com/sunface/rust-by-practice.git",  "fc561055b71b8356a78542e82794fa06d5fe00b0"),
}

def run(*c, cwd=None):
    subprocess.run(c, cwd=cwd, check=True)

def fetch(name, work):
    url, sha = SOURCES[name]
    d = os.path.join(work, "src-" + name)
    shutil.rmtree(d, ignore_errors=True); os.makedirs(d)
    run("git", "init", "-q", cwd=d)
    run("git", "remote", "add", "origin", url, cwd=d)
    run("git", "fetch", "-q", "--depth", "1", "origin", sha, cwd=d)
    run("git", "checkout", "-q", "FETCH_HEAD", cwd=d)
    return d

def cp(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy2(src, dst)

def cptree(src, dst, ignore=None):
    shutil.copytree(src, dst, dirs_exist_ok=True, ignore=ignore)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="dist")
    ap.add_argument("--work", default="/tmp/learn-work")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    stage = os.path.join(a.work, "stage"); shutil.rmtree(stage, ignore_errors=True)
    root = os.path.join(stage, "share/rustpad/learn"); os.makedirs(root)
    meta = {}

    # ---- rustlings
    s = fetch("rustlings", a.work); d = os.path.join(root, "rustlings")
    cp(os.path.join(s, "rustlings-macros/info.toml"), os.path.join(d, "info.toml"))
    cptree(os.path.join(s, "exercises"), os.path.join(d, "exercises"))
    cptree(os.path.join(s, "solutions"), os.path.join(d, "solutions"))
    cp(os.path.join(s, "LICENSE"), os.path.join(d, "LICENSE"))

    # ---- exercism (practice فقط، مع الحل المثالي)
    s = fetch("exercism", a.work); d = os.path.join(root, "exercism")
    cfg = json.load(open(os.path.join(s, "config.json")))
    idx = [{"slug": e["slug"], "name": e["name"], "difficulty": e.get("difficulty", 1)}
           for e in cfg["exercises"]["practice"] if e.get("status") != "deprecated"]
    for e in idx:
        src = os.path.join(s, "exercises/practice", e["slug"])
        if not os.path.isdir(src):
            continue
        dst = os.path.join(d, "practice", e["slug"])
        for sub in (".docs", "src", "tests"):
            if os.path.isdir(os.path.join(src, sub)):
                cptree(os.path.join(src, sub), os.path.join(dst, sub))
        for f in ("Cargo.toml",):
            if os.path.exists(os.path.join(src, f)):
                cp(os.path.join(src, f), os.path.join(dst, f))
        ex = os.path.join(src, ".meta/example.rs")
        if os.path.exists(ex):
            cp(ex, os.path.join(dst, ".meta/example.rs"))
    json.dump(sorted(idx, key=lambda e: (e["difficulty"], e["name"])), open(os.path.join(d, "index.json"), "w"))
    cp(os.path.join(s, "LICENSE"), os.path.join(d, "LICENSE"))

    # ---- The Book (md + أمثلة .rs فقط)
    s = fetch("book", a.work); d = os.path.join(root, "book")
    cptree(os.path.join(s, "src"), os.path.join(d, "src"),
           ignore=shutil.ignore_patterns("img", "*.png", "*.svg", "*.jpg", "*.css", "*.js", "*.json"))
    for f in glob.glob(os.path.join(s, "listings/**/*.rs"), recursive=True):
        cp(f, os.path.join(d, os.path.relpath(f, s)))
    for lic in ("LICENSE-MIT", "LICENSE-APACHE"):
        if os.path.exists(os.path.join(s, lic)): cp(os.path.join(s, lic), os.path.join(d, lic))

    # ---- Rust by Example
    s = fetch("rbe", a.work); d = os.path.join(root, "rbe")
    cptree(os.path.join(s, "src"), os.path.join(d, "src"),
           ignore=shutil.ignore_patterns("*.png", "*.svg", "*.jpg", "*.css", "*.js"))
    cp(os.path.join(s, "LICENSE-MIT"), os.path.join(d, "LICENSE-MIT"))

    # ---- Rust by Practice (الإنجليزية + الحلول)
    s = fetch("rbp", a.work); d = os.path.join(root, "rbp")
    cptree(os.path.join(s, "en/src"), os.path.join(d, "src"),
           ignore=shutil.ignore_patterns("*.png", "*.svg", "*.jpg", "*.gif", "*.css", "*.js"))
    if os.path.isdir(os.path.join(s, "solutions")):
        cptree(os.path.join(s, "solutions"), os.path.join(d, "solutions"))
    cp(os.path.join(s, "LICENSE"), os.path.join(d, "LICENSE"))

    meta = {n: SOURCES[n][1] for n in SOURCES}
    json.dump(meta, open(os.path.join(root, "VERSIONS.json"), "w"), indent=1)

    out = os.path.join(a.out, "bootstrap-learn-aarch64.tar.zst")
    tar = subprocess.Popen(["tar", "--numeric-owner", "--owner=0", "--group=0", "-C", stage, "-cf", "-", "."],
                           stdout=subprocess.PIPE)
    with open(out, "wb") as fh:
        subprocess.run(["zstd", "-19", "-T0", "-q", "-"], stdin=tar.stdout, stdout=fh, check=True)
    if tar.wait() != 0:
        sys.exit("tar failed")
    h = hashlib.sha256(open(out, "rb").read()).hexdigest()
    open(os.path.join(a.out, "SHA256SUMS-learn"), "w").write(f"{h}  {os.path.basename(out)}\n")
    print(f"{out}: {os.path.getsize(out)/1e6:.1f} MB")

if __name__ == "__main__":
    main()
