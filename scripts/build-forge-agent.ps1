$ErrorActionPreference = 'Stop'

$workspaceRoot = Split-Path -Parent $PSScriptRoot
$forgeAgentDir = Join-Path $workspaceRoot 'forge-agent'
$gradleVersion = '8.1.1'
$gradleRoot = Join-Path $workspaceRoot '.local\gradle'
$gradleZip = Join-Path $gradleRoot "gradle-$gradleVersion-bin.zip"
$gradleHome = Join-Path $gradleRoot "gradle-$gradleVersion"
$gradleExe = Join-Path $gradleHome 'bin\gradle.bat'

$javaVersionOutput = (& cmd /c "java -version 2>&1" | Out-String)
if ($LASTEXITCODE -ne 0 -or $javaVersionOutput -notmatch 'version "(?<major>\d+)') {
    throw '[forge-agent-build] Java was not found or its version could not be detected. Forge 1.20.1 requires JDK 17.'
}
$javaMajor = [int]$Matches['major']
if ($javaMajor -ne 17) {
    throw "[forge-agent-build] Unsupported Java $javaMajor. Forge 1.20.1 in this project requires JDK 17; set JAVA_HOME and PATH to a JDK 17 installation."
}

New-Item -ItemType Directory -Force -Path $gradleRoot | Out-Null

if (!(Test-Path $gradleExe)) {
    $url = "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
    Write-Host "[forge-agent-build] downloading Gradle $gradleVersion from $url"
    Invoke-WebRequest -Uri $url -OutFile $gradleZip
    Expand-Archive -Path $gradleZip -DestinationPath $gradleRoot -Force
}

Write-Host "[forge-agent-build] using $gradleExe"
& $gradleExe -p $forgeAgentDir build
if ($LASTEXITCODE -ne 0) {
    throw "[forge-agent-build] Gradle failed with exit code $LASTEXITCODE."
}
