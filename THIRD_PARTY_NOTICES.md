# 第三方代码、依赖与源码

CrossBandHotspot自身代码按GPL-3.0-or-later发布。下列依赖保持各自许可，版本与二进制SHA256见`app/dependencies.lock.json`。本项目使用它们已发布的原始二进制，没有修改依赖代码。

| 依赖 | 版本 | 上游与许可 |
| --- | --- | --- |
| LibADB Android | 3.1.1 | [MuntashirAkon/libadb-android](https://github.com/MuntashirAkon/libadb-android)，Copyright Muntashir Al-Islam；GPL-3.0-or-later或Apache-2.0，本项目采用Apache-2.0选项 |
| SPAKE2 Java / Android | 2.2.1 | [MuntashirAkon/spake2-java](https://github.com/MuntashirAkon/spake2-java)，LGPL-3.0；包含下行原生库 |
| SPAKE2 C | 0d15933e5ba3e662cb01245a7ac0dc9fca3eac31 | [MuntashirAkon/spake2-c](https://github.com/MuntashirAkon/spake2-c)，LGPL-3.0；保留其源码中的BoringSSL等原始声明 |
| Bouncy Castle Provider | 1.81 | [bcgit/bc-java](https://github.com/bcgit/bc-java)，Bouncy Castle许可（MIT形式） |
| Conscrypt Android | 2.5.3 | [google/conscrypt](https://github.com/google/conscrypt)，Apache-2.0及上游NOTICE列明的第三方代码 |
| sun-security-android | 1.1 | [MuntashirAkon/sun-security-android](https://github.com/MuntashirAkon/sun-security-android)，GPL-2.0附Classpath例外；保留Oracle、OpenJDK及其他原始声明 |
| AndroidX Annotation JVM | 1.9.1 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/)，Apache-2.0 |

`LocalAdb`的证书生成方式参考了LibADB测试应用，已按其上述许可保留来源说明。本机恢复流程参考了[mouldybread/adb-auto-enable](https://github.com/mouldybread/adb-auto-enable)（MIT）；本项目实现固定热点用途的流程，未复制其应用代码。

许可文本和NOTICE位于[LICENSES](LICENSES)。每个依赖完整的版权与许可头也随对应源码保留。

## 对应源码与重新构建

[Releases](https://github.com/RTXwcz/CrossBandHotspot/releases)随APK提供`CrossBandHotspot-dependency-sources.zip`，内容由`third-party-sources.lock.json`中的固定版本或提交构成：

- LibADB 3.1.1的完整仓库源码及构建脚本。
- SPAKE2 Java 2.2.1的完整仓库源码和它对应的SPAKE2 C子模块源码。
- sun-security-android 1.1的完整仓库源码及构建脚本。
- Bouncy Castle、Conscrypt和AndroidX Annotation的已发布Java源码包。
- 本项目使用的许可文本与源码替换说明。

本项目应用源码由本仓库及GitHub自动生成的版本源码归档提供。所有依赖版本和内容校验值可通过以下命令重新获取并打包：

```sh
python scripts/package_dependency_sources.py
```

重新构建SPAKE2时，将`spake2-c`源码放回`spake2-java/android/src/main/cpp/spake2-c`，按原项目Gradle配置构建Android AAR。其他库使用各自归档中的构建脚本。替换本地`app/deps/`中的相应AAR/JAR、将`app/dependencies.lock.json`中该文件的SHA256改为重建产物的值，再执行`python app/build.py`即可重新链接应用。APK在自己设备上的安装使用自己的签名身份，不依赖原发布者的密钥。
