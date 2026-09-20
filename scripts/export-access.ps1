[CmdletBinding()]
param(
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\dist\VCampus-Access.accdb')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Quote-Name([string]$Name) {
    return '[' + $Name.Replace(']', ']]') + ']'
}

function Quote-Text([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

function Invoke-Sql($Connection, [string]$Sql) {
    [void]$Connection.Execute($Sql)
}

function Get-Scalar($Connection, [string]$Sql) {
    $recordset = $Connection.Execute($Sql)
    try {
        if ($recordset.EOF) { return $null }
        return $recordset.Fields.Item(0).Value
    }
    finally {
        $recordset.Close()
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($recordset)
    }
}

function Split-TopLevel([string]$Text) {
    $items = [Collections.Generic.List[string]]::new()
    $start = 0
    $depth = 0
    $inQuote = $false
    for ($i = 0; $i -lt $Text.Length; $i++) {
        $ch = $Text[$i]
        if ($ch -eq "'") {
            if ($inQuote -and $i + 1 -lt $Text.Length -and $Text[$i + 1] -eq "'") {
                $i++
                continue
            }
            $inQuote = -not $inQuote
            continue
        }
        if ($inQuote) { continue }
        if ($ch -eq '(') { $depth++; continue }
        if ($ch -eq ')') { $depth--; continue }
        if ($ch -eq ',' -and $depth -eq 0) {
            $items.Add($Text.Substring($start, $i - $start).Trim())
            $start = $i + 1
        }
    }
    if ($start -lt $Text.Length) {
        $items.Add($Text.Substring($start).Trim())
    }
    return $items
}

function Get-CreateTableBlocks([string]$Sql) {
    $blocks = [Collections.Generic.List[object]]::new()
    $pattern = [regex]::new(
        'CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*\(',
        [Text.RegularExpressions.RegexOptions]::IgnoreCase
    )
    foreach ($match in $pattern.Matches($Sql)) {
        $open = $match.Index + $match.Length - 1
        $depth = 1
        $inQuote = $false
        $close = -1
        for ($i = $open + 1; $i -lt $Sql.Length; $i++) {
            $ch = $Sql[$i]
            if ($ch -eq "'") {
                if ($inQuote -and $i + 1 -lt $Sql.Length -and $Sql[$i + 1] -eq "'") {
                    $i++
                    continue
                }
                $inQuote = -not $inQuote
                continue
            }
            if ($inQuote) { continue }
            if ($ch -eq '(') { $depth++ }
            elseif ($ch -eq ')') {
                $depth--
                if ($depth -eq 0) { $close = $i; break }
            }
        }
        if ($close -lt 0) {
            throw "无法定位数据表 $($match.Groups['name'].Value) 的结束括号。"
        }
        $blocks.Add([pscustomobject]@{
            Name = $match.Groups['name'].Value
            Body = $Sql.Substring($open + 1, $close - $open - 1)
        })
    }
    return $blocks
}

function Convert-ColumnList([string]$List) {
    $names = foreach ($rawName in ($List -split ',')) {
        $name = $rawName.Trim().Trim('`')
        $name = $name -replace '\s+(ASC|DESC)$', ''
        $name = $name -replace '\(\d+\)$', ''
        Quote-Name $name
    }
    return ($names -join ', ')
}

function Convert-DataType([string]$BaseType, [string]$Size) {
    switch ($BaseType.ToUpperInvariant()) {
        { $_ -in @('BIGINT', 'INT', 'SMALLINT', 'TINYINT') } { return 'LONG' }
        'BOOLEAN' { return 'YESNO' }
        { $_ -in @('DATE', 'TIME', 'TIMESTAMP') } { return 'DATETIME' }
        'TEXT' { return 'LONGTEXT' }
        'DECIMAL' { return "DECIMAL($Size)" }
        { $_ -in @('CHAR', 'VARCHAR') } {
            $length = [int]$Size
            if ($length -le 255) { return "TEXT($length)" }
            return 'LONGTEXT'
        }
        default { throw "不支持的 MySQL 字段类型：$BaseType($Size)" }
    }
}

function New-ShortName([string]$Prefix, [string]$Table, [string]$Column) {
    $name = "${Prefix}_${Table}_${Column}" -replace '[^A-Za-z0-9_]', '_'
    if ($name.Length -gt 60) { return $name.Substring(0, 60) }
    return $name
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$schemaPath = Join-Path $projectRoot 'database\schema.sql'
$resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
if ([IO.Path]::GetExtension($resolvedOutput) -ne '.accdb') {
    throw '输出文件必须使用 .accdb 扩展名。'
}
if (-not (Test-Path -LiteralPath $schemaPath -PathType Leaf)) {
    throw "未找到数据库结构文件：$schemaPath"
}

$outputDirectory = Split-Path -Parent $resolvedOutput
[IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
$temporaryOutput = Join-Path $outputDirectory 'VCampus-Access.building.accdb'
if (Test-Path -LiteralPath $temporaryOutput) {
    Remove-Item -LiteralPath $temporaryOutput -Force
}

$schemaSql = [IO.File]::ReadAllText($schemaPath, [Text.Encoding]::UTF8)
$schemaSql = [regex]::Replace($schemaSql, '(?m)--.*$', '')
$tableBlocks = @(Get-CreateTableBlocks $schemaSql)
if ($tableBlocks.Count -eq 0) { throw 'schema.sql 中没有找到 CREATE TABLE 语句。' }

$indexes = [Collections.Generic.List[object]]::new()
$foreignKeys = [Collections.Generic.List[object]]::new()
$conversionLog = [Collections.Generic.List[object]]::new()
$tableDdls = [Collections.Generic.List[object]]::new()

foreach ($table in $tableBlocks) {
    $definitions = [Collections.Generic.List[string]]::new()
    $primaryKeyColumns = $null
    foreach ($partValue in (Split-TopLevel $table.Body)) {
        $part = ([regex]::Replace($partValue, '\s+', ' ')).Trim()
        if (-not $part) { continue }

        $primaryMatch = [regex]::Match($part, '^PRIMARY\s+KEY\s*\((?<columns>[^)]+)\)', 'IgnoreCase')
        if ($primaryMatch.Success) {
            $primaryKeyColumns = Convert-ColumnList $primaryMatch.Groups['columns'].Value
            continue
        }

        $indexMatch = [regex]::Match(
            $part,
            '^(?<unique>UNIQUE\s+)?(?:INDEX|KEY)\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*\((?<columns>[^)]+)\)',
            'IgnoreCase'
        )
        if ($indexMatch.Success) {
            $indexes.Add([pscustomobject]@{
                Table = $table.Name
                Name = $indexMatch.Groups['name'].Value
                Columns = Convert-ColumnList $indexMatch.Groups['columns'].Value
                Unique = $indexMatch.Groups['unique'].Success
            })
            continue
        }

        $foreignMatch = [regex]::Match(
            $part,
            '^(?:CONSTRAINT\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)\s+)?FOREIGN\s+KEY\s*\((?<columns>[^)]+)\)\s+REFERENCES\s+(?<target>[A-Za-z_][A-Za-z0-9_]*)\s*\((?<targetColumns>[^)]+)\)',
            'IgnoreCase'
        )
        if ($foreignMatch.Success) {
            $fkName = $foreignMatch.Groups['name'].Value
            if (-not $fkName) { $fkName = New-ShortName 'fk' $table.Name 'relation' }
            $foreignKeys.Add([pscustomobject]@{
                Table = $table.Name
                Name = $fkName
                Columns = Convert-ColumnList $foreignMatch.Groups['columns'].Value
                Target = $foreignMatch.Groups['target'].Value
                TargetColumns = Convert-ColumnList $foreignMatch.Groups['targetColumns'].Value
            })
            if ($part -match 'ON\s+DELETE|ON\s+UPDATE') {
                $conversionLog.Add([pscustomobject]@{
                    Type = '外键动作'; Name = "$($table.Name).$fkName"; Status = '已简化'
                    Detail = 'Access 版本保留外键关系，但省略 MySQL 的 ON DELETE/ON UPDATE 动作。'
                })
            }
            continue
        }

        if ($part -match '^(?:CONSTRAINT\s+\w+\s+)?CHECK\s*\(') {
            $checkName = if ($part -match '^CONSTRAINT\s+(\w+)') { $Matches[1] } else { 'unnamed_check' }
            $conversionLog.Add([pscustomobject]@{
                Type = '检查约束'; Name = "$($table.Name).$checkName"; Status = '仅记录'
                Detail = 'MySQL CHECK/REGEXP 表达式未写入 Access，请以原 schema.sql 为准。'
            })
            continue
        }

        $columnMatch = [regex]::Match(
            $part,
            '^(?<name>[A-Za-z_][A-Za-z0-9_]*)\s+(?<type>[A-Za-z]+)(?:\((?<size>[0-9,]+)\))?',
            'IgnoreCase'
        )
        if (-not $columnMatch.Success) {
            $conversionLog.Add([pscustomobject]@{
                Type = '结构片段'; Name = $table.Name; Status = '无法识别'; Detail = $part
            })
            continue
        }

        $columnName = $columnMatch.Groups['name'].Value
        $baseType = $columnMatch.Groups['type'].Value
        $size = $columnMatch.Groups['size'].Value
        $accessType = Convert-DataType $baseType $size
        $isAutoNumber = $part -match '\bAUTO_INCREMENT\b'
        $isGenerated = $part -match '\bGENERATED\s+ALWAYS\b'
        if ($isAutoNumber) { $accessType = 'AUTOINCREMENT' }

        $columnDefinition = "$(Quote-Name $columnName) $accessType"
        if (-not $isGenerated -and $part -match '\bNOT\s+NULL\b') {
            $columnDefinition += ' NOT NULL'
        }

        if (-not $isAutoNumber -and -not $isGenerated -and $accessType -ne 'LONGTEXT') {
            $defaultMatch = [regex]::Match(
                $part,
                "\bDEFAULT\s+(?<value>'(?:''|[^'])*'|CURRENT_TIMESTAMP|TRUE|FALSE|-?[0-9]+(?:\.[0-9]+)?)",
                'IgnoreCase'
            )
            if ($defaultMatch.Success) {
                $defaultValue = $defaultMatch.Groups['value'].Value
                if ($defaultValue -match '^CURRENT_TIMESTAMP$') { $defaultValue = 'Now()' }
                elseif ($defaultValue -match '^TRUE$') { $defaultValue = '-1' }
                elseif ($defaultValue -match '^FALSE$') { $defaultValue = '0' }
                $columnDefinition += " DEFAULT $defaultValue"
            }
        }
        $definitions.Add($columnDefinition)

        if ($part -match '\bPRIMARY\s+KEY\b') {
            $primaryKeyColumns = Quote-Name $columnName
        }
        if ($part -match '\bUNIQUE\b') {
            $indexes.Add([pscustomobject]@{
                Table = $table.Name
                Name = New-ShortName 'ux' $table.Name $columnName
                Columns = Quote-Name $columnName
                Unique = $true
            })
        }
        if ($isGenerated) {
            $conversionLog.Add([pscustomobject]@{
                Type = '生成列'; Name = "$($table.Name).$columnName"; Status = '普通字段'
                Detail = 'Access 快照中转换为可空普通字段；生成表达式请查阅 MySQL schema.sql。'
            })
        }
        if ($part -match '\bON\s+UPDATE\s+CURRENT_TIMESTAMP\b') {
            $conversionLog.Add([pscustomobject]@{
                Type = '自动更新时间'; Name = "$($table.Name).$columnName"; Status = '仅记录'
                Detail = 'Access 不支持同等的 ON UPDATE 语法。'
            })
        }
    }

    if ($primaryKeyColumns) {
        $definitions.Add("CONSTRAINT $(Quote-Name (New-ShortName 'pk' $table.Name '')) PRIMARY KEY ($primaryKeyColumns)")
    }
    $ddl = "CREATE TABLE $(Quote-Name $table.Name) (" + ($definitions -join ', ') + ')'
    $tableDdls.Add([pscustomobject]@{ Name = $table.Name; Sql = $ddl })
}

$catalog = $null
$connection = $null
$provider = $null
foreach ($candidate in @('Microsoft.ACE.OLEDB.16.0', 'Microsoft.ACE.OLEDB.12.0')) {
    try {
        $candidateConnection = "Provider=$candidate;Data Source=$temporaryOutput;Jet OLEDB:Engine Type=5;"
        $catalog = New-Object -ComObject ADOX.Catalog
        [void]$catalog.Create($candidateConnection)
        [void]$catalog.ActiveConnection.Close()
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($catalog)
        $catalog = $null
        $provider = $candidate
        break
    }
    catch {
        if ($catalog) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($catalog); $catalog = $null }
        if (Test-Path -LiteralPath $temporaryOutput) { Remove-Item -LiteralPath $temporaryOutput -Force }
    }
}
if (-not $provider) {
    throw '未检测到 Microsoft Access Database Engine（ACE OLEDB 12/16），无法生成 .accdb。'
}

$connectionString = "Provider=$provider;Data Source=$temporaryOutput;"
$connection = New-Object -ComObject ADODB.Connection
$connection.Open($connectionString)
$tableFailures = [Collections.Generic.List[string]]::new()

try {
    foreach ($tableDdl in $tableDdls) {
        try {
            Invoke-Sql $connection $tableDdl.Sql
        }
        catch {
            $fallback = [regex]::Replace(
                $tableDdl.Sql,
                "\s+DEFAULT\s+(?:Now\(\)|-?[0-9]+(?:\.[0-9]+)?|'(?:''|[^'])*')",
                '',
                [Text.RegularExpressions.RegexOptions]::IgnoreCase
            )
            try {
                Invoke-Sql $connection $fallback
                $conversionLog.Add([pscustomobject]@{
                    Type = '数据表'; Name = $tableDdl.Name; Status = '已降级'
                    Detail = 'ACE 不接受一个或多个 DEFAULT 子句，已保留字段并省略默认值。'
                })
            }
            catch {
                $tableFailures.Add("$($tableDdl.Name): $($_.Exception.Message)")
            }
        }
    }
    if ($tableFailures.Count -gt 0) {
        throw "以下数据表转换失败：`n$($tableFailures -join "`n")"
    }

    $createdIndexes = 0
    foreach ($index in $indexes) {
        $uniqueSql = if ($index.Unique) { 'UNIQUE ' } else { '' }
        $sql = "CREATE ${uniqueSql}INDEX $(Quote-Name $index.Name) ON $(Quote-Name $index.Table) ($($index.Columns))"
        try {
            Invoke-Sql $connection $sql
            $createdIndexes++
        }
        catch {
            $conversionLog.Add([pscustomobject]@{
                Type = '索引'; Name = "$($index.Table).$($index.Name)"; Status = '创建失败'
                Detail = $_.Exception.Message
            })
        }
    }

    $createdForeignKeys = 0
    foreach ($foreignKey in $foreignKeys) {
        $sql = "ALTER TABLE $(Quote-Name $foreignKey.Table) ADD CONSTRAINT $(Quote-Name $foreignKey.Name) " +
               "FOREIGN KEY ($($foreignKey.Columns)) REFERENCES $(Quote-Name $foreignKey.Target) ($($foreignKey.TargetColumns))"
        try {
            Invoke-Sql $connection $sql
            $createdForeignKeys++
        }
        catch {
            $conversionLog.Add([pscustomobject]@{
                Type = '外键'; Name = "$($foreignKey.Table).$($foreignKey.Name)"; Status = '创建失败'
                Detail = $_.Exception.Message
            })
        }
    }

    # 匿名化的课程演示基础数据；不写入用户、密码或真实个人资料。
    $roles = @(
        @('STUDENT', '学生'), @('TEACHER', '教师'), @('SUPER_ADMIN', '超级管理员'),
        @('STUDENT_ADMIN', '学籍管理员'), @('ACADEMIC_ADMIN', '教务管理员'),
        @('LIBRARY_ADMIN', '图书管理员'), @('SHOP_ADMIN', '商店管理员'),
        @('BANK_ADMIN', '银行管理员'), @('FORUM_ADMIN', '论坛管理员')
    )
    foreach ($role in $roles) {
        Invoke-Sql $connection "INSERT INTO [roles] ([role_code], [role_name]) VALUES ($(Quote-Text $role[0]), $(Quote-Text $role[1]))"
    }

    $sections = @(
        @('CAMPUS', '校园生活', '校园见闻与生活交流', 10),
        @('STUDY', '学习广角', '课程、竞赛与学习经验', 20),
        @('ACTIVITY', '场馆运动', '社团、活动与运动', 30),
        @('CAREER', '生涯发展', '实习、就业与成长', 40),
        @('MARKET', '交换认领', '闲置交换与失物招领', 50)
    )
    foreach ($section in $sections) {
        Invoke-Sql $connection "INSERT INTO [forum_sections] ([code], [name], [description], [sort_order], [enabled]) VALUES ($(Quote-Text $section[0]), $(Quote-Text $section[1]), $(Quote-Text $section[2]), $($section[3]), -1)"
    }

    Invoke-Sql $connection "INSERT INTO [departments] ([department_code], [department_name], [enabled]) VALUES ('01', '计算机科学与工程学院', -1)"
    $computerDepartmentId = Get-Scalar $connection 'SELECT @@IDENTITY'
    Invoke-Sql $connection "INSERT INTO [departments] ([department_code], [department_name], [enabled]) VALUES ('02', '经济管理学院', -1)"
    $businessDepartmentId = Get-Scalar $connection 'SELECT @@IDENTITY'

    Invoke-Sql $connection "INSERT INTO [majors] ([department_id], [major_code], [major_name], [enabled]) VALUES ($computerDepartmentId, '0101', '计算机科学与技术', -1)"
    $computerMajorId = Get-Scalar $connection 'SELECT @@IDENTITY'
    Invoke-Sql $connection "INSERT INTO [majors] ([department_id], [major_code], [major_name], [enabled]) VALUES ($computerDepartmentId, '0102', '软件工程', -1)"
    $softwareMajorId = Get-Scalar $connection 'SELECT @@IDENTITY'
    Invoke-Sql $connection "INSERT INTO [majors] ([department_id], [major_code], [major_name], [enabled]) VALUES ($businessDepartmentId, '0201', '工商管理', -1)"
    $businessMajorId = Get-Scalar $connection 'SELECT @@IDENTITY'

    $classRows = @(
        @($computerMajorId, '2026010101', '计算机科学与技术2026级1班'),
        @($softwareMajorId, '2026010201', '软件工程2026级1班'),
        @($businessMajorId, '2026020101', '工商管理2026级1班')
    )
    foreach ($classRow in $classRows) {
        Invoke-Sql $connection "INSERT INTO [administrative_classes] ([major_id], [class_code], [class_name], [enrollment_year], [enabled]) VALUES ($($classRow[0]), $(Quote-Text $classRow[1]), $(Quote-Text $classRow[2]), 2026, -1)"
    }

    Invoke-Sql $connection ("INSERT INTO [academic_terms] " +
        "([academic_year], [term_number], [term_name], [start_date], [end_date], [selection_start], [selection_end], [drop_deadline], [status]) VALUES " +
        "('2026-2027', 1, '2026-2027学年第一学期', DateSerial(2026,9,1), DateSerial(2027,1,15), DateSerial(2026,8,20), DateSerial(2026,9,10), DateSerial(2026,9,20), 'SELECTION')")
    for ($period = 1; $period -le 12; $period++) {
        Invoke-Sql $connection "INSERT INTO [class_period_times] ([period_number]) VALUES ($period)"
    }

    $courses = @(
        @('C000001', 'Java程序设计', '3.0', 48, 'Java语言基础、面向对象、Swing与网络编程'),
        @('C000002', '数据库系统原理', '3.0', 48, '关系数据库、SQL、事务与数据库设计'),
        @('C000003', '高等数学', '5.0', 80, '一元函数微积分与常微分方程')
    )
    foreach ($course in $courses) {
        Invoke-Sql $connection "INSERT INTO [courses] ([course_code], [course_name], [credits], [total_hours], [description], [enabled]) VALUES ($(Quote-Text $course[0]), $(Quote-Text $course[1]), $($course[2]), $($course[3]), $(Quote-Text $course[4]), -1)"
    }

    foreach ($sequence in @(@('BOOK_CATALOG', 7), @('COPY_BARCODE', 111), @('LIBRARY_FEE_BILL', 1))) {
        Invoke-Sql $connection "INSERT INTO [library_code_sequences] ([code_type], [next_value]) VALUES ($(Quote-Text $sequence[0]), $($sequence[1]))"
    }
    Invoke-Sql $connection 'INSERT INTO [library_fee_policy] ([id], [grace_days], [daily_overdue_fee], [overdue_cap], [minor_damage_rate], [major_damage_rate], [lost_rate]) VALUES (1, 1, 0.50, 20.00, 0.2000, 0.5000, 1.0000)'

    $books = @(
        @('BK000000001', '9780134685991', 'Effective Java', 'Joshua Bloch', 'Addison-Wesley', 2018, '计算机', 'Java API 设计与工程实践。', '89.00'),
        @('BK000000002', '9780132350884', 'Clean Code', 'Robert C. Martin', 'Prentice Hall', 2008, '计算机', '可读、可维护代码的实践原则。', '68.00'),
        @('BK000000003', '9780201633610', 'Design Patterns', 'Erich Gamma 等', 'Addison-Wesley', 1994, '计算机', '经典面向对象设计模式。', '89.00'),
        @('BK000000004', '9780141439518', 'Pride and Prejudice', 'Jane Austen', 'Penguin Classics', 2003, '文学', '匿名化课程演示用文学馆藏。', '49.00'),
        @('BK000000005', '9780061120084', 'To Kill a Mockingbird', 'Harper Lee', 'Harper Perennial', 2006, '文学', '匿名化课程演示用文学馆藏。', '49.00'),
        @('BK000000006', '9780060555665', 'The Intelligent Investor', 'Benjamin Graham', 'Harper Business', 2006, '经济', '价值投资基础读物。', '68.00')
    )
    $bookIds = @{}
    foreach ($book in $books) {
        Invoke-Sql $connection ("INSERT INTO [books] ([catalog_code], [isbn], [title], [authors], [publisher], [publish_year], [category], [description], [replacement_price], [enabled]) VALUES (" +
            "$(Quote-Text $book[0]), $(Quote-Text $book[1]), $(Quote-Text $book[2]), $(Quote-Text $book[3]), $(Quote-Text $book[4]), $($book[5]), $(Quote-Text $book[6]), $(Quote-Text $book[7]), $($book[8]), -1)")
        $bookIds[$book[1]] = Get-Scalar $connection 'SELECT @@IDENTITY'
    }
    $copies = @(
        @('9780134685991', 'B000000101', 'A-01-01'), @('9780134685991', 'B000000102', 'A-01-02'),
        @('9780132350884', 'B000000103', 'A-02-01'), @('9780132350884', 'B000000104', 'A-02-02'),
        @('9780201633610', 'B000000105', 'A-03-01'), @('9780141439518', 'B000000106', 'B-01-01'),
        @('9780141439518', 'B000000107', 'B-01-02'), @('9780061120084', 'B000000108', 'B-02-01'),
        @('9780060555665', 'B000000109', 'C-01-01'), @('9780060555665', 'B000000110', 'C-01-02')
    )
    foreach ($copy in $copies) {
        Invoke-Sql $connection "INSERT INTO [book_copies] ([book_id], [barcode], [shelf_location], [status]) VALUES ($($bookIds[$copy[0]]), $(Quote-Text $copy[1]), $(Quote-Text $copy[2]), 'AVAILABLE')"
    }

    Invoke-Sql $connection 'CREATE TABLE [VCampus_Submission_Info] ([item] TEXT(80) NOT NULL, [content] LONGTEXT NOT NULL)'
    $infoRows = @(
        @('文件用途', '课程提交用 Access 数据库快照，可直接用 Microsoft Access 打开。'),
        @('运行架构', 'VCampus 正式运行仍使用 MySQL -> 应用服务器 -> 客户端三层架构；本文件不替代运行库。'),
        @('数据来源', 'database/schema.sql 与匿名化的 database/seed.sql 基础演示数据。'),
        @('安全说明', '不包含数据库密码、账号密码、令牌或真实个人资料。'),
        @('表数量', "$($tableBlocks.Count) 张项目业务表，另含 2 张交付说明/转换日志表。"),
        @('索引转换', "成功创建 $createdIndexes / $($indexes.Count) 个索引。"),
        @('外键转换', "成功创建 $createdForeignKeys / $($foreignKeys.Count) 个外键关系。"),
        @('生成时间', (Get-Date).ToString('yyyy-MM-dd HH:mm:ss zzz')),
        @('生成工具', "scripts/export-access.ps1；数据库引擎 $provider。")
    )
    foreach ($info in $infoRows) {
        Invoke-Sql $connection "INSERT INTO [VCampus_Submission_Info] ([item], [content]) VALUES ($(Quote-Text $info[0]), $(Quote-Text $info[1]))"
    }

    Invoke-Sql $connection 'CREATE TABLE [VCampus_Conversion_Log] ([object_type] TEXT(40), [object_name] TEXT(180), [status] TEXT(40), [detail] LONGTEXT)'
    foreach ($entry in $conversionLog) {
        Invoke-Sql $connection "INSERT INTO [VCampus_Conversion_Log] ([object_type], [object_name], [status], [detail]) VALUES ($(Quote-Text $entry.Type), $(Quote-Text $entry.Name), $(Quote-Text $entry.Status), $(Quote-Text $entry.Detail))"
    }

    $verifiedTableCount = 0
    foreach ($table in $tableBlocks) {
        [void](Get-Scalar $connection "SELECT Count(*) FROM $(Quote-Name $table.Name)")
        $verifiedTableCount++
    }
    if ($verifiedTableCount -ne $tableBlocks.Count) {
        throw "验证失败：预期 $($tableBlocks.Count) 张业务表，实际验证 $verifiedTableCount 张。"
    }
    foreach ($requiredTable in @('roles', 'departments', 'majors', 'administrative_classes', 'academic_terms', 'courses', 'books', 'book_copies')) {
        $count = [int](Get-Scalar $connection "SELECT Count(*) FROM $(Quote-Name $requiredTable)")
        if ($count -le 0) { throw "验证失败：$requiredTable 没有演示数据。" }
    }
}
finally {
    if ($connection) {
        if ($connection.State -ne 0) { $connection.Close() }
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($connection)
        $connection = $null
    }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}

Start-Sleep -Milliseconds 250
if (Test-Path -LiteralPath $resolvedOutput) {
    Remove-Item -LiteralPath $resolvedOutput -Force
}
Move-Item -LiteralPath $temporaryOutput -Destination $resolvedOutput

$result = Get-Item -LiteralPath $resolvedOutput
Write-Host "Access 数据库已生成：$($result.FullName)"
Write-Host "业务表：$($tableBlocks.Count)；文件大小：$($result.Length) 字节；引擎：$provider"
