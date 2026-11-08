$ErrorActionPreference = 'Stop'
$projectDir = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectDir
try {
    & mvn -B verify
    if ($LASTEXITCODE -ne 0) { throw 'Build/test failed; server was not started.' }
    & java -Xms64m -Xmx160m -XX:+UseSerialGC -XX:MaxMetaspaceSize=72m -jar target/nso-web.jar
    if ($LASTEXITCODE -ne 0) { throw 'Server exited with an error. Check the startup log.' }
} finally {
    Pop-Location
}
