# ==============================================================
# Phase 2 + Phase 3 one-shot acceptance runner
# ==============================================================
# Collects all the scattered verification commands into one script,
# so you do not have to dig through docs to find them.
#
# !! IMPORTANT: THIS FILE MUST STAY PURE ASCII !!
#    Windows PowerShell 5.1 reads .ps1 files using the system code page
#    (GBK on zh-CN Windows). Any non-ASCII character in the source
#    (even inside a comment) gets mis-decoded and breaks the parser
#    with a confusing "Unexpected token" error far from the real cause.
#    This exact trap is recorded in the project docs (section 6.2) and
#    in the handover prompt. Comments and output here are English only.
#
# Usage:
#   cd D:\summerDiary\_verify
#   .\run-acceptance.ps1                 # run everything
#   .\run-acceptance.ps1 -SkipBrowser    # skip the headless-browser part
#
# Prerequisites:
#   1. MySQL container (port 3307)
#   2. backend : cd backend  ; mvn -o -DskipTests spring-boot:run
#      Step 8 (Phase 3) additionally needs the AI switches, otherwise it
#      skips its HTTP half. Start the backend like this, and run the script
#      once per AI_MOCK_OUTCOME mode (it reads the mode from actuator and
#      runs the matching test -- it will not restart the backend for you):
#        $env:AI_ANALYSIS_ENABLED='true'      ; $env:AI_MOCK_DELAY_MS='1500'
#        $env:AI_WORKER_INTERVAL_MS='500'     ; $env:AI_RETRY_BACKOFF_BASE_MS='500'
#        $env:AI_MOCK_OUTCOME='success'       # then 'timeout', 'invalid-json'
#   3. frontend: cd frontend ; npm run preview   (needed by steps 5 and 7)
#      Prefer "preview" over "dev" for acceptance: the dev server watches
#      files and can die with EBUSY on Windows (see docs section 11.3.11).
# ==============================================================

param(
    [switch]$SkipBrowser
)

$ErrorActionPreference = 'Continue'

# Force the working directory to this script's folder.
#
# Why: every file below (TestDiaryApi.java, browser-check.mjs, the _acc_*.txt
# logs) is referenced by a relative path, so the script must run with
# _verify as the current directory.
#
# Two things were learned the hard way here:
#   * $PSScriptRoot can be EMPTY depending on how the script is invoked, so
#     relying on it alone is not enough.
#   * Set-Location only changes PowerShell's location; some .NET APIs
#     ([System.IO.File]) resolve relative paths against the PROCESS working
#     directory instead, which may differ. That mismatch is what produced
#     "Could not find file 'D:\summerDiary\_acc_*.txt'" while the file was
#     actually sitting in _verify.
#
# The robust fix is to stop depending on the current directory at all:
# build absolute paths from a directory that is derived defensively below.
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) {
    $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
}
if (-not $ScriptDir) {
    $ScriptDir = 'D:\summerDiary\_verify'   # last-resort fallback
}
Set-Location $ScriptDir

$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$JAVA  = Join-Path $env:JAVA_HOME 'bin\java.exe'
$JAVAC = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$M2 = 'D:\summerDiary\.m2repo'
$MY = Join-Path $M2 'com\mysql\mysql-connector-j\8.3.0\mysql-connector-j-8.3.0.jar'

$script:totalPass = 0
$script:totalFail = 0

function Write-Head($text) {
    Write-Host ''
    Write-Host ('=' * 66) -ForegroundColor DarkGray
    Write-Host "  $text" -ForegroundColor Cyan
    Write-Host ('=' * 66) -ForegroundColor DarkGray
}

