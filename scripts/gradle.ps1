# Runs Gradle from the repo root with Android Studio's JBR 21 (never the system JDK 25).
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    & .\gradlew.bat @args
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
