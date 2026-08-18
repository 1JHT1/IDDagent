$ErrorActionPreference = 'Stop'
$path = 'd:\HuaweiMoveData\Users\蒋洪涛\Desktop\IDDagent\data\conversations.json'
$data = Get-Content $path -Raw -Encoding UTF8 | ConvertFrom-Json
$convs = $data.conversations
if (-not $convs) { $convs = $data }
Write-Output ('总数: ' + $convs.Count)
$convs | Sort-Object { $_.created_at } | ForEach-Object {
    $cid = $_.id
    $msgs = @($_.messages)
    $planMsgs = @($msgs | Where-Object { $_.extra.action -eq 'plan_status' })
    $planIds = @($planMsgs | ForEach-Object { $_.extra.planId } | Select-Object -Unique)
    $reportMsgs = @($msgs | Where-Object { $_.extra._skill_name -eq 'generate_report' })
    Write-Output ('会话 ' + $cid + ' | 消息数:' + $msgs.Count + ' | plan_status数:' + $planMsgs.Count + ' | generate_report卡数:' + $reportMsgs.Count + ' | planIds: ' + ($planIds -join ','))
}
