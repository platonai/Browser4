$j = Get-content "$PSScriptRoot\site-code.json" -Raw | ConvertFrom-Json
$r = $j.output.result
Write-Output ("LEN=" + $r.len + " HITS=" + $r.hits.Count)
$i = 0
foreach ($h in $r.hits) {
  $i++
  Write-Output ("===== HIT $i @$($h.idx) =====")
  Write-Output $h.code
}
if ($r.prototypeLeakCtx) {
  Write-Output "===== PROTOTYPE LEAK CTX ====="
  Write-Output $r.prototypeLeakCtx
}
