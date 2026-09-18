# T1 offline forensics: which physics animation would the new rule select for a model?
#
# Independent of the mod's Java code on purpose - it reads the raw YSM package files with
# nothing but PowerShell 5.1 and re-derives the answer, so the report can compare two
# implementations instead of trusting one. ASCII only: PowerShell 5.1 reads a BOM-less
# script as ANSI, and Chinese comments would be mangled (and could break string literals).
#
# The rule mirrored here (see YsmPhysicsBinding.select):
#   1. candidates, in order: every animation the model's own controllers play, then the
#      model's own 'pre_parallel*'/'parallel*' animations, then every animation YSM's
#      built-in default controllers play;
#   2. a candidate counts only if it looks like physics (a spring call in some bone's
#      rotation, or a name containing physics/_phys/phys_);
#   3. the bones it drives - rotation-channel spring carriers, plus bones driven from one
#      via ysm.bone_rot() - are intersected with the model's simulatable bones (the bone
#      carries cubes, or an ancestor/descendant in the same subtree does);
#   4. a candidate driving nothing the model can move is skipped; the model then falls
#      back to name-based classification.
#
# Usage:
#   .\T1_forensics.ps1 -ConfigRoot "C:\...\config\yes_steve_model" `
#                      -Models "builtin\wine_fox\01_taisho_maid","builtin\misc\3_default_boy"

param(
    [string]$ConfigRoot = "C:\Users\ASUS\AppData\Roaming\.minecraft\versions\EPIC mod test\config\yes_steve_model",
    [string[]]$Models = @(
        "builtin\wine_fox\01_taisho_maid",
        "builtin\wine_fox\22_elf",
        "builtin\wine_fox\03_astronaut",
        "builtin\misc\3_default_boy"
    ),
    [string]$BuiltinPackage = "builtin\misc\4_default_controllers"
)

$ErrorActionPreference = "Stop"

