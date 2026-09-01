param(
    [switch]$Swing,
    [switch]$CheckRuntime
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$clientTarget = Join-Path $projectRoot 'vcampus-client/target'
$clientJar = Join-Path $clientTarget 'vcampus-client-1.0.0-SNAPSHOT.jar'
$runtimeDirectory = Join-Path $clientTarget 'lib'

if (-not (Test-Path -LiteralPath $clientJar) -or -not (Test-Path -LiteralPath $runtimeDirectory)) {
    throw 'Build the project first: mvn clean verify (see README.md).'
}

if ($env:JAVA_HOME) {
    $javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable)) { throw 'JAVA_HOME does not contain bin/java.exe.' }
} else {
    $javaExecutable = (Get-Command java.exe -ErrorAction Stop).Source
}

# Resolve only the Windows JavaFX modules; the remaining runtime libraries stay on the classpath.
$javafxJars = @(Get-ChildItem -LiteralPath $runtimeDirectory -Filter 'javafx-*-win.jar' -File)
if ($javafxJars.Count -lt 4) {
    throw 'The JavaFX Windows runtime is missing. Rebuild this project on Windows with Java 21.'
}
$javaArguments = @('--module-path', ($javafxJars.FullName -join [IO.Path]::PathSeparator),
    '--add-modules', 'javafx.controls,javafx.swing')
if ($CheckRuntime) {
    & $javaExecutable @javaArguments --validate-modules
} else {
    $classPath = $clientJar + [IO.Path]::PathSeparator + (Join-Path $runtimeDirectory '*')
    $javaArguments += @('-cp', $classPath, 'com.vcampus.client.ClientMain')
    if ($Swing) { $javaArguments += '--swing' }
    & $javaExecutable @javaArguments
}
if ($LASTEXITCODE -ne 0) { throw "VCampus Java process exited with code $LASTEXITCODE." }
