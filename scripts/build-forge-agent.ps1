$ErrorActionPreference = 'Stop'

$workspaceRoot = Split-Path -Parent $PSScriptRoot
$forgeAgentDir = Join-Path $workspaceRoot 'forge-agent'
$gradleVersion = '8.1.1'
$gradleRoot = Join-Path $workspaceRoot '.local\gradle'
$gradleZip = Join-Path $gradleRoot "gradle-$gradleVersion-bin.zip"
$gradleHome = Join-Path $gradleRoot "gradle-$gradleVersion"
$gradleExe = Join-Path $gradleHome 'bin\gradle.bat'

New-Item -ItemType Directory -Force -Path $gradleRoot | Out-Null

if (!(Test-Path $gradleExe)) {
    $url = "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
    Write-Host "[forge-agent-build] downloading Gradle $gradleVersion from $url"
    Invoke-WebRequest -Uri $url -OutFile $gradleZip
    Expand-Archive -Path $gradleZip -DestinationPath $gradleRoot -Force
}

Write-Host "[forge-agent-build] using $gradleExe"
& $gradleExe -p $forgeAgentDir build
