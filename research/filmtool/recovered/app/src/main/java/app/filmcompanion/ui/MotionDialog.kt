package app.filmcompanion.ui



import androidx.compose.runtime.Composable

import androidx.compose.runtime.SideEffect

import androidx.compose.ui.platform.LocalView

import androidx.compose.ui.window.DialogProperties

import androidx.compose.ui.window.DialogWindowProvider

import app.filmcompanion.R



/** Window animations also run when a close button removes the dialog from composition. */

@Composable

internal fun MotionDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(),

    content: @Composable () -> Unit) {

    val reduced = LocalCompanionSettings.current.reducedMotion

    androidx.compose.ui.window.Dialog(onDismissRequest, properties = properties) {

        val view = LocalView.current

        SideEffect {

            (view.parent as? DialogWindowProvider)?.window?.setWindowAnimations(

                if (reduced) 0 else R.style.FilmDialogMotion)

        }

        content()

    }

}
