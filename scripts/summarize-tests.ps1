$ErrorActionPreference = 'Stop'

$resultDir = Join-Path $PSScriptRoot '..\build\test-results\test'
$reports = @(Get-ChildItem -LiteralPath $resultDir -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)
if ($reports.Count -eq 0) {
    throw 'No JUnit XML reports were produced.'
}

$rows = foreach ($report in $reports) {
    [xml]$document = Get-Content -LiteralPath $report.FullName -Raw
    $suite = $document.testsuite
    [pscustomobject]@{
        Name = [string]$suite.name
        Tests = [int]$suite.tests
        Skipped = [int]$suite.skipped
        Failures = [int]$suite.failures + [int]$suite.errors
    }
}

$tests = ($rows | Measure-Object -Property Tests -Sum).Sum
$skipped = ($rows | Measure-Object -Property Skipped -Sum).Sum
$failures = ($rows | Measure-Object -Property Failures -Sum).Sum
$executed = $tests - $skipped
if ($executed -le 0) {
    throw "No tests executed: $tests discovered, $skipped skipped."
}

$summary = @(
    '# Test results',
    '',
    "Discovered: $tests · Executed: $executed · Skipped: $skipped · Failed: $failures",
    '',
    'Suites with skipped tests:',
    '',
    '| Suite | Skipped / Total |',
    '| --- | ---: |'
)
foreach ($row in ($rows | Where-Object { $_.Skipped -gt 0 } |
        Sort-Object -Property @{Expression = 'Skipped'; Descending = $true}, Name)) {
    $summary += "| $($row.Name) | $($row.Skipped) / $($row.Tests) |"
}
if ($skipped -eq 0) {
    $summary += '| None | 0 |'
}

$text = $summary -join "`n"
Write-Output $text
if ($env:GITHUB_STEP_SUMMARY) {
    Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value $text
}
