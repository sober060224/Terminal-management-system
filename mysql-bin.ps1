# 找出本机 MySQL 的可执行文件，输出一行路径；找不到就什么都不输出。
# 查找顺序：PATH 上的 mysqld → 注册成 Windows 服务的安装（从 ImagePath 里抠出 mysqld.exe）。
# client 就是同目录下的 mysql.exe。
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File mysql-bin.ps1 server|client
param(
    [ValidateSet('server', 'client')]
    [string] $Which = 'server'
)

$server = $null
$cmd = Get-Command mysqld.exe -ErrorAction SilentlyContinue
if ($null -ne $cmd) {
    $server = $cmd.Source
} else {
    $svc = Get-CimInstance Win32_Service -ErrorAction SilentlyContinue |
        Where-Object { $_.PathName -match 'mysqld' } |
        Select-Object -First 1
    if ($null -ne $svc -and $null -ne $svc.PathName) {
        $m = [regex]::Match($svc.PathName, '^"?([^"]*mysqld\.exe)')
        if ($m.Success) { $server = $m.Groups[1].Value }
    }
}

if ($null -eq $server) { return }
if ($Which -eq 'server') {
    Write-Output $server
    return
}

$client = Join-Path (Split-Path $server) 'mysql.exe'
if (Test-Path $client) { Write-Output $client } else { Write-Output $server }
