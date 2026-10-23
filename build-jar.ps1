$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
        return $env:JAVA_HOME
    }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) { return (Split-Path (Split-Path $java.Source -Parent) -Parent) }
    throw "未找到 JDK：请设置 JAVA_HOME。"
}

function Resolve-Maven {
    $cmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $cmd) { $cmd = Get-Command mvn -ErrorAction SilentlyContinue }
    if ($cmd) { return $cmd.Source }
    if ($env:MAVEN_HOME -and (Test-Path (Join-Path $env:MAVEN_HOME "bin\mvn.cmd"))) {
        return (Join-Path $env:MAVEN_HOME "bin\mvn.cmd")
    }
    throw "未找到 Maven：请把 mvn 加入 PATH，或设置 MAVEN_HOME。"
}

$javaHome = Resolve-JavaHome
$mvn = Resolve-Maven
$pom = [xml](Get-Content (Join-Path $root "pom.xml") -Raw)
$version = $pom.project.version
$javafxVersion = [regex]::Match((Get-Content (Join-Path $root "pom.xml") -Raw), "<javafx\.version>([^<]+)</javafx\.version>").Groups[1].Value

Push-Location $root
try {
    Write-Host "[1/4] 编译并收集所有平台 JavaFX 模块 ..." -ForegroundColor Cyan
    & $mvn -q -DskipTests -Pcrossjar package
    if ($LASTEXITCODE -ne 0) { throw "Maven package 失败" }

    $crossLibs = Join-Path $root "target\cross-libs"
    $required = @(
        "win\javafx-base-$javafxVersion-win.jar",
        "mac\javafx-base-$javafxVersion-mac.jar",
        "mac-aarch64\javafx-base-$javafxVersion-mac-aarch64.jar",
        "linux\javafx-base-$javafxVersion-linux.jar",
        "linux-aarch64\javafx-base-$javafxVersion-linux-aarch64.jar"
    )
    foreach ($rel in $required) {
        if (-not (Test-Path (Join-Path $crossLibs $rel))) {
            throw "缺少平台运行库: $rel"
        }
    }

    Write-Host "[2/4] 准备 fat-jar 内容 ..." -ForegroundColor Cyan
    $stage = Join-Path $root "target\crossjar-stage"
    Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $stage | Out-Null
    Copy-Item (Join-Path $root "target\classes\*") $stage -Recurse -Force
    $stageLibs = Join-Path $stage "libs"
    New-Item -ItemType Directory -Force $stageLibs | Out-Null
    Copy-Item (Join-Path $crossLibs '*') $stageLibs -Recurse -Force

    Write-Host "[3/4] 编译兼容性启动器（Java 17 字节码）..." -ForegroundColor Cyan
    $launcherOut = Join-Path $root "target\crosslauncher-classes"
    Remove-Item $launcherOut -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $launcherOut | Out-Null
    & (Join-Path $javaHome "bin\javac.exe") --release 17 -encoding UTF-8 -d $launcherOut `
        (Join-Path $root "launcher\CrossLauncher.java")
    if ($LASTEXITCODE -ne 0) { throw "CrossLauncher 编译失败" }
    Copy-Item (Join-Path $launcherOut "com\snowmcd\CrossLauncher.class") `
        (Join-Path $stage "com\snowmcd\CrossLauncher.class") -Force

    Write-Host "[4/4] 打包跨平台 jar ..." -ForegroundColor Cyan
    $dist = Join-Path $root "dist"
    New-Item -ItemType Directory -Force $dist | Out-Null
    $out = Join-Path $dist "SnowMCD-$version-cross.jar"
    Remove-Item $out -Force -ErrorAction SilentlyContinue
    & (Join-Path $javaHome "bin\jar.exe") --create --file $out `
        --main-class com.snowmcd.CrossLauncher -C $stage .
    if ($LASTEXITCODE -ne 0) { throw "jar 打包失败" }

    $sizeMb = (Get-Item $out).Length / 1MB
    Write-Host ("构建完成: {0} ({1:N1} MB)" -f $out, $sizeMb) -ForegroundColor Green
    Write-Host "Windows / macOS (Intel & Apple Silicon) / Linux (x64 & arm64) 共用一个 jar。"
} finally {
    Pop-Location
}

