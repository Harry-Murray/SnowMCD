$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

function Resolve-Java {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return (Join-Path $env:JAVA_HOME "bin\java.exe")
    }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) { return $java.Source }
    throw "未找到 Java：请设置 JAVA_HOME。"
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

$mvn = Resolve-Maven
$java = Resolve-Java
Push-Location $root
try {
    $appJar = Join-Path $root "target\snowmcd-1.0.0.jar"
    if (-not (Test-Path $appJar)) {
        throw "请先运行 build.ps1 或 mvn package 生成 $appJar"
    }
    $cpFile = Join-Path $root "target\cp.txt"
    if (-not (Test-Path $cpFile)) {
        & $mvn -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt" "-Dmdep.includeScope=runtime"
        if ($LASTEXITCODE -ne 0) { throw "dependency:build-classpath 失败" }
    }
    $classpath = (Get-Content $cpFile -Raw).Trim() -split ';'
    $modules = @()
    foreach ($entry in $classpath) {
        if ([string]::IsNullOrWhiteSpace($entry) -or -not (Test-Path $entry)) { continue }
        if ($entry -match '\\org\\openjfx\\') {
            if ($entry -match '-win\.jar$') { $modules += (Resolve-Path $entry).Path }
        } elseif ($entry -match 'gson-[0-9]') {
            $modules += (Resolve-Path $entry).Path
        }
    }
    $modulePath = (@($appJar) + $modules) -join ';'
    & $java --module-path $modulePath -m com.snowmcd/com.snowmcd.Main --cli @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
