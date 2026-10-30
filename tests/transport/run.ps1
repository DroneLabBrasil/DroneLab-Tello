$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$testOutput = Join-Path ([System.IO.Path]::GetTempPath()) ('tellono-transport-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testOutput | Out-Null
try {
    $sources = @(
        (Join-Path $PSScriptRoot 'android/net/Network.java'),
        (Join-Path $PSScriptRoot 'android/util/Log.java'),
        (Join-Path $PSScriptRoot 'android/os/SystemClock.java'),
        (Join-Path $repoRoot 'app/src/main/java/com/duvitech/network/udp/UDPClient.java'),
        (Join-Path $repoRoot 'app/src/main/java/com/duvitech/network/udp/UDPStatusServer.java'),
        (Join-Path $PSScriptRoot 'TransportTest.java')
    )
    & javac -d $testOutput @sources
    if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
    & java -cp $testOutput TransportTest
    if ($LASTEXITCODE -ne 0) { throw 'transport tests failed' }
} finally {
    Remove-Item -Recurse -Force $testOutput
}