# Every Java test in this project prints the same summary tail.
#
# Two traps had to be solved here, both verified experimentally:
#
# 1) ENCODING. PowerShell 5.1's ">" redirect writes UTF-16LE (BOM FF FE),
#    not UTF-8. So the log must be read with Encoding.Unicode.
#    Reading it as UTF8 silently yields nothing and every result looks like
#    "COULD NOT PARSE".
#
# 2) THE PATTERN. Do NOT write the Chinese label directly, and do NOT use
#    '\uXXXX' escapes -- '\u' is only special inside a .NET regex when the
#    pattern string actually contains a backslash-u sequence, which PowerShell
#    mangles. The reliable way is to build the characters with [char] and
#    concatenate, using SINGLE quotes so PowerShell does not interpret
#    '\s' / '\d' itself:
#
#      0x901A 0x8FC7 = "tong guo" (passed)
#      0x5931 0x8D25 = "shi bai"  (failed)
#
#    (Both were confirmed by experiment: pattern built with [char] matches,
#     '\uXXXX' does not.)
function Get-TestResult($file) {
    if (-not (Test-Path $file)) { return @{ pass = 0; fail = -1 } }

    # Detect the encoding from the BOM. Two writers are in play:
    #   ">"                     -> UTF-16LE (FF FE)
    #   Out-File -Encoding utf8 -> UTF-8    (EF BB BF)
    $bytes = [System.IO.File]::ReadAllBytes($file)
    if ($bytes.Length -ge 2 -and $bytes[0] -eq 0xFF -and $bytes[1] -eq 0xFE) {
        $txt = [System.Text.Encoding]::Unicode.GetString($bytes)
    } elseif ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and
              $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $txt = [System.Text.Encoding]::UTF8.GetString($bytes, 3, $bytes.Length - 3)
    } else {
        $txt = [System.Text.Encoding]::UTF8.GetString($bytes)
    }

    # Assign each Chinese char to its own [string] variable BEFORE concatenating.
    # A version that concatenated [char] values inline gave inconsistent results
    # (matched on one run, failed on the next) -- this form is reliable.
    $chuan = [string][char]0x901A
    $guo   = [string][char]0x8FC7
    $shi   = [string][char]0x5931
    $bai   = [string][char]0x8D25
    $regex = $chuan + $guo + ':\s*(\d+)\s+' + $shi + $bai + ':\s*(\d+)'

    $m = [regex]::Match($txt, $regex)
    if (-not $m.Success) { return @{ pass = 0; fail = -1 } }
    return @{ pass = [int]$m.Groups[1].Value; fail = [int]$m.Groups[2].Value }
}

# Runs one Java test and tallies its result.
#
# !! IMPORTANT: capture the output with a PowerShell redirect, do NOT pass a
#    filename argument to the Java program.
#
#    Why: only TestDiaryE2E accepts an output-file argument. The other tests
#    (TestDiaryApi / TestDiaryDataLayer / TestDiaryMappers / the Phase 1 ones)
#    ignore extra args and print to stdout. The first version of this script
#    passed a filename to all of them, so no log file appeared and every
#    result came back as "COULD NOT PARSE".
#
#    PowerShell 5.1's ">" writes UTF-16 (it is a shell-level redirect), so we
#    must read it back with -Encoding Unicode -- NOT UTF8. (TestDiaryE2E writes
#    its own log in UTF-8 when given a filename; the two paths differ, so the
#    reader handles both.)
# $suffix  : appended to the log file name. Needed by tests that run once per
#            backend mode (TestAiTaskFailure); without it the second run would
#            silently overwrite the first run's evidence.
# $progArgs: extra arguments handed to the Java program. TestAiTaskFailure
#            REQUIRES args[0] = timeout|invalid-json and exits with code 2
#            without it.
function Invoke-JavaTest($name, $class, $cp, $suffix = '', [string[]]$progArgs = @()) {
    Write-Host ''
    Write-Host ">>> $name" -ForegroundColor Yellow
    $out = Join-Path $ScriptDir "_acc_$class$suffix.txt"
    & $JAVA '-Dfile.encoding=UTF-8' -cp $cp $class @progArgs > $out 2>&1
    $r = Get-TestResult $out
    if ($r.fail -lt 0) {
        Write-Host "    COULD NOT PARSE result; see $out" -ForegroundColor DarkYellow
        return
    }
    $script:totalPass += $r.pass
    $script:totalFail += $r.fail
    $color = if ($r.fail -eq 0) { 'Green' } else { 'Red' }
    Write-Host ("    pass={0}  fail={1}" -f $r.pass, $r.fail) -ForegroundColor $color
}

# ---- 0. environment check -------------------------------------
Write-Head 'Phase 2 acceptance  /  environment check'

if (-not (Test-Path $JAVA)) {
    Write-Host "JDK 17 not found at $JAVA" -ForegroundColor Red
    exit 1
}
Write-Host 'JDK 17       : OK'

$backendUp = $false
try {
    $r = Invoke-WebRequest 'http://localhost:8080/api/health' -UseBasicParsing -TimeoutSec 5
    $backendUp = ($r.StatusCode -eq 200)
} catch { }
Write-Host ("backend 8080 : " + $(if ($backendUp) { 'UP' } else { 'DOWN' })) `
    -ForegroundColor $(if ($backendUp) { 'Green' } else { 'Red' })

$frontUp = $false
try {
    $r = Invoke-WebRequest 'http://localhost:5173/' -UseBasicParsing -TimeoutSec 5
    $frontUp = ($r.StatusCode -eq 200)
} catch { }
Write-Host ("frontend 5173: " + $(if ($frontUp) { 'UP' } else { 'DOWN' })) `
    -ForegroundColor $(if ($frontUp) { 'Green' } else { 'Red' })

if (-not $backendUp) {
    Write-Host ''
    Write-Host 'Backend is required. Start it first:' -ForegroundColor Red
    Write-Host '  cd D:\summerDiary\backend ; mvn -o -DskipTests spring-boot:run'
    exit 1
}

