package io.nekohasekai.sfa.mihomo

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Local native selector UI; no remotely hosted dashboard receives the controller secret. */
class MihomoPanelActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(rows) })
        refresh()
    }

    private fun api(path: String, body: JSONObject? = null): JSONObject {
        check(MihomoRuntime.isRunning) { "请先连接一个 Clash 配置，再打开节点管理。" }
        val connection = URL("http://127.0.0.1:${MihomoRuntime.controllerPort}$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Authorization", "Bearer ${MihomoRuntime.secret}")
            if (body != null) {
                connection.requestMethod = "PUT"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) { "节点管理请求失败 (${connection.responseCode})" }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun refresh() {
        rows.removeAllViews()
        rows.addView(TextView(this).apply { text = "Mihomo 节点管理"; textSize = 24f })
        rows.addView(Button(this).apply { text = "刷新"; setOnClickListener { refresh() } })
        rows.addView(Button(this).apply { text = "返回"; setOnClickListener { finish() } })
        scope.launch {
            try {
                val proxies = withContext(Dispatchers.IO) { api("/proxies").getJSONObject("proxies") }
                for (name in proxies.keys().asSequence().sorted()) {
                    val group = proxies.getJSONObject(name)
                    val all = group.optJSONArray("all") ?: continue
                    val choices = Array(all.length()) { all.getString(it) }
                    val current = group.optString("now")
                    rows.addView(Button(this@MihomoPanelActivity).apply {
                        text = "$name\n${group.optString("type")} · $current"
                        isEnabled = group.optString("type") == "Selector"
                        setOnClickListener {
                            AlertDialog.Builder(this@MihomoPanelActivity).setTitle(name)
                                .setSingleChoiceItems(choices, choices.indexOf(current)) { dialog, selected ->
                                    dialog.dismiss()
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                api("/proxies/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20"), JSONObject().put("name", choices[selected]))
                                            }
                                            refresh()
                                        } catch (e: Exception) { showError(e) }
                                    }
                                }.setNegativeButton("取消", null).show()
                        }
                    })
                }
            } catch (e: Exception) { showError(e) }
        }
    }

    private fun showError(e: Exception) {
        rows.addView(TextView(this).apply { text = e.message ?: "无法读取节点" })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
