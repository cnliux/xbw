$ErrorActionPreference='Continue'
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
$t=[System.IO.File]::ReadAllText('G:\git\xbw\tools\_romtest\pclib.js')
$q = [char]39; $dq = [char]34
"=== 1990i / yikm 碎片 ==="
[regex]::Matches($t, "[$q$dq][^$q$dq]{0,48}(?:1990i|yikm)[^$q$dq]{0,48}[$q$dq]") | ForEach-Object { $_.Value } | Sort-Object -Unique
"=== 'rom' 相关短字符串 ==="
[regex]::Matches($t, "[$q$dq][A-Za-z0-9_\-\./]{2,34}rom[A-Za-z0-9_\-\./]{0,12}[$q$dq]") | ForEach-Object { $_.Value } | Sort-Object -Unique | Select-Object -First 50
"=== zip 相关短字符串 ==="
[regex]::Matches($t, "[$q$dq][A-Za-z0-9_\-\./]{2,34}zip[A-Za-z0-9_\-\./]{0,6}[$q$dq]") | ForEach-Object { $_.Value } | Sort-Object -Unique | Select-Object -First 30
"=== 'https://' 开头的完整字符串 ==="
[regex]::Matches($t, "[$q$dq]https?://[^$q$dq]{4,80}[$q$dq]") | ForEach-Object { $_.Value } | Sort-Object -Unique | Select-Object -First 40
"=== concat 拼接点（+. 前后）：找 rom 目录名 ==="
[regex]::Matches($t, "[A-Za-z_\$][A-Za-z0-9_\$]{0,24}\.zip") | ForEach-Object { $_.Value } | Sort-Object -Unique | Select-Object -First 20
"=== 含 gsystem 上下文 200 字符（找 gsystem 到 URL 的映射）==="
$idx=0; $found=0
while($found -lt 6){
  $i=$t.IndexOf('gsystem',$idx)
  if($i -lt 0){break}
  ("---@"+$i+"--- "+$t.Substring([Math]::Max(0,$i-120),320)) -replace '\s+',' '
  $idx=$i+7; $found++
}
