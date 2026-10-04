package top.nkbe.npatch.ui.component

import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.viewinterop.AndroidView
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

/** Parse GitHub release Markdown into native text spans; no WebView or remote assets needed. */
@Composable
internal fun ReleaseNotesMarkdown(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = COUITheme.colorScheme
    val foreground = colors.onSurface.toArgb()
    val accent = colors.primary.toArgb()
    val codeBackground = colors.primary.copy(alpha = 0.1f).toArgb()
    val border = colors.onSurface.copy(alpha = 0.25f).toArgb()
    val checkmark = colors.onPrimary.toArgb()
    val density = LocalDensity.current.density
    val markwon = remember(context, accent, codeBackground, border, checkmark, density) {
        Markwon.builder(context)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create { theme ->
                theme.tableCellPadding((8 * density).toInt())
                    .tableBorderWidth((1 * density).toInt().coerceAtLeast(1))
                    .tableBorderColor(border)
                    .tableHeaderRowBackgroundColor(codeBackground)
                    .tableOddRowBackgroundColor(android.graphics.Color.TRANSPARENT)
                    .tableEvenRowBackgroundColor(android.graphics.Color.TRANSPARENT)
            })
            .usePlugin(TaskListPlugin.create(accent, accent, checkmark))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(accent)
                        .blockQuoteColor(accent)
                        .codeBackgroundColor(codeBackground)
                }
            })
            .build()
    }
    val rendered = remember(markwon, markdown) { markwon.toMarkdown(markdown) }
    AndroidView(
        factory = { TextView(it).apply {
            textSize = 16f
            includeFontPadding = false
            setLineSpacing(0f, 1.15f)
        } },
        update = { view ->
            view.setTextColor(foreground)
            if (view.tag !== rendered) {
                markwon.setParsedMarkdown(view, rendered)
                view.tag = rendered
            }
        },
        modifier = modifier.fillMaxWidth().semantics { text = AnnotatedString(rendered.toString()) },
    )
}
