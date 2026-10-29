#!/usr/bin/env python3
"""检查打包出来的 app-image 有没有"依赖被改写"。

背景（2026-10 踩过一次）：Compose Desktop 的 **release 变体默认开整程序压缩（R8/ProGuard）**，
它会把每个依赖 jar 重写、只留"可达"的类。JNA 这种**按名字与原生库绑定**的库当场就废了：
`Native.dispose` 被裁掉，运行时报
`Could not initialize class com.sun.jna.Native`（或 `Can't obtain static method dispose`）。
用户装上去谁都连不上，而开发机 `gradlew run` 一切正常 —— 只有打包产物才有这个差异。

所以每次发版前跑一次这个脚本：把 app-image 里的 jar 与 Gradle 缓存里的原件按大小比对。
允许两种合法差异：
  1. **同名多份**（Compose 会额外放一个占位空壳，真身另一份）—— 只要有一份对得上就算 OK；
  2. **原生库被抽出去**（如 skiko 的 dll 落在 app/ 下而不是 jar 里）—— 这些 jar 会变成空壳，
     列为"已知"不算失败。

用法：
    python desktop/tools/checkBundle.py                 # 自动找默认路径
    python desktop/tools/checkBundle.py <app-image目录>  # 指定目录
退出码 0 = 全部对得上；1 = 有 jar 被改写（很可能又是压缩开了）。
"""
import glob
import os
import re
import sys

# 原生库会被打包器抽到 app/ 下，jar 里只剩空壳，属于正常
KNOWN_NATIVE_STUBS = ("skiko-awt-runtime",)

HASH_SUFFIX = re.compile(r"^(.*)-[0-9a-f]{20,}\.jar$")


def default_app_dir() -> str | None:
    base = os.environ.get("LOCALAPPDATA") or os.path.expanduser("~")
    hits = glob.glob(os.path.join(base, "TailcatManager-build", "**",
                                 "compose", "binaries", "main-release", "app",
                                 "TailcatManager", "app"), recursive=True)
    return hits[0] if hits else None


def main() -> int:
    app = sys.argv[1] if len(sys.argv) > 1 else default_app_dir()
    if not app or not os.path.isdir(app):
        print("找不到 app-image 目录；先把 :desktop:packageReleaseAppImage 跑一遍")
        return 2

    cache_root = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
    cache: dict[str, list[int]] = {}
    for path in glob.glob(os.path.join(cache_root, "**", "*.jar"), recursive=True):
        cache.setdefault(os.path.basename(path), []).append(os.path.getsize(path))

    packaged: dict[str, list[int]] = {}
    for path in glob.glob(os.path.join(app, "*.jar")):
        base = os.path.basename(path)
        m = HASH_SUFFIX.match(base)
        packaged.setdefault((m.group(1) + ".jar") if m else base, []).append(os.path.getsize(path))

    problems = []
    skipped = []
    for name, sizes in sorted(packaged.items()):
        origin = cache.get(name)
        if not origin:
            skipped.append(name)
            continue
        if any(s in origin for s in sizes):
            continue
        if any(name.startswith(prefix) for prefix in KNOWN_NATIVE_STUBS):
            continue
        problems.append((name, origin, sizes))

    for name, origin, sizes in problems:
        print(f"!! {name}: 缓存 {origin} vs 打包 {sizes}")
    for name in skipped:
        print(f"?? {name}: 缓存里没有同名 jar（本项目自产，跳过）")

    print(f"\n打包 jar {len(packaged)} 个，异常 {len(problems)} 个")
    if problems:
        print("→ 依赖被改写了。检查 desktop/build.gradle.kts 里 buildTypes.release.proguard 是否被打开，")
        print("  或为反射/JNI 依赖补 keep 规则。")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
