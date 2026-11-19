package com.vidlab.app.ui.catalog

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.remote.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun UnifiedTrackingCard(onSimklToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val SurfaceDark = Color(0xFF0D1015)
    val BorderPurple = Color(0xFF222E46)
    val AccentViolet = Color(0xFF244985)

    val simklManager = remember { SimklManager(context) }
    var simklSignedIn by remember { mutableStateOf(simklManager.isSignedIn) }
    var simklUser by remember { mutableStateOf(simklManager.username ?: "Simkl Member") }
    var simklScrobble by remember { mutableStateOf(simklManager.scrobbleEnabled) }
    var simklPin by remember { mutableStateOf<SimklPinSession?>(null) }
    var simklBusy by remember { mutableStateOf(false) }

    val traktManager = remember { TraktManager(context) }
    var traktSignedIn by remember { mutableStateOf(traktManager.isSignedIn) }
    var traktUser by remember { mutableStateOf(traktManager.username ?: "Trakt Member") }
    var traktScrobble by remember { mutableStateOf(traktManager.scrobbleEnabled) }
    var traktCid by remember { mutableStateOf(traktManager.clientId) }
    var traktSec by remember { mutableStateOf(traktManager.clientSecret) }
    var showTraktSetup by remember { mutableStateOf(false) }
    var traktPin by remember { mutableStateOf<TraktDeviceSession?>(null) }
    var traktBusy by remember { mutableStateOf(false) }

    val aniManager = remember { AniListManager(context) }
    var aniSignedIn by remember { mutableStateOf(aniManager.isSignedIn) }
    var aniUser by remember { mutableStateOf(aniManager.username ?: "AniList Member") }
    var aniScrobble by remember { mutableStateOf(aniManager.scrobbleEnabled) }
    var aniCid by remember { mutableStateOf(aniManager.clientId) }
    var aniToken by remember { mutableStateOf("") }
    var showAniSetup by remember { mutableStateOf(false) }
    var aniVerifying by remember { mutableStateOf(false) }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                aniSignedIn = aniManager.isSignedIn
                aniUser = aniManager.username ?: "AniList Member"
                traktSignedIn = traktManager.isSignedIn
                traktUser = traktManager.username ?: "Trakt Member"
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(traktPin) {
        val active = traktPin ?: return@LaunchedEffect
        var waitSec = active.interval.coerceAtLeast(5).toLong()
        val deadline = System.currentTimeMillis() + (active.expiresIn.coerceAtLeast(120) * 1000L)
        while (traktPin != null && System.currentTimeMillis() < deadline) {
            delay(waitSec * 1000L)
            when (val res = traktManager.pollDeviceToken(active)) {
                is TraktPollState.Authorized -> {
                    traktSignedIn = true
                    traktUser = traktManager.username ?: "Trakt Member"
                    traktPin = null
                    showTraktSetup = false
                    Toast.makeText(context, "Connected to Trakt as $traktUser", Toast.LENGTH_SHORT).show()
                    break
                }
                is TraktPollState.Pending -> if (res.slowDown) waitSec += 5L
                is TraktPollState.Expired -> {
                    traktPin = null
                    Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                    break
                }
                is TraktPollState.Error -> {
                    traktPin = null
                    Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
                    break
                }
            }
        }
    }

    LaunchedEffect(simklPin) {
        val active = simklPin ?: return@LaunchedEffect
        var waitSec = active.interval.coerceAtLeast(4).toLong()
        val deadline = System.currentTimeMillis() + (active.expiresIn.coerceAtLeast(120) * 1000L)
        while (simklPin != null && System.currentTimeMillis() < deadline) {
            delay(waitSec * 1000L)
            if (simklPin == null) break
            when (val st = simklManager.pollPinStatus(active)) {
                is SimklPollState.Authorized -> {
                    simklSignedIn = true
                    simklUser = simklManager.username ?: "Simkl Member"
                    simklScrobble = simklManager.scrobbleEnabled
                    simklPin = null
                    onSimklToggle(true)
                    Toast.makeText(context, "Connected to Simkl as $simklUser", Toast.LENGTH_SHORT).show()
                    break
                }
                is SimklPollState.Pending -> if (st.slowDown) waitSec += 2L
                is SimklPollState.Expired, is SimklPollState.Error -> { simklPin = null; break }
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Simkl", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (simklSignedIn) { Spacer(modifier = Modifier.width(6.dp)); Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp)) }
                    }
                    Text(if (simklSignedIn) "Connected • $simklUser" else "Movies, TV & Anime", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                if (!simklSignedIn) {
                    Button(
                        onClick = {
                            simklBusy = true
                            scope.launch {
                                val res = simklManager.requestPinCode()
                                simklBusy = false
                                res.onSuccess {
                                    simklPin = it
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.verificationUrl))) }
                                }.onFailure { Toast.makeText(context, it.message ?: "Simkl error", Toast.LENGTH_SHORT).show() }
                            }
                        },
                        enabled = !simklBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) { Text(if (simklBusy) "..." else "Connect", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                } else {
                    Switch(
                        checked = simklScrobble,
                        onCheckedChange = { simklScrobble = it; simklManager.scrobbleEnabled = it; onSimklToggle(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF3B6CB7),
                            uncheckedThumbColor = Color(0xFF8E9199),
                            uncheckedTrackColor = Color(0xFF1F2737),
                            uncheckedBorderColor = Color(0xFF2D3A52)
                        )
                    )
                }
            }
            if (simklSignedIn) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        onClick = { simklManager.signOut(); simklSignedIn = false; simklPin = null; onSimklToggle(false) },
                        color = Color(0xFF161D2B),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.8.dp, BorderPurple)
                    ) {
                        Text("Sign Out", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            if (simklPin != null && !simklSignedIn) {
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BorderPurple).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Code: ${simklPin!!.userCode}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Cancel", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.clickable { simklPin = null })
                }
            }

            HorizontalDivider(color = BorderPurple, thickness = 0.8.dp)

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Trakt.tv", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (traktSignedIn) { Spacer(modifier = Modifier.width(6.dp)); Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp)) }
                    }
                    Text(if (traktSignedIn) "Connected • $traktUser" else "Movies & TV Shows", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                if (!traktSignedIn) {
                    Button(
                        onClick = {
                            if (traktBusy) return@Button
                            traktBusy = true
                            scope.launch {
                                val res = traktManager.requestDeviceCode()
                                traktBusy = false
                                res.onSuccess { session ->
                                    traktPin = session
                                    runCatching {
                                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Trakt Code", session.userCode))
                                        Toast.makeText(context, "Code ${session.userCode} copied — paste on Trakt", Toast.LENGTH_LONG).show()
                                    }
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(session.verificationUrl)))
                                    }
                                }.onFailure {
                                    Toast.makeText(context, it.message ?: "Failed to start Trakt login", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !traktBusy,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) { Text(if (traktBusy) "..." else "Connect", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = traktScrobble, onCheckedChange = { traktScrobble = it; traktManager.scrobbleEnabled = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF3B6CB7)))
                        Text("Sign Out", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.clickable { traktManager.signOut(); traktSignedIn = false })
                    }
                }
            }
            if (traktPin != null && !traktSignedIn) {
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BorderPurple).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Code: ${traktPin!!.userCode}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Cancel", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.clickable { traktPin = null })
                }
            }
            if (showTraktSetup && !traktSignedIn) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Redirect URI: vidlab://auth/trakt", color = Color(0xFF94A3B8), fontSize = 10.sp)
                    OutlinedTextField(value = traktCid, onValueChange = { traktCid = it; traktManager.clientId = it }, label = { Text("Trakt Client ID", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = traktSec, onValueChange = { traktSec = it; traktManager.clientSecret = it }, label = { Text("Trakt Client Secret", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(traktManager.buildAuthorizeUrl()))) } },
                        enabled = traktCid.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(34.dp)
                    ) { Text("Authorize in Browser", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }

            HorizontalDivider(color = BorderPurple, thickness = 0.8.dp)

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AniList", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (aniSignedIn) { Spacer(modifier = Modifier.width(6.dp)); Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp)) }
                    }
                    Text(if (aniSignedIn) "Connected • $aniUser" else "Anime episode progress", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                if (!aniSignedIn) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(aniManager.buildAuthorizeUrl())))
                            }.onFailure {
                                showAniSetup = !showAniSetup
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) { Text("Connect", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = aniScrobble, onCheckedChange = { aniScrobble = it; aniManager.scrobbleEnabled = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF3B6CB7)))
                        Text("Sign Out", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.clickable { aniManager.signOut(); aniSignedIn = false })
                    }
                }
            }
            if (showAniSetup && !aniSignedIn) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = aniCid, onValueChange = { aniCid = it; aniManager.clientId = it }, label = { Text("AniList Client ID", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(aniManager.buildAuthorizeUrl()))) } },
                            border = BorderStroke(1.dp, AccentViolet),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(48.dp)
                        ) { Icon(Icons.Default.OpenInNew, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp)) }
                    }
                    OutlinedTextField(value = aniToken, onValueChange = { aniToken = it }, label = { Text("Paste AniList Access Token", fontSize = 10.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            aniVerifying = true
                            scope.launch {
                                val res = aniManager.verifyAndSaveToken(aniToken)
                                aniVerifying = false
                                res.onSuccess { name ->
                                    aniSignedIn = true; aniUser = name; aniToken = ""; showAniSetup = false
                                    Toast.makeText(context, "Connected to AniList as $name", Toast.LENGTH_SHORT).show()
                                }.onFailure { Toast.makeText(context, it.message ?: "Invalid token", Toast.LENGTH_SHORT).show() }
                            }
                        },
                        enabled = !aniVerifying && aniToken.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(34.dp)
                    ) { Text(if (aniVerifying) "Verifying..." else "Save & Connect AniList", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
