param(
    [switch]$SkipInstaller
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$pom = [xml](Get-Content (Join-Path $root "pom.xml") -Raw)
$version = $pom.project.version
$appName = "SnowMCD"

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\jpackage.exe"))) {
        return $env:JAVA_HOME
    }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) {
        return (Split-Path (Split-Path $java.Source -Parent) -Parent)
    }
    throw "未找到 JDK 25：请设置 JAVA_HOME 指向包含 jpackage 的 JDK。"
}

function Resolve-Maven {
    $cmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $cmd) { $cmd = Get-Command mvn -ErrorAction SilentlyContinue }
    if ($cmd) { return $cmd.Source }
    if ($env:MAVEN_HOME -and (Test-Path (Join-Path $env:MAVEN_HOME "bin\mvn.cmd"))) {
        return (Join-Path $env:MAVEN_HOME "bin\mvn.cmd")
    }
    throw "未找到 Maven 3.9+：请把 mvn 加入 PATH，或设置 MAVEN_HOME。"
}

$javaHome = Resolve-JavaHome
$mvn = Resolve-Maven
$jpackage = Join-Path $javaHome "bin\jpackage.exe"
Write-Host "JDK : $javaHome"
Write-Host "Maven: $mvn"

Push-Location $root
try {
    Write-Host "`n[1/4] 编译并打包应用 jar ..." -ForegroundColor Cyan
    & $mvn -q -DskipTests clean package
    if ($LASTEXITCODE -ne 0) { throw "Maven package 失败" }

    Write-Host "[2/4] 收集 JavaFX / Gson 运行时模块 ..." -ForegroundColor Cyan
    & $mvn -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt" "-Dmdep.includeScope=runtime"
    if ($LASTEXITCODE -ne 0) { throw "dependency:build-classpath 失败" }
    $classpath = (Get-Content (Join-Path $root "target\cp.txt") -Raw).Trim() -split ';'
    $modules = @()
    foreach ($entry in $classpath) {
        if ([string]::IsNullOrWhiteSpace($entry) -or -not (Test-Path $entry)) { continue }
        if ($entry -match '\\org\\openjfx\\') {
            # The non-classifier JavaFX jars are stubs; only the platform jars are modular.
            if ($entry -match '-win\.jar$') { $modules += (Resolve-Path $entry).Path }
        } elseif ($entry -match 'gson-[0-9]') {
            $modules += (Resolve-Path $entry).Path
        }
    }
    if ($modules.Count -lt 4) { throw "运行时依赖不完整（JavaFX/Gson）" }

    $appJar = Join-Path $root "target\snowmcd-$version.jar"
    if (-not (Test-Path $appJar)) { throw "没有找到 $appJar" }
    $modulePath = (@($appJar) + $modules) -join ';'

    $dist = Join-Path $root "dist"
    Remove-Item $dist -Recurse -Force -ErrorAction SilentlyContinue

    Write-Host "[3/4] 生成免安装目录 dist\$appName ..." -ForegroundColor Cyan
    & $jpackage --type app-image --name $appName --app-version $version `
        --vendor "SnowMCD Project" --description "Minecraft full downloader (BMCLAPI mirror)" `
        --module-path $modulePath --module "com.snowmcd/com.snowmcd.Main" `
        --dest $dist --icon (Join-Path $root "src\main\resources\com\snowmcd\ui\app.ico") `
        --app-content "$root\LICENSE,$root\README.md" `
        --java-options "-Dfile.encoding=UTF-8"
    if ($LASTEXITCODE -ne 0) { throw "jpackage app-image 失败" }

    if (-not $SkipInstaller) {
        $wix = Get-Command candle.exe -ErrorAction SilentlyContinue
        if (-not $wix) {
            Write-Warning "未找到 WiX Toolset（candle.exe），跳过安装包。安装 WiX 3.x 并加入 PATH 后可生成 exe 安装包。"
        } else {
            Write-Host "[4/4] 生成安装包 dist\$appName-$version.exe ..." -ForegroundColor Cyan
            & $jpackage --type exe --name $appName --app-version $version `
                --vendor "SnowMCD Project" --description "Minecraft full downloader (BMCLAPI mirror)" `
                --module-path $modulePath --module "com.snowmcd/com.snowmcd.Main" `
                --dest $dist --icon (Join-Path $root "src\main\resources\com\snowmcd\ui\app.ico") `
                --app-content "$root\LICENSE,$root\README.md" `
                --java-options "-Dfile.encoding=UTF-8" `
                --license-file (Join-Path $root "LICENSE") `
                --win-per-user-install --win-dir-chooser --win-shortcut --win-menu `
                --win-menu-group "SnowMCD" --win-upgrade-uuid "7d2c5e0b-4e3a-4d3d-9e1f-2a6b8c9d0e1f" `
                --copyright "GPL-3.0-or-later"
            if ($LASTEXITCODE -ne 0) { throw "jpackage exe 失败" }
        }
    }

    Write-Host "`n构建完成：" -ForegroundColor Green
    Get-ChildItem $dist | ForEach-Object { Write-Host "  $($_.FullName)" }
} finally {
    Pop-Location
}


