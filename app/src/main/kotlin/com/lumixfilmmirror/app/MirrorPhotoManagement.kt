package com.lumixfilmmirror.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumixfilmmirror.model.PhotoId
import com.lumixfilmmirror.design.LocalFilmMaterials

@Composable
internal fun PhotoManagementActions(state:WorkspaceState,workspace:WorkspaceViewModel) {
    var managing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<PhotoId>()) }
    LaunchedEffect(state.photos.map { it.id }) {
        selected=selected.intersect(state.photos.map { it.id }.toSet())
        if(state.photos.isEmpty())managing=false
    }
    if(state.photos.isEmpty())return
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        MirrorTextAction("移除当前照片",{state.selected?.let { workspace.removePhotos(setOf(it)) }},enabled=!state.busy)
        MirrorTextAction("批量管理 · ${state.photos.size}",{selected=emptySet(); managing=true},enabled=!state.busy)
    }
    if(managing)MirrorDialog("管理待处理照片",onDismiss={managing=false}) {
        Text("从当前批次移除，手机相册的原文件和已导出的照片保留。",fontSize=12.sp,color=LocalFilmMaterials.current.secondaryInk)
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            MirrorTextAction("全选",{selected=state.photos.map { it.id }.toSet()})
            MirrorTextAction("取消选择",{selected=emptySet()})
        }
        LazyColumn(Modifier.heightIn(max=340.dp)) {
            items(state.photos,key={it.id.value}) { photo ->
                Row(Modifier.fillMaxWidth().clickable(enabled=!state.busy) {
                    selected=if(photo.id in selected)selected-photo.id else selected+photo.id
                },verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(photo.id in selected,onCheckedChange={checked -> selected=if(checked)selected+photo.id else selected-photo.id},enabled=!state.busy)
                    Text(photo.name,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
        }
        MirrorButton(enabled=selected.isNotEmpty() && !state.busy,onClick={workspace.removePhotos(selected);managing=false}) {
            Text("移除选中照片 · ${selected.size}")
        }
    }
}
