# ==============================================================
#  看板娘图片背景去除（内联版，不用函数避免参数绑定问题）
# ==============================================================
#  原图 24bpp RGB 无 Alpha，背景是接近纯白的实心色。
#  用「从四边泛洪填充」只抠掉与边缘连通的白色，
#  角色内部的白色（衬衫、发带）不受影响。
# ==============================================================

Add-Type -AssemblyName System.Drawing

$src = "C:\Users\123\.dsh\attachments\v1\objects\34\3472d1cfac2bf9586be4ed656f661617e1123dfb2090a2707d207e0c3aa853d0"
$out = "D:\summerDiary\看板娘.png"

Write-Host "=== 读取原图 ==="
$bmp = New-Object System.Drawing.Bitmap($src)
$w = $bmp.Width
$h = $bmp.Height
Write-Host "  ${w} x ${h}"

$rect = New-Object System.Drawing.Rectangle(0, 0, $w, $h)
$data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
                      [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$stride = $data.Stride
$bytes = New-Object byte[] ($stride * $h)
[System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
$bmp.UnlockBits($data)

$thr = 246
$total = $w * $h
$isBg = New-Object bool[] $total

Write-Host "`n=== 泛洪填充（从四边向内扩散）==="
$stack = New-Object System.Collections.Generic.Stack[int]

# 四边种子
for ($x = 0; $x -lt $w; $x++) {
    foreach ($y in @(0, ($h - 1))) {
        $i = $y * $w + $x
        $p = $i * 4
        if (-not $isBg[$i] -and $bytes[$p] -ge $thr -and $bytes[$p+1] -ge $thr -and $bytes[$p+2] -ge $thr) {
            $isBg[$i] = $true
            $stack.Push($i)
        }
    }
}
for ($y = 0; $y -lt $h; $y++) {
    foreach ($x in @(0, ($w - 1))) {
        $i = $y * $w + $x
        $p = $i * 4
        if (-not $isBg[$i] -and $bytes[$p] -ge $thr -and $bytes[$p+1] -ge $thr -and $bytes[$p+2] -ge $thr) {
            $isBg[$i] = $true
            $stack.Push($i)
        }
    }
}

Write-Host "  种子数: $($stack.Count)"

$processed = 0
while ($stack.Count -gt 0) {
    $i = $stack.Pop()
    $processed++
    $x = $i % $w
    $y = [int][math]::Floor($i / $w)

    if ($x -gt 0) {
        $n = $i - 1
        if (-not $isBg[$n]) {
            $p = $n * 4
            if ($bytes[$p] -ge $thr -and $bytes[$p+1] -ge $thr -and $bytes[$p+2] -ge $thr) {
                $isBg[$n] = $true; $stack.Push($n)
            }
        }
    }
    if ($x -lt ($w - 1)) {
        $n = $i + 1
        if (-not $isBg[$n]) {
            $p = $n * 4
            if ($bytes[$p] -ge $thr -and $bytes[$p+1] -ge $thr -and $bytes[$p+2] -ge $thr) {
                $isBg[$n] = $true; $stack.Push($n)
            }
        }
    }
    if ($y -gt 0) {
        $n = $i - $w
        if (-not $isBg[$n]) {
            $p = $n * 4
            if ($bytes[$p] -ge $thr -and $bytes[$p+1] -ge $thr -and $bytes[$p+2] -ge $thr) {
                $isBg[$n] = $true; $stack.Push($n)
            }
        }
    }
    if ($y -lt ($h - 1)) {
        $n = $i + $w
        if (-not $isBg[$n]) {
            $p = $n * 4
            if ($bytes[$p] -ge $thr -and $bytes[$p+1] -ge $thr -and $bytes[$p+2] -ge $thr) {
                $isBg[$n] = $true; $stack.Push($n)
            }
        }
    }
}

$bgCount = 0
for ($i = 0; $i -lt $total; $i++) { if ($isBg[$i]) { $bgCount++ } }
$pct = [math]::Round($bgCount * 100 / $total, 1)
Write-Host "  背景像素: $bgCount / $total  ($pct%)"

Write-Host "`n=== 写入 Alpha 通道 ==="
$newBmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$nd = $newBmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::WriteOnly,
                       [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$nstride = $nd.Stride
$nbytes = New-Object byte[] ($nstride * $h)

for ($y = 0; $y -lt $h; $y++) {
    $rowSrc = $y * $w * 4
    $rowDst = $y * $nstride
    for ($x = 0; $x -lt $w; $x++) {
        $sp = $rowSrc + $x * 4
        $dp = $rowDst + $x * 4
        $nbytes[$dp]     = $bytes[$sp]
        $nbytes[$dp + 1] = $bytes[$sp + 1]
        $nbytes[$dp + 2] = $bytes[$sp + 2]
        if ($isBg[$y * $w + $x]) { $nbytes[$dp + 3] = 0 } else { $nbytes[$dp + 3] = 255 }
    }
}

[System.Runtime.InteropServices.Marshal]::Copy($nbytes, 0, $nd.Scan0, $nbytes.Length)
$newBmp.UnlockBits($nd)

$newBmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$newBmp.Dispose()
$bmp.Dispose()

$fi = Get-Item $out
Write-Host "`n=== 完成 ==="
Write-Host "  输出: $out"
Write-Host "  大小: $([math]::Round($fi.Length/1KB,1)) KB"
