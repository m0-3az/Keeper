package com.keeper.vault.vault.platform

import android.content.Context
import com.keeper.vault.contracts.platform.AppFileStore
import java.io.File

/** App-private directories. The vault and intruder trees are siblings under `filesDir`. */
class ContextAppFileStore(context: Context) : AppFileStore {
    private val appContext = context.applicationContext

    override fun vaultDirectory(): File = File(appContext.filesDir, "vault")

    override fun intruderDirectory(): File = File(appContext.filesDir, "intruder")
}
