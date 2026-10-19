package com.kronos.offlinechat.inference

import android.app.*
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.*
import java.net.*
import kotlinx.coroutines.*

/** Foreground, single-instance, HTTP-Range-resumable model transfer. */
class ModelDownloadService : Service() {
  companion object {
    const val UPDATE = "com.kronos.offlinechat.DOWNLOAD_UPDATE"
    fun start(context: Context, m: OfficialGemmaModel) = ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java).putExtra("id", m.id).putExtra("url", m.downloadUrl).putExtra("name", m.fileName))
  }
  private val scope = CoroutineScope(Dispatchers.IO); private var job: Job? = null
  override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
    if (job?.isActive == true) return START_REDELIVER_INTENT
    val id = i?.getStringExtra("id") ?: return START_NOT_STICKY; val url = i.getStringExtra("url") ?: return START_NOT_STICKY; val name = i.getStringExtra("name") ?: return START_NOT_STICKY
    startForeground(41, note(name, 0, 0)); job = scope.launch { transfer(id, url, name); stopSelf(startId) }; return START_REDELIVER_INTENT
  }
  private fun transfer(id: String, url: String, name: String) = runCatching {
    val dir = File(filesDir, "models").apply { mkdirs() }; val part = File(dir, "$name.part"); val final = File(dir, name); var at = part.length()
    var c = (URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = true; connectTimeout=20_000; readTimeout=60_000; if(at>0)setRequestProperty("Range","bytes=$at-"); connect() }
    if(c.responseCode == 200 && at>0) { part.delete(); at=0; c.disconnect(); c=(URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects=true; connectTimeout=20_000; readTimeout=60_000; connect() } }
    check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }; val total=c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: c.contentLengthLong.let { if(it>0)at+it else 0 }
    BufferedInputStream(c.inputStream,262144).use { input -> FileOutputStream(part,at>0).use { out -> val b=ByteArray(262144); var n: Int; var done=at; var last=0L; while(input.read(b).also{n=it}>=0){out.write(b,0,n);done+=n;val now=android.os.SystemClock.elapsedRealtime();if(now-last>500){publish(id,done,total,"downloading");startForeground(41,note(name,done,total));last=now}};publish(id,done,total,"downloading");check(total<=0||done==total){"Incomplete download"} } }
    if(final.exists())final.delete();check(part.renameTo(final)){"Could not finalize model"};publish(id,final.length(),final.length(),"ready")
  }.onFailure { publish(id, File(filesDir,"models/$name.part").length(),0,"error:${it.message}") }
  private fun publish(id:String,b:Long,t:Long,s:String){getSharedPreferences("downloads",MODE_PRIVATE).edit().putLong("$id.b",b).putLong("$id.t",t).putString("$id.s",s).apply();sendBroadcast(Intent(UPDATE).setPackage(packageName))}
  private fun note(name:String,b:Long,t:Long):Notification{getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("models","Model downloads",NotificationManager.IMPORTANCE_LOW));return NotificationCompat.Builder(this,"models").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Local Gemma Chat").setContentText("Downloading $name").setOnlyAlertOnce(true).setOngoing(true).setProgress(t.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),b.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),t<=0).build()}
  override fun onBind(i:Intent?):IBinder?=null
  override fun onDestroy(){scope.cancel();super.onDestroy()}
}