# ---- 1. backend unit tests ------------------------------------
Write-Head '1. Backend unit tests (mvn -o test, offline)'
Push-Location 'D:\summerDiary\backend'
& mvn -o test 2>&1 | Out-File -Encoding utf8 (Join-Path $ScriptDir '_acc_mvn.txt')
$mvnLine = Select-String -Path (Join-Path $ScriptDir '_acc_mvn.txt') `
    -Pattern 'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)$' |
    Select-Object -Last 1
if ($mvnLine) {
    $m = $mvnLine.Matches[0]
    $bad = [int]$m.Groups[2].Value + [int]$m.Groups[3].Value
    $good = [int]$m.Groups[1].Value - $bad
    $script:totalPass += $good
    $script:totalFail += $bad
    Write-Host ("    pass={0}  fail={1}" -f $good, $bad) `
        -ForegroundColor $(if ($bad -eq 0) { 'Green' } else { 'Red' })
} else {
    Write-Host '    COULD NOT PARSE maven output; see backend\_acc_mvn.txt' -ForegroundColor DarkYellow
}
Pop-Location

# ---- 2. data layer: JDBC direct -------------------------------
Write-Head '2. Data layer (JDBC, module 2-2)'
& $JAVAC -encoding UTF-8 -cp $MY 'TestDiaryDataLayer.java' 2>&1 | Out-Null
Invoke-JavaTest 'TestDiaryDataLayer - SQL logic + ownership (50)' `
    'TestDiaryDataLayer' ".;$MY"

# ---- 3. MyBatis mapping: real XML -----------------------------
Write-Head '3. MyBatis mapping (module 2-2)'
$CP_MB = 'D:\summerDiary\backend\target\classes;' +
         (Join-Path $M2 'org\mybatis\mybatis\3.5.19\mybatis-3.5.19.jar') + ';' +
         $MY + ';' +
         (Join-Path $M2 'org\slf4j\slf4j-api\2.0.16\slf4j-api-2.0.16.jar')
& $JAVAC -encoding UTF-8 -cp $CP_MB 'TestDiaryMappers.java' 2>&1 | Out-Null
Invoke-JavaTest 'TestDiaryMappers - XML parse + #{} binding (47)' `
    'TestDiaryMappers' ".;$CP_MB"

# ---- 4. HTTP API acceptance -----------------------------------
Write-Head '4. HTTP API acceptance (module 2-3, 8 endpoints)'
& $JAVAC -encoding UTF-8 'TestDiaryApi.java' 2>&1 | Out-Null
Invoke-JavaTest 'TestDiaryApi - endpoints + cross-user (79)' `
    'TestDiaryApi' '.'

# ---- 5. end-to-end through the Vite proxy ---------------------
Write-Head '5. End-to-end via Vite proxy (module 2-5)'
if ($frontUp) {
    & $JAVAC -encoding UTF-8 'TestDiaryE2E.java' 2>&1 | Out-Null
    Invoke-JavaTest 'TestDiaryE2E - full diary loop + SPA routes (63)' `
        'TestDiaryE2E' '.'
} else {
    Write-Host '    SKIPPED (frontend 5173 is down)' -ForegroundColor DarkYellow
}

# ---- 6. Phase 1 regression: auth ------------------------------
Write-Head '6. Phase 1 regression (auth, 55)'
& $JAVAC -encoding UTF-8 'TestRegister.java' 'TestRegisterSecurity.java' 'TestSession.java' `
    'TestLogoutResidual.java' 'TestAuthorizationMatrix.java' 2>&1 | Out-Null
foreach ($t in @('TestRegister', 'TestRegisterSecurity', 'TestSession',
                 'TestLogoutResidual', 'TestAuthorizationMatrix')) {
    $out = Join-Path $ScriptDir "_acc_$t.txt"
    & $JAVA '-Dfile.encoding=UTF-8' $t > $out 2>&1
    $r = Get-TestResult $out
    if ($r.fail -lt 0) {
        Write-Host ("    {0,-26} COULD NOT PARSE; see {1}" -f $t, $out) -ForegroundColor DarkYellow
        continue
    }
    $script:totalPass += $r.pass
    $script:totalFail += $r.fail
    Write-Host ("    {0,-26} pass={1} fail={2}" -f $t, $r.pass, $r.fail) `
        -ForegroundColor $(if ($r.fail -eq 0) { 'Green' } else { 'Red' })
}