function Read-Json([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    return ([System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8) | ConvertFrom-Json)
}

function Test-PhysicsText([string]$text) {
    if (-not $text) { return $false }
    return ($text -match 'ysm\.second_order\(|ysm\.first_order\(|ysm\.bone_rot\(|ysm\.bone_pos\(')
}

function Test-PhysicsName([string]$name) {
    if (-not $name) { return $false }
    $lower = $name.ToLowerInvariant()
    return ($lower.Contains('physics') -or $lower.Contains('_phys') -or $lower.Contains('phys_'))
}

# Collect the bones an animation drives, per the pass-one/pass-two rules of the Java side.
# Pass two follows ysm.bone_rot('X') links only (the timeline-variable hop the Java side also
# follows is left out: it can only add bones, so this is a lower bound on the part list).
function Get-AnimBones($anim) {
    $spring = New-Object System.Collections.Generic.List[string]
    $links = @{}
    foreach ($bone in $anim.bones.PSObject.Properties) {
        $name = $bone.Name
        $rot = $bone.Value.rotation
        if ($null -ne $rot) {
            $rotText = ($rot | ConvertTo-Json -Depth 12 -Compress)
            if (Test-PhysicsText $rotText) { $spring.Add($name) }
        }
        $allText = ($bone.Value | ConvertTo-Json -Depth 12 -Compress)
        $m = [regex]::Match($allText, "ysm\.bone_rot\(\s*'([^']+)'")
        if ($m.Success) { $links[$name] = $m.Groups[1].Value }
    }
    $accepted = New-Object System.Collections.Generic.HashSet[string]
    foreach ($s in $spring) { [void]$accepted.Add($s) }
    for ($pass = 0; $pass -lt 4; $pass++) {
        $changed = $false
        foreach ($k in @($links.Keys)) {
            if ($accepted.Contains($k)) { continue }
            if ($accepted.Contains($links[$k])) { [void]$accepted.Add($k); $changed = $true }
        }
        if (-not $changed) { break }
    }
    return @{ accepted = $accepted; spring = $spring }
}

# The model's simulatable bones: geometry-bearing bones plus all their ancestors.
function Get-Simulatable($geo) {
    $has = @{}; $parent = @{}
    foreach ($b in $geo.'minecraft:geometry'[0].bones) {
        $c = 0; if ($b.cubes) { $c = @($b.cubes).Count }
        $has[$b.name] = ($c -gt 0)
        $parent[$b.name] = $b.parent
    }
    for ($pass = 0; $pass -lt 600; $pass++) {
        $changed = $false
        foreach ($n in @($has.Keys)) {
            if (-not $has[$n]) { continue }
            $p = $parent[$n]
            if ($p -and $has.ContainsKey($p) -and -not $has[$p]) { $has[$p] = $true; $changed = $true }
        }
        if (-not $changed) { break }
    }
    $out = New-Object System.Collections.Generic.HashSet[string]
    foreach ($n in @($has.Keys)) { if ($has[$n]) { [void]$out.Add($n) } }
    return $out
}

# The animations a set of controllers plays, in declaration order.
function Get-PlayedAll($controllers) {
    $played = New-Object System.Collections.Generic.List[string]
    foreach ($c in $controllers.Values) {
        if (-not $c.states) { continue }
        foreach ($s in $c.states.PSObject.Properties) {
            if (-not $s.Value.animations) { continue }
            foreach ($a in @($s.Value.animations)) {
                $name = $null
                if ($a -is [string]) { $name = $a }
                elseif ($a -is [psobject]) { foreach ($p in $a.PSObject.Properties) { $name = $p.Name; break } }
                if ($name -and -not $played.Contains($name)) { $played.Add($name) }
            }
        }
    }
    return $played
}

function Get-PackageAnims([string]$dir) {
    # Ordered, because the production loader keeps file order and the reported animation name
    # depends on it: a plain hashtable enumerates in an arbitrary order, which would make this
    # script disagree with the Java rule about *which* candidate came first while agreeing about
    # the bones - a difference in the tool, not in the rule.
    $anims = [ordered]@{}
    $manifest = Read-Json (Join-Path $dir "ysm.json")
    if (-not $manifest -or -not $manifest.files -or -not $manifest.files.player) { return $anims }
    $player = $manifest.files.player
    if ($player.animation) {
        foreach ($p in $player.animation.PSObject.Properties) {
            $rel = $p.Value -replace '/', '\'
            $file = Join-Path $dir $rel
            $json = Read-Json $file
            if ($json -and $json.animations) {
                foreach ($a in $json.animations.PSObject.Properties) {
                    if (-not $anims.Contains($a.Name)) { $anims[$a.Name] = $a.Value }
                }
            }
        }
    }
    return $anims
}

function Get-PackageControllers([string]$dir) {
    $controllers = [ordered]@{}
    $manifest = Read-Json (Join-Path $dir "ysm.json")
    if (-not $manifest -or -not $manifest.files -or -not $manifest.files.player) { return $controllers }
    $declared = $manifest.files.player.animation_controllers
    if (-not $declared) { return $controllers }
    foreach ($rel in @($declared)) {
        $json = Read-Json (Join-Path $dir ($rel -replace '/', '\'))
        if ($json -and $json.animation_controllers) {
            foreach ($c in $json.animation_controllers.PSObject.Properties) { $controllers[$c.Name] = $c.Value }
        }
    }
    return $controllers
}

# --- built-in default controller set -------------------------------------------------
$builtinDir = Join-Path $ConfigRoot $BuiltinPackage
$builtinAnims = Get-PackageAnims $builtinDir
$builtinPlayed = Get-PlayedAll (Get-PackageControllers $builtinDir)

Write-Output ("built-in package: " + $BuiltinPackage + "  controllers/anims: " + $builtinPlayed.Count + "/" + $builtinAnims.Count)
Write-Output ""

foreach ($rel in $Models) {
    $dir = Join-Path $ConfigRoot $rel
    $geo = Read-Json (Join-Path $dir "models\main.json")
    if (-not $geo) { Write-Output ("### " + $rel + " : NO GEOMETRY - skipped"); continue }
    $simulatable = Get-Simulatable $geo
    $allBones = New-Object System.Collections.Generic.HashSet[string]
    foreach ($b in $geo.'minecraft:geometry'[0].bones) { [void]$allBones.Add($b.name) }
    $ownAnims = Get-PackageAnims $dir
    $ownPlayed = Get-PlayedAll (Get-PackageControllers $dir)

    # Candidate order, as in YsmPhysicsBinding.candidateAnimations.
    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($n in $ownPlayed) { if (-not $candidates.Contains($n)) { $candidates.Add($n) } }
    foreach ($n in $ownAnims.Keys) { if (($n -like 'pre_parallel*' -or $n -like 'parallel*') -and -not $candidates.Contains($n)) { $candidates.Add($n) } }
    foreach ($n in $builtinPlayed) { if (-not $candidates.Contains($n)) { $candidates.Add($n) } }

    $selected = $null; $selectedParts = New-Object System.Collections.Generic.HashSet[string]
    $declared = 0; $rejectedNote = ""
    $trail = New-Object System.Collections.Generic.List[string]
    foreach ($name in $candidates) {
        $anim = $null
        $fromOwn = $ownAnims.Contains($name)
        if ($fromOwn) { $anim = $ownAnims[$name] } elseif ($builtinAnims.Contains($name)) { $anim = $builtinAnims[$name] }
        if (-not $anim) { continue }
        if (-not ((Test-PhysicsName $name) -or (Test-PhysicsText (($anim | ConvertTo-Json -Depth 15 -Compress))))) { continue }
        # 'own' vs 'builtin' matters for the before/after reading: the old rule could only see the
        # model's own tables, so a candidate marked 'builtin' did not exist for it at all.
        $src = 'own'
        if (-not $fromOwn) { $src = 'builtin' }
        $bones = Get-AnimBones $anim
        $d = $bones.accepted.Count
        $hit = New-Object System.Collections.Generic.List[string]
        $exists = 0
        foreach ($b in $bones.accepted) {
            if ($allBones.Contains($b)) { $exists++ }
            if ($simulatable.Contains($b)) { $hit.Add($b) }
        }
        # 'exists' and 'present' are different questions and the difference is the whole verdict:
        # the runtime skips a part whose name is not in the model's bone table (so an all-absent
        # list falls back to bone names, harmlessly), but a part that exists with no geometry below
        # it becomes an authored list that produces no segment at all - and then physics is off.
        $trail.Add(("{0} [{1}]: drives {2}, exists {3}, present {4}" -f $name, $src, $d, $exists, $hit.Count))
        if ($hit.Count -gt 0) {
            if (-not $selected) { $selected = $name }
            $declared += $d
            foreach ($b in $hit) { [void]$selectedParts.Add($b) }
        } elseif (-not $selected -and -not $rejectedNote) {
            # A refusal only speaks for the model while nothing has been accepted: a candidate
            # refused after the choice was made must not overwrite the chosen one's numbers.
            $declared = $d
            $rejectedNote = ("{0} (drives {1} bone(s), none of which this model has geometry for)" -f $name, $d)
        }
    }

    Write-Output ("### " + $rel)
    Write-Output ("  geometry bones      : " + @($geo.'minecraft:geometry'[0].bones).Count + "  (simulatable " + $simulatable.Count + ")")
    Write-Output ("  own animations      : " + $ownAnims.Count + "   own controllers play: " + $ownPlayed.Count)
    Write-Output ("  candidates walked   : " + $candidates.Count)
    foreach ($t in $trail) { Write-Output ("    - " + $t) }
    if ($selected) {
        Write-Output ("  SELECTED            : " + $selected + "   declared=" + $declared + " matched=" + $selectedParts.Count)
        Write-Output ("  parts               : " + (($selectedParts | Sort-Object) -join ', '))
    } else {
        Write-Output ("  SELECTED            : (none) -> name-based fallback")
        Write-Output ("  refused             : " + $rejectedNote)
    }
    Write-Output ""
}
