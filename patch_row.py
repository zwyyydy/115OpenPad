# -*- coding: utf-8 -*-
"""一次性补丁：控制排改为 播放/暂停|时间|画质|音轨|字幕|倍速，单击切换其显隐"""
p = 'F:/ad/115pad/app/src/main/java/com/open115/pad/player/PlayerActivity.kt'
src = open(p, encoding='utf-8').read()

# ---- 2. 定位旧控制排 if 块并整块替换 ----
start_marker = '            // 右下角控制排：画质 | 字幕（交互后显示 4 秒自动淡出；控制器弹出时隐藏）\n'
i = src.index(start_marker)
depth = 0
j = i
while True:
    line_end = src.index('\n', j)
    line = src[j:line_end]
    depth += line.count('{') + line.count('(') - line.count('}') - line.count(')')
    j = line_end + 1
    if depth <= 0:
        break
old_block = src[i:j]

new_block = '''            // 右下角控制排：播放/暂停 | 时间 | 画质 | 音轨 | 字幕 | 倍速（4 秒无操作自动淡出）
            if (controlRowVisible) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                        }) {
                            Icon(
                                if (player.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                "播放/暂停",
                                tint = Color.White,
                            )
                        }
                        Text(
                            timeText,
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        // 画质
                        if ((data?.videoUrls?.size ?: 0) > 1) {
                            Box {
                                TextButton(onClick = { qualityMenuOpen = true }) {
                                    Text(
                                        labelOf(currentDef),
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                DropdownMenu(expanded = qualityMenuOpen, onDismissRequest = { qualityMenuOpen = false }) {
                                    data?.videoUrls?.forEach { entry ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    labelOf(entry.definition) +
                                                        if (entry.definition == currentDef) " ✓" else "",
                                                )
                                            },
                                            onClick = {
                                                qualityMenuOpen = false
                                                switchQuality(entry.definition)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        // 音轨
                        if ((data?.multitrackList?.size ?: 0) > 1) {
                            Box {
                                TextButton(onClick = { audioMenuOpen = true }) {
                                    Text(
                                        "音轨",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                DropdownMenu(expanded = audioMenuOpen, onDismissRequest = { audioMenuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("默认音轨" + if (currentAudio == -1) " ✓" else "") },
                                        onClick = {
                                            audioMenuOpen = false
                                            switchAudio(-1)
                                        },
                                    )
                                    data?.multitrackList?.forEachIndexed { i, title ->
                                        DropdownMenuItem(
                                            text = { Text("音轨${i + 1}：$title" + if (currentAudio == i) " ✓" else "") },
                                            onClick = {
                                                audioMenuOpen = false
                                                switchAudio(i)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        // 字幕：搜索在线字幕 / 显示开关
                        Box {
                            IconButton(onClick = { subsMenuOpen = true }) {
                                Icon(
                                    if (subsEnabled) Icons.Outlined.Subtitles else Icons.Outlined.SubtitlesOff,
                                    "字幕",
                                    tint = Color.White,
                                )
                            }
                            DropdownMenu(expanded = subsMenuOpen, onDismissRequest = { subsMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("搜索在线字幕…") },
                                    onClick = {
                                        subsMenuOpen = false
                                        onSubSearch()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (subsEnabled) "显示字幕 ✓" else "显示字幕（关）") },
                                    onClick = {
                                        subsMenuOpen = false
                                        setSubsEnabled(!subsEnabled)
                                    },
                                )
                            }
                        }
                        // 倍速
                        Box {
                            TextButton(onClick = { speedMenuOpen = true }) {
                                Text(
                                    "倍速" + if (currentSpeed == 1f) "" else " x$currentSpeed",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text("x$s" + if (currentSpeed == s) " ✓" else "") },
                                        onClick = {
                                            speedMenuOpen = false
                                            setSpeed(s)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
'''
src = src[:i] + new_block + src[j:]

# ---- 3. 单击 = 切换我们的控制排 ----
old_tap = (
    "                onToggleController = {\n"
    "                    if (controllerVisible) hideControllerNow() else showController()\n"
    "                },")
new_tap = (
    "                onToggleController = {\n"
    "                    // 自带控制条已停用：单击切换右下角控制排的显隐\n"
    "                    if (controlRowVisible) {\n"
    "                        controlRowVisible = false\n"
    "                        controlRowUntil = 0L\n"
    "                    } else {\n"
    "                        pulseControlRow()\n"
    "                    }\n"
    "                },")
assert old_tap in src, 'tap'
src = src.replace(old_tap, new_tap)

# ---- 4. 手势层恒启用 ----
src = src.replace(
    '                enabled = !controllerVisible,\n',
    '                enabled = true,\n', 1)

open(p, 'w', encoding='utf-8').write(src)
print('2-4 ok')
