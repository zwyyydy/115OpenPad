package com.open115.pad.ui.login

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.open115.pad.AppContainer
import com.open115.pad.util.Pkce
import com.open115.pad.util.qrBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LoginViewModel(private val container: AppContainer) : ViewModel() {

    data class QrState(val bitmap: Bitmap, val uid: String, val hint: String)

    val clientId = MutableStateFlow("")
    val qr = MutableStateFlow<QrState?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val importError = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(false)

    private var authJob: Job? = null
    private var attempts = 0

    init {
        viewModelScope.launch { clientId.value = container.session.currentClientId() }
    }

    fun saveClientId(v: String) {
        viewModelScope.launch {
            container.session.saveClientId(v)
            clientId.value = v.trim()
        }
    }

    /** 手动导入第三方工具（如 oplist.org）获取的令牌 */
    fun importTokens(access: String, refresh: String) {
        val a = access.trim()
        if (a.isBlank()) {
            importError.value = "请填写 Access Token"
            return
        }
        viewModelScope.launch {
            importing.value = true
            importError.value = null
            try {
                container.session.saveTokens(
                    access = a,
                    refresh = refresh.trim(),
                    // 有 refresh_token 时官方刷新接口续期（不依赖 client_id）；
                    // 没有时按 30 天占位，实际到期后需重新导入
                    expiresIn = if (refresh.isBlank()) 30L * 24 * 3600 else 7200,
                )
            } catch (e: Exception) {
                importError.value = "保存失败：${e.message}"
            } finally {
                importing.value = false
            }
        }
    }

    fun startAuth() {
        authJob?.cancel()
        val cid = clientId.value
        if (cid.isBlank()) {
            error.value = "请先填写 AppID"
            return
        }
        attempts = 0
        authJob = viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                val verifier = Pkce.newVerifier()
                val resp = container.authApi.authDeviceCode(cid, Pkce.challenge(verifier))
                val d = resp.data
                if (!resp.ok || d?.uid == null || d.qrcode == null) {
                    error.value = resp.message ?: "获取设备码失败，请检查 AppID 是否正确（错误码 ${resp.code}）"
                    return@launch
                }
                qr.value = QrState(qrBitmap(d.qrcode!!), d.uid, "请用 115 官方 App 扫码授权")
                while (isActive) {
                    val st = container.qrApi.qrStatus(d.uid, d.time.toString(), d.sign ?: "")
                    when (st.data?.status) {
                        1 -> qr.value = qr.value?.copy(hint = "已扫码，请在手机上确认")
                        2 -> {
                            val tr = container.authApi.deviceCodeToToken(d.uid, verifier)
                            val t = tr.data
                            if (!tr.ok || t?.access_token.isNullOrBlank()) {
                                error.value = tr.message ?: "换取令牌失败（错误码 ${tr.code}）"
                                return@launch
                            }
                            container.session.saveTokens(
                                access = t!!.access_token!!,
                                refresh = t.refresh_token ?: "",
                                expiresIn = t.expires_in,
                            )
                            return@launch
                        }
                        else -> {
                            attempts++
                            if (attempts > 60) {
                                error.value = "授权超时，请重新生成二维码"
                                qr.value = null
                                return@launch
                            }
                        }
                    }
                    delay(1200)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = "网络错误：${e.message}"
            } finally {
                busy.value = false
            }
        }
    }

    fun cancelAuth() {
        authJob?.cancel()
        qr.value = null
        busy.value = false
    }
}

@Composable
fun LoginScreen(container: AppContainer) {
    val vm: LoginViewModel = viewModel(initializer = { LoginViewModel(container) })
    var tab by rememberSaveable { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 被强制登出的原因（终态授权码触发时由 Session 设置）
        val logoutNotice by container.session.logoutNotice.collectAsState()
        logoutNotice?.let { reason ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("115 OpenPad", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "非官方 115 网盘客户端 · 为平板与大屏优化",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("扫码授权") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("导入 Token") })
        }
        Spacer(Modifier.height(20.dp))

        when (tab) {
            0 -> QrAuthPane(vm)
            else -> ImportTokenPane(vm)
        }
    }
}

/** 标签页一：AppID + PKCE 扫码授权（原有流程） */
@Composable
private fun QrAuthPane(vm: LoginViewModel) {
    val clientId by vm.clientId.collectAsState()
    val qrState by vm.qr.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    var editingClientId by remember { mutableStateOf<String?>(null) }
    val uri = LocalUriHandler.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (clientId.isBlank()) {
            ClientIdForm(initial = "", onSave = { vm.saveClientId(it) })
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { uri.openUri("https://open.115.com/") }) {
                Text("还没有 AppID？前往 115 开放平台申请")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "不想申请 AppID？切换到「导入 Token」标签页",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val state = qrState
                    if (state != null) {
                        Image(
                            bitmap = state.bitmap.asImageBitmap(),
                            contentDescription = "授权二维码",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(240.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(state.hint, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Box(
                            Modifier.size(240.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (busy) CircularProgressIndicator() else Text("点击下方按钮开始授权")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { if (busy) vm.cancelAuth() else vm.startAuth() }) {
                            Text(if (busy) "取消" else "扫码授权登录")
                        }
                        OutlinedButton(onClick = { editingClientId = clientId }) {
                            Text("修改 AppID")
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "AppID：$clientId",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }

    editingClientId?.let { current ->
        AlertDialog(
            onDismissRequest = { editingClientId = null },
            title = { Text("修改 AppID") },
            text = { ClientIdForm(initial = current, onSave = { vm.saveClientId(it); editingClientId = null }) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editingClientId = null }) { Text("关闭") } },
        )
    }
}

/** 标签页二：手动粘贴第三方工具获取的令牌（如 OpenList Token / oplist.org） */
@Composable
private fun ImportTokenPane(vm: LoginViewModel) {
    val importError by vm.importError.collectAsState()
    val importing by vm.importing.collectAsState()
    val uri = LocalUriHandler.current
    var access by rememberSaveable { mutableStateOf("") }
    var refresh by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("从令牌工具导入", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "适用没有 AppID 的情况：用 OpenList 等工具的公共参数扫码，" +
                        "把拿到的令牌粘贴到这里。建议同时填写 Refresh Token（官方刷新接口不依赖 AppID，可长期自动续期）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = access,
                    onValueChange = { access = it },
                    label = { Text("Access Token（必填）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = refresh,
                    onValueChange = { refresh = it },
                    label = { Text("Refresh Token（选填，用于自动续期）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { vm.importTokens(access, refresh) },
                    enabled = access.isNotBlank() && !importing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (importing) "正在导入…" else "导入并登录")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { uri.openUri("https://api.oplist.org/") }) {
                        Text("打开 OpenList Token")
                    }
                    TextButton(onClick = {
                        access = ""
                        refresh = ""
                    }) {
                        Text("清空")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "令牌仅保存在本机应用数据中，不会上传给任何第三方。\n" +
                "注意：请仅导入你本人账号的令牌，导入他人令牌属于违规使用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        importError?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ClientIdForm(initial: String, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("AppID (client_id)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { if (value.isNotBlank()) onSave(value) },
            enabled = value.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("保存")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "需要在 115 开放平台完成开发者入驻并创建应用后获得 AppID。" +
                "本应用使用 OAuth2 + PKCE 设备码模式授权，无需 AppSecret。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
