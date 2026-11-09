package com.keeper.vault.vault.platform

import com.keeper.vault.contracts.platform.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Production dispatchers for the vault module. Tests inject deterministic dispatchers. */
class AndroidDispatcherProvider : DispatcherProvider {
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val main: CoroutineDispatcher = Dispatchers.Main
}