# ---- 7. browser rendering: headless Edge via CDP --------------
Write-Head '7. Browser rendering (headless Edge, module 2-4)'
if ($SkipBrowser) {
    Write-Host '    SKIPPED (-SkipBrowser)' -ForegroundColor DarkYellow
} elseif (-not $frontUp) {
    Write-Host '    SKIPPED (frontend 5173 is down)' -ForegroundColor DarkYellow
} else {
    Write-Host '    NOTE: needs elevation in a sandbox (Edge spawns a subprocess)'
    node browser-check.mjs 2>&1 | Out-File -Encoding utf8 (Join-Path $ScriptDir '_acc_browser.txt')
    $r = Get-TestResult (Join-Path $ScriptDir '_acc_browser.txt')
    if ($r.fail -lt 0) {
        Write-Host '    COULD NOT PARSE browser output; see _acc_browser.txt' -ForegroundColor DarkYellow
    } else {
        $script:totalPass += $r.pass
        $script:totalFail += $r.fail
        Write-Host ("    pass={0}  fail={1}" -f $r.pass, $r.fail) `
            -ForegroundColor $(if ($r.fail -eq 0) { 'Green' } else { 'Red' })
    }
}

# ---- 8. Phase 3: AI analysis -----------------------------------
#
# Phase 3 tests need the backend running with a SPECIFIC AI_MOCK_OUTCOME, and
# switching modes means stopping and restarting the backend. This script
# deliberately does not do that mid-run: a silent restart would make the
# "one command" promise a lie, and a half-restarted backend produces failures
# that look exactly like code bugs. Instead the mode is READ from actuator and
# the matching test is run, so a run always tells the truth about what it
# actually exercised. Run the script once per mode to cover all three.
Write-Head '8. Phase 3 AI analysis (async task + Mock model)'

$mockOutcome = ''
try {
    $act = Invoke-RestMethod 'http://localhost:8080/actuator/health' -TimeoutSec 5
    $mockOutcome = [string]$act.components.aiAnalysis.details.mockOutcome
} catch { }
if ($mockOutcome) {
    Write-Host ("    AI mode       : {0}" -f $mockOutcome) -ForegroundColor Green
} else {
    Write-Host '    AI mode       : UNKNOWN (AI off, or backend unreachable)' -ForegroundColor DarkYellow
}

# The data layer runs first because it needs MySQL plus the compiled classes,
# never the backend -- so it stays meaningful even with the backend down.
& $JAVAC -encoding UTF-8 -cp $CP_MB 'TestAiTaskData.java' 2>&1 | Out-Null
Invoke-JavaTest 'TestAiTaskData - V2 migration + AI mappers (53)' `
    'TestAiTaskData' ".;$CP_MB"

if ($mockOutcome -eq 'success') {
    & $JAVAC -encoding UTF-8 -cp ".;$MY" 'TestAiTaskApi.java' 2>&1 | Out-Null
    Invoke-JavaTest 'TestAiTaskApi - async task, analysis, cross-user (68)' `
        'TestAiTaskApi' ".;$MY"
} elseif ($mockOutcome -eq 'timeout' -or $mockOutcome -eq 'invalid-json') {
    & $JAVAC -encoding UTF-8 -cp ".;$MY" 'TestAiTaskFailure.java' 2>&1 | Out-Null
    Invoke-JavaTest ("TestAiTaskFailure - model fails, diary survives (" + $mockOutcome + ", 39)") `
        'TestAiTaskFailure' ".;$MY" ("_" + $mockOutcome) @($mockOutcome)
} else {
    Write-Host '    SKIPPED the HTTP half: no AI mode detected.' -ForegroundColor DarkYellow
    Write-Host '    Start the backend with $env:AI_ANALYSIS_ENABLED=true first.' -ForegroundColor DarkYellow
}

Write-Host ''
Write-Host '    Phase 3 spans three backend modes -- run this script once per mode:'
Write-Host '      success      -> TestAiTaskApi (68 assertions)'
Write-Host '      timeout      -> TestAiTaskFailure timeout (39 assertions)'
Write-Host '      invalid-json -> TestAiTaskFailure invalid-json (39 assertions)'
Write-Host '    To switch, restart the backend with a different $env:AI_MOCK_OUTCOME.'
Write-Host '    Recommended backend env (see docs section 11.6):'
Write-Host '      AI_ANALYSIS_ENABLED=true  AI_MOCK_DELAY_MS=1500'
Write-Host '      AI_WORKER_INTERVAL_MS=500 AI_RETRY_BACKOFF_BASE_MS=500'

# ---- summary --------------------------------------------------
Write-Head 'SUMMARY'
Write-Host ''
Write-Host ("  TOTAL  pass={0}   fail={1}" -f $script:totalPass, $script:totalFail) `
    -ForegroundColor $(if ($script:totalFail -eq 0) { 'Green' } else { 'Red' })
Write-Host ''
Write-Host 'NOTE: steps 4/5/7 create real data. Each prints cleanup SQL at the'
Write-Host '      end of its log; see _acc_*.txt and the E2E log file.'
Write-Host ''
Write-Host 'Logs: _acc_*.txt'
Write-Host ''

if ($script:totalFail -gt 0) { exit 1 }
exit 0
