package org.kushare.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import org.kushare.app.PhoneData
import org.kushare.app.R

/**
 * Portapapeles compartido con el PC: interruptores, enviar el de aquí y lo
 * último que llegó del PC o se mandó (solo en memoria, no se guarda).
 */
@Composable
fun ClipboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val log by PhoneData.clipboardLog.collectAsState()
    var notify by remember { mutableStateOf(PhoneData.clipboardNotify(context)) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val time = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_clipboard), style = MaterialTheme.typography.displaySmallEmphasized) },
                subtitle = { Text(stringResource(R.string.clipboard_subtitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ClipboardCard() }
            item {
                ExpressiveCard(shape = KushareShapes.ListCard) {
                    SwitchRow(
                        title = stringResource(R.string.clipboard_notify),
                        subtitle = stringResource(R.string.clipboard_notify_detail),
                        checked = notify,
                        onCheckedChange = {
                            notify = it
                            PhoneData.setClipboardNotify(context, it)
                        },
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.clipboard_recent)) }
            if (log.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.clipboard_recent_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(log) { entry ->
                ExpressiveCard(shape = KushareShapes.ListCard) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth().clickable {
                                PhoneData.copyLocally(context, entry.text)
                                Toast.makeText(context, R.string.clipboard_copied_here, Toast.LENGTH_SHORT).show()
                            },
                    ) {
                        Icon(
                            if (entry.fromPc) Icons.Rounded.Computer else Icons.Rounded.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(if (entry.fromPc) R.string.clipboard_from_pc else R.string.clipboard_to_pc) +
                                    " · " + time.format(Date(entry.timeMs)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(entry.text, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
