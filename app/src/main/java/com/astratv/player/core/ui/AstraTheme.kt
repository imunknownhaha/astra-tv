package com.astratv.player.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults

val Background=Color(0xFF0B1015);val Surface=Color(0xFF141C24);val Elevated=Color(0xFF1B2732)
val Accent=Color(0xFF48D6E8);val Ink=Color(0xFFF5F7FA);val Muted=Color(0xFF9CAAB7);val Outline=Color(0xFF293743)
@Composable
fun AstraTheme(content: @Composable ()->Unit) {
    androidx.tv.material3.MaterialTheme(colorScheme=androidx.tv.material3.darkColorScheme(primary=Accent,onPrimary=Background,surface=Surface,onSurface=Ink)) {
        MaterialTheme(colorScheme=darkColorScheme(primary=Accent,onPrimary=Background,background=Background,onBackground=Ink,surface=Surface,onSurface=Ink,onSurfaceVariant=Muted,outline=Outline),content=content)
    }
}
@Composable
fun TvAction(label: String,onClick: ()->Unit,modifier: Modifier=Modifier,selected: Boolean=false,enabled: Boolean=true,content: (@Composable RowScope.()->Unit)?=null) {
    Button(onClick=onClick,modifier=modifier.defaultMinSize(minHeight=48.dp),enabled=enabled,
        colors=ButtonDefaults.colors(containerColor=if(selected) Elevated else Surface,contentColor=Ink,focusedContainerColor=Accent,focusedContentColor=Background),
        shape=ButtonDefaults.shape(shape=RoundedCornerShape(12.dp)),
        contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp)) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides androidx.tv.material3.LocalContentColor.current) {
            if(content!=null) content() else Text(label,color=Color.Unspecified,style=MaterialTheme.typography.labelLarge)
        }
    }
}
