package cash.z.ecc.android.sdk

import android.content.Context
import cash.z.ecc.android.sdk.Synchronizer.Status.DISCONNECTED
import cash.z.ecc.android.sdk.Synchronizer.Status.DOWNLOADING
import cash.z.ecc.android.sdk.Synchronizer.Status.ENHANCING
import cash.z.ecc.android.sdk.Synchronizer.Status.SCANNING
import cash.z.ecc.android.sdk.Synchronizer.Status.STOPPED
import cash.z.ecc.android.sdk.Synchronizer.Status.SYNCED
import cash.z.ecc.android.sdk.Synchronizer.Status.VALIDATING
import cash.z.ecc.android.sdk.block.CompactBlockProcessor
import cash.z.ecc.android.sdk.block.CompactBlockProcessor.State.Disconnected
import cash.z.ecc.android.sdk.block.CompactBlockProcessor.State.Downloading
import cash.z.ecc.android.sdk.block.CompactBlockProcessor.State.Enhancing
import cash.z.ecc.android.sdk.block.CompactBlockProcessor.State.Initialized
import cash.z.ecc.android.sdk.block.CompactBlockProcessor.State.Scanned
import cash.z.ecc.android.sdk.block.CompactBlockProcessor.State.Scanning
import cash.z.ecc.android.sdk.block.CompactBlockProcessor.State.Stopped
import cash.z.ecc.android.sdk.block.CompactBlockProcessor.State.Validating
import cash.z.ecc.android.sdk.exception.SynchronizerException
import cash.z.ecc.android.sdk.ext.ConsensusBranchId
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.internal.SaplingParamTool
import cash.z.ecc.android.sdk.internal.block.CompactBlockDownloader
import cash.z.ecc.android.sdk.internal.db.DatabaseCoordinator
import cash.z.ecc.android.sdk.internal.db.block.DbCompactBlockRepository
import cash.z.ecc.android.sdk.internal.db.derived.DbDerivedDataRepository
import cash.z.ecc.android.sdk.internal.db.derived.DerivedDataDb
import cash.z.ecc.android.sdk.internal.ext.toHexReversed
import cash.z.ecc.android.sdk.internal.ext.tryNull
import cash.z.ecc.android.sdk.internal.isEmpty
import cash.z.ecc.android.sdk.internal.model.Checkpoint
import cash.z.ecc.android.sdk.internal.repository.CompactBlockRepository
import cash.z.ecc.android.sdk.internal.repository.DerivedDataRepository
import cash.z.ecc.android.sdk.internal.service.LightWalletGrpcService
import cash.z.ecc.android.sdk.internal.service.LightWalletService
import cash.z.ecc.android.sdk.internal.transaction.OutboundTransactionManager
import cash.z.ecc.android.sdk.internal.transaction.PersistentTransactionManager
import cash.z.ecc.android.sdk.internal.transaction.TransactionEncoder
import cash.z.ecc.android.sdk.internal.transaction.WalletTransactionEncoder
import cash.z.ecc.android.sdk.internal.twig
import cash.z.ecc.android.sdk.internal.twigTask
import cash.z.ecc.android.sdk.jni.RustBackend
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.LightWalletEndpoint
import cash.z.ecc.android.sdk.model.PendingTransaction
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionRecipient
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.isExpired
import cash.z.ecc.android.sdk.model.isLongExpired
import cash.z.ecc.android.sdk.model.isMarkedForDeletion
import cash.z.ecc.android.sdk.model.isMined
import cash.z.ecc.android.sdk.model.isSafeToDiscard
import cash.z.ecc.android.sdk.model.isSubmitSuccess
import cash.z.ecc.android.sdk.type.AddressType
import cash.z.ecc.android.sdk.type.AddressType.Shielded
import cash.z.ecc.android.sdk.type.AddressType.Transparent
import cash.z.ecc.android.sdk.type.AddressType.Unified
import cash.z.ecc.android.sdk.type.ConsensusMatchType
import cash.z.ecc.android.sdk.type.UnifiedFullViewingKey
import cash.z.wallet.sdk.rpc.Service
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A Synchronizer that attempts to remain operational, despite any number of errors that can occur.
 * It acts as the glue that ties all the pieces of the SDK together. Each component of the SDK is
 * designed for the potential of stand-alone usage but coordinating all the interactions is non-
 * trivial. So the Synchronizer facilitates this, acting as reference that demonstrates how all the
 * pieces can be tied together. Its goal is to allow a developer to focus on their app rather than
 * the nuances of how Zcash works.
 *
 * @property storage exposes flows of wallet transaction information.
 * @property txManager manages and tracks outbound transactions.
 * @property processor saves the downloaded compact blocks to the cache and then scans those blocks for
 * data related to this wallet.
 */
@FlowPreview
@Suppress("TooManyFunctions")
class SdkSynchronizer internal constructor(
    private val storage: DerivedDataRepository,
    private val txManager: OutboundTransactionManager,
    val processor: CompactBlockProcessor,
    private val rustBackend: RustBackend
) : Synchronizer {

    // pools
    private val _orchardBalances = MutableStateFlow<WalletBalance?>(null)
    private val _saplingBalances = MutableStateFlow<WalletBalance?>(null)
    private val _transparentBalances = MutableStateFlow<WalletBalance?>(null)

    private val _status = MutableStateFlow<Synchronizer.Status>(DISCONNECTED)

    /**
     * The lifespan of this Synchronizer. This scope is initialized once the Synchronizer starts
     * because it will be a child of the parentScope that gets passed into the [start] function.
     * Everything launched by this Synchronizer will be cancelled once the Synchronizer or its
     * parentScope stops. This coordinates with [isStarted] so that it fails early
     * rather than silently, whenever the scope is used before the Synchronizer has been started.
     */
    var coroutineScope: CoroutineScope = CoroutineScope(EmptyCoroutineContext)
        get() {
            if (!isStarted) {
                throw SynchronizerException.NotYetStarted
            } else {
                return field
            }
        }
        set(value) {
            field = value
            if (value.coroutineContext !is EmptyCoroutineContext) isStarted = true
        }

    /**
     * The channel that this Synchronizer uses to communicate with lightwalletd. In most cases, this
     * should not be needed or used. Instead, APIs should be added to the synchronizer to
     * enable the desired behavior. In the rare case, such as testing, it can be helpful to share
     * the underlying channel to connect to the same service, and use other APIs
     * (such as darksidewalletd) because channels are heavyweight.
     */
    val channel: ManagedChannel get() = (processor.downloader.lightWalletService as LightWalletGrpcService).channel

    override var isStarted = false

    //
    // Balances
    //

    override val orchardBalances = _orchardBalances.asStateFlow()
    override val saplingBalances = _saplingBalances.asStateFlow()
    override val transparentBalances = _transparentBalances.asStateFlow()

    //
    // Transactions
    //

    override val clearedTransactions get() = storage.allTransactions
    override val pendingTransactions = txManager.getAll()
    override val sentTransactions get() = storage.sentTransactions
    override val receivedTransactions get() = storage.receivedTransactions

    //
    // Status
    //

    override val network: ZcashNetwork get() = processor.network

    /**
     * Indicates the status of this Synchronizer. This implementation basically simplifies the
     * status of the processor to focus only on the high level states that matter most. Whenever the
     * processor is finished scanning, the synchronizer updates transaction and balance info and
     * then emits a [SYNCED] status.
     */
    override val status = _status.asStateFlow()

    /**
     * Indicates the download progress of the Synchronizer. When progress reaches 100, that
     * signals that the Synchronizer is in sync with the network. Balances should be considered
     * inaccurate and outbound transactions should be prevented until this sync is complete. It is
     * a simplified version of [processorInfo].
     */
    override val progress: Flow<Int> = processor.progress

    /**
     * Indicates the latest information about the blocks that have been processed by the SDK. This
     * is very helpful for conveying detailed progress and status to the user.
     */
    override val processorInfo: Flow<CompactBlockProcessor.ProcessorInfo> = processor.processorInfo

    /**
     * The latest height seen on the network while processing blocks. This may differ from the
     * latest height scanned and is useful for determining block confirmations and expiration.
     */
    override val networkHeight: StateFlow<BlockHeight?> = processor.networkHeight

    //
    // Error Handling
    //

    /**
     * A callback to invoke whenever an uncaught error is encountered. By definition, the return
     * value of the function is ignored because this error is unrecoverable. The only reason the
     * function has a return value is so that all error handlers work with the same signature which
     * allows one function to handle all errors in simple apps. This callback is not called on the
     * main thread so any UI work would need to switch context to the main thread.
     */
    override var onCriticalErrorHandler: ((Throwable?) -> Boolean)? = null

    /**
     * A callback to invoke whenever a processor error is encountered. Returning true signals that
     * the error was handled and a retry attempt should be made, if possible. This callback is not
     * called on the main thread so any UI work would need to switch context to the main thread.
     */
    override var onProcessorErrorHandler: ((Throwable?) -> Boolean)? = null

    /**
     * A callback to invoke whenever a server error is encountered while submitting a transaction to
     * lightwalletd. Returning true signals that the error was handled and a retry attempt should be
     * made, if possible. This callback is not called on the main thread so any UI work would need
     * to switch context to the main thread.
     */
    override var onSubmissionErrorHandler: ((Throwable?) -> Boolean)? = null

    /**
     * A callback to invoke whenever a processor is not setup correctly. Returning true signals that
     * the invalid setup should be ignored. If no handler is set, then any setup error will result
     * in a critical error. This callback is not called on the main thread so any UI work would need
     * to switch context to the main thread.
     */
    override var onSetupErrorHandler: ((Throwable?) -> Boolean)? = null

    /**
     * A callback to invoke whenever a chain error is encountered. These occur whenever the
     * processor detects a missing or non-chain-sequential block (i.e. a reorg).
     */
    override var onChainErrorHandler: ((errorHeight: BlockHeight, rewindHeight: BlockHeight) -> Any)? = null

    //
    // Public API
    //

    /**
     * Convenience function for the latest height. Specifically, this value represents the last
     * height that the synchronizer has observed from the lightwalletd server. Instead of using
     * this, a wallet will more likely want to consume the flow of processor info using
     * [processorInfo].
     */
    override val latestHeight
        get() = processor.currentInfo.networkBlockHeight

    override val latestBirthdayHeight
        get() = processor.birthdayHeight

    override suspend fun prepare(): Synchronizer = apply {
        // Do nothing; this could likely be removed
    }

    /**
     * Starts this synchronizer within the given scope. For simplicity, attempting to start an
     * instance that has already been started will throw a [SynchronizerException.FalseStart]
     * exception. This reduces the complexity of managing resources that must be recycled. Instead,
     * each synchronizer is designed to have a long lifespan and should be started from an activity,
     * application or session.
     *
     * @param parentScope the scope to use for this synchronizer, typically something with a
     * lifecycle such as an Activity for single-activity apps or a logged in user session. This
     * scope is only used for launching this synchronizer's job as a child. If no scope is provided,
     * then this synchronizer and all of its coroutines will run until stop is called, which is not
     * recommended since it can leak resources. That type of behavior is more useful for tests.
     *
     * @return an instance of this class so that this function can be used fluidly.
     */
    override fun start(parentScope: CoroutineScope?): Synchronizer {
        if (isStarted) throw SynchronizerException.FalseStart
        // base this scope on the parent so that when the parent's job cancels, everything here
        // cancels as well also use a supervisor job so that one failure doesn't bring down the
        // whole synchronizer
        val supervisorJob = SupervisorJob(parentScope?.coroutineContext?.get(Job))
        CoroutineScope(supervisorJob + Dispatchers.Main).let { scope ->
            coroutineScope = scope
            scope.onReady()
        }
        return this
    }

    /**
     * Stop this synchronizer and all of its child jobs. Once a synchronizer has been stopped it
     * should not be restarted and attempting to do so will result in an error. Also, this function
     * will throw an exception if the synchronizer was never previously started.
     */
    override fun stop() {
        coroutineScope.launch {
            // log everything to help troubleshoot shutdowns that aren't graceful
            twig("Synchronizer::stop: STARTING")
            twig("Synchronizer::stop: processor.stop()")
            processor.stop()
            twig("Synchronizer::stop: coroutineScope.cancel()")
            coroutineScope.cancel()
            twig("Synchronizer::stop: _status.cancel()")
            _status.value = STOPPED
            twig("Synchronizer::stop: COMPLETE")
        }
    }

    /**
     * Convenience function that exposes the underlying server information, like its name and
     * consensus branch id. Most wallets should already have a different source of truth for the
     * server(s) with which they operate.
     */
    override suspend fun getServerInfo(): Service.LightdInfo = processor.downloader.getServerInfo()

    override suspend fun getNearestRewindHeight(height: BlockHeight): BlockHeight =
        processor.getNearestRewindHeight(height)

    override suspend fun rewindToNearestHeight(height: BlockHeight, alsoClearBlockCache: Boolean) {
        processor.rewindToNearestHeight(height, alsoClearBlockCache)
    }

    override suspend fun quickRewind() {
        processor.quickRewind()
    }

    override fun getMemos(transactionOverview: TransactionOverview): Flow<String> {
        return when (transactionOverview.isSentTransaction) {
            true -> {
                val sentNoteIds = storage.getSentNoteIds(transactionOverview.id)

                sentNoteIds.map { rustBackend.getSentMemoAsUtf8(it) }.filterNotNull()
            }
            false -> {
                val receivedNoteIds = storage.getReceivedNoteIds(transactionOverview.id)

                receivedNoteIds.map { rustBackend.getReceivedMemoAsUtf8(it) }.filterNotNull()
            }
        }
    }

    override fun getRecipients(transactionOverview: TransactionOverview): Flow<TransactionRecipient> {
        require(transactionOverview.isSentTransaction) { "Recipients can only be queried for sent transactions" }

        return storage.getRecipients(transactionOverview.id)
    }

    //
    // Storage APIs
    //

    // TODO [#682]: turn this section into the data access API. For now, just aggregate all the things that we want
    //  to do with the underlying data
    // TODO [#682]: https://github.com/zcash/zcash-android-wallet-sdk/issues/682

    suspend fun findBlockHash(height: BlockHeight): ByteArray? {
        return storage.findBlockHash(height)
    }

    suspend fun findBlockHashAsHex(height: BlockHeight): String? {
        return findBlockHash(height)?.toHexReversed()
    }

    suspend fun getTransactionCount(): Int {
        return storage.getTransactionCount().toInt()
    }

    fun refreshTransactions() {
        storage.invalidate()
    }

    //
    // Private API
    //

    suspend fun refreshUtxos() {
        twig("refreshing utxos", -1)
        refreshUtxos(getLegacyTransparentAddress())
    }

    /**
     * Calculate the latest balance, based on the blocks that have been scanned and transmit this
     * information into the flow of [balances].
     */
    suspend fun refreshAllBalances() {
        refreshSaplingBalance()
        refreshTransparentBalance()
        // TODO [#682]: refresh orchard balance
        // TODO [#682]: https://github.com/zcash/zcash-android-wallet-sdk/issues/682
        twig("Warning: Orchard balance does not yet refresh. Only some of the plumbing is in place.")
    }

    suspend fun refreshSaplingBalance() {
        twig("refreshing sapling balance")
        _saplingBalances.value = processor.getBalanceInfo(Account.DEFAULT)
    }

    suspend fun refreshTransparentBalance() {
        twig("refreshing transparent balance")
        _transparentBalances.value = processor.getUtxoCacheBalance(getLegacyTransparentAddress())
    }

    suspend fun isValidAddress(address: String): Boolean {
        return !validateAddress(address).isNotValid
    }

    private fun CoroutineScope.onReady() = launch(CoroutineExceptionHandler(::onCriticalError)) {
        twig("Preparing to start...")
        prepare()

        twig("Synchronizer (${this@SdkSynchronizer}) Ready. Starting processor!")
        var lastScanTime = 0L
        processor.onProcessorErrorListener = ::onProcessorError
        processor.onSetupErrorListener = ::onSetupError
        processor.onChainErrorListener = ::onChainError
        processor.state.onEach {
            when (it) {
                is Scanned -> {
                    val now = System.currentTimeMillis()
                    // do a bit of housekeeping and then report synced status
                    onScanComplete(it.scannedRange, now - lastScanTime)
                    lastScanTime = now
                    SYNCED
                }
                is Stopped -> STOPPED
                is Disconnected -> DISCONNECTED
                is Downloading, Initialized -> DOWNLOADING
                is Validating -> VALIDATING
                is Scanning -> SCANNING
                is Enhancing -> ENHANCING
            }.let { synchronizerStatus ->
                //  ignore enhancing status for now
                // TODO [#682]: clean this up and handle enhancing gracefully
                // TODO [#682]: https://github.com/zcash/zcash-android-wallet-sdk/issues/682
                if (synchronizerStatus != ENHANCING) _status.value = synchronizerStatus
            }
        }.launchIn(this)
        processor.start()
        twig("Synchronizer onReady complete. Processor start has exited!")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onCriticalError(unused: CoroutineContext?, error: Throwable) {
        twig("********")
        twig("********  ERROR: $error")
        twig(error)
        if (error.cause != null) twig("******** caused by ${error.cause}")
        if (error.cause?.cause != null) twig("******** caused by ${error.cause?.cause}")
        twig("********")

        if (onCriticalErrorHandler == null) {
            twig(
                "WARNING: a critical error occurred but no callback is registered to be notified " +
                    "of critical errors! THIS IS PROBABLY A MISTAKE. To respond to these " +
                    "errors (perhaps to update the UI or alert the user) set " +
                    "synchronizer.onCriticalErrorHandler to a non-null value."
            )
        }

        onCriticalErrorHandler?.invoke(error)
    }

    private fun onProcessorError(error: Throwable): Boolean {
        twig("ERROR while processing data: $error")
        if (onProcessorErrorHandler == null) {
            twig(
                "WARNING: falling back to the default behavior for processor errors. To add" +
                    " custom behavior, set synchronizer.onProcessorErrorHandler to" +
                    " a non-null value"
            )
            return true
        }
        return onProcessorErrorHandler?.invoke(error)?.also {
            twig(
                "processor error handler signaled that we should " +
                    "${if (it) "try again" else "abort"}!"
            )
        } == true
    }

    private fun onSetupError(error: Throwable): Boolean {
        if (onSetupErrorHandler == null) {
            twig(
                "WARNING: falling back to the default behavior for setup errors. To add custom" +
                    " behavior, set synchronizer.onSetupErrorHandler to a non-null value"
            )
            return false
        }
        return onSetupErrorHandler?.invoke(error) == true
    }

    private fun onChainError(errorHeight: BlockHeight, rewindHeight: BlockHeight) {
        twig("Chain error detected at height: $errorHeight. Rewinding to: $rewindHeight")
        if (onChainErrorHandler == null) {
            twig(
                "WARNING: a chain error occurred but no callback is registered to be notified of " +
                    "chain errors. To respond to these errors (perhaps to update the UI or alert the" +
                    " user) set synchronizer.onChainErrorHandler to a non-null value"
            )
        }
        onChainErrorHandler?.invoke(errorHeight, rewindHeight)
    }

    /**
     * @param elapsedMillis the amount of time that passed since the last scan
     */
    private suspend fun onScanComplete(scannedRange: ClosedRange<BlockHeight>?, elapsedMillis: Long) {
        // We don't need to update anything if there have been no blocks
        // refresh anyway if:
        // - if it's the first time we finished scanning
        // - if we check for blocks 5 times and find nothing was mined
        @Suppress("MagicNumber")
        val shouldRefresh = !scannedRange.isEmpty() || elapsedMillis > (ZcashSdk.POLL_INTERVAL * 5)
        val reason = if (scannedRange.isEmpty()) "it's been a while" else "new blocks were scanned"

        // TRICKY:
        // Keep an eye on this section because there is a potential for concurrent DB
        // modification. A change in transactions means a change in balance. Calculating the
        // balance requires touching transactions. If both are done in separate threads, the
        // database can have issues. On Android, this would manifest as a false positive for a
        // "malformed database" exception when the database is not actually corrupt but rather
        // locked (i.e. it's a bad error message).
        // The balance refresh is done first because it is coroutine-based and will fully
        // complete by the time the function returns.
        // Ultimately, refreshing the transactions just invalidates views of data that
        // already exists and it completes on another thread so it should come after the
        // balance refresh is complete.
        if (shouldRefresh) {
            twigTask("Triggering utxo refresh since $reason!", -1) {
                refreshUtxos()
            }
            twigTask("Triggering balance refresh since $reason!", -1) {
                refreshAllBalances()
            }
            twigTask("Triggering pending transaction refresh since $reason!", -1) {
                refreshPendingTransactions()
            }
            twigTask("Triggering transaction refresh since $reason!") {
                refreshTransactions()
            }
        }
    }

    @Suppress("LongMethod", "ComplexMethod")
    private suspend fun refreshPendingTransactions() {
        twig("[cleanup] beginning to refresh and clean up pending transactions")
        // TODO [#682]: this would be the place to clear out any stale pending transactions. Remove filter logic and
        //  then delete any pending transaction with sufficient confirmations (all in one db transaction).
        // TODO [#682]: https://github.com/zcash/zcash-android-wallet-sdk/issues/682
        val allPendingTxs = txManager.getAll().first()
        val lastScannedHeight = storage.lastScannedHeight()

        allPendingTxs.filter { it.isSubmitSuccess() && !it.isMined() }
            .forEach { pendingTx ->
                twig("checking for updates on pendingTx id: ${pendingTx.id}")
                pendingTx.rawTransactionId?.let { rawId ->
                    storage.findMinedHeight(rawId.byteArray)?.let { minedHeight ->
                        twig(
                            "found matching transaction for pending transaction with id" +
                                " ${pendingTx.id} mined at height $minedHeight!"
                        )
                        txManager.applyMinedHeight(pendingTx, minedHeight)
                    }
                }
            }

        twig("[cleanup] beginning to cleanup expired transactions", -1)
        // Experimental: cleanup expired transactions
        // note: don't delete the pendingTx until the related data has been scrubbed, or else you
        // lose the thing that identifies the other data as invalid
        // so we first mark the data for deletion, during the previous "cleanup" step, by removing
        // the thing that we're trying to preserve to signal we no longer need it
        // sometimes apps crash or things go wrong and we get an orphaned pendingTx that we'll poll
        // forever, so maybe just get rid of all of them after a long while
        allPendingTxs.filter {
            (
                it.isExpired(
                    lastScannedHeight,
                    network.saplingActivationHeight
                ) && it.isMarkedForDeletion()
                ) ||
                it.isLongExpired(
                    lastScannedHeight,
                    network.saplingActivationHeight
                ) || it.isSafeToDiscard()
        }.forEach {
            val result = txManager.abort(it)
            twig(
                "[cleanup] FOUND EXPIRED pendingTX (lastScanHeight: $lastScannedHeight " +
                    " expiryHeight: ${it.expiryHeight}): and ${it.id} " +
                    "${if (result > 0) "successfully removed" else "failed to remove"} it"
            )
        }

        twig("[cleanup] done refreshing and cleaning up pending transactions", -1)
    }

    //
    // Account management
    //

    // Not ready to be a public API; internal for testing only
    internal suspend fun createAccount(seed: ByteArray): UnifiedSpendingKey =
        processor.createAccount(seed)

    //
    // Send / Receive
    //

    override suspend fun cancelSpend(pendingId: Long) = txManager.cancel(pendingId)

    /**
     * Returns the current Unified Address for this account.
     */
    override suspend fun getCurrentAddress(account: Account): String =
        processor.getCurrentAddress(account)

    /**
     * Returns the legacy Sapling address corresponding to the current Unified Address for this account.
     */
    override suspend fun getLegacySaplingAddress(account: Account): String =
        processor.getLegacySaplingAddress(account)

    /**
     * Returns the legacy transparent address corresponding to the current Unified Address for this account.
     */
    override suspend fun getLegacyTransparentAddress(account: Account): String =
        processor.getTransparentAddress(account)

    override suspend fun sendToAddress(
        usk: UnifiedSpendingKey,
        amount: Zatoshi,
        toAddress: String,
        memo: String
    ): Flow<PendingTransaction> {
        // Emit the placeholder transaction, then switch to monitoring the database
        val placeHolderTx = txManager.initSpend(amount, TransactionRecipient.Address(toAddress), memo, usk.account)

        txManager.encode(usk, placeHolderTx).let { encodedTx ->
            txManager.submit(encodedTx)
        }

        return txManager.monitorById(placeHolderTx.id)
    }

    override suspend fun shieldFunds(
        usk: UnifiedSpendingKey,
        memo: String
    ): Flow<PendingTransaction> {
        twig("Initializing shielding transaction")
        val tAddr = processor.getTransparentAddress(usk.account)
        val tBalance = processor.getUtxoCacheBalance(tAddr)

        // Emit the placeholder transaction, then switch to monitoring the database
        val placeHolderTx = txManager.initSpend(
            tBalance.available,
            TransactionRecipient.Account(usk.account),
            memo,
            usk.account
        )
        val encodedTx = txManager.encode("", usk, placeHolderTx)
        txManager.submit(encodedTx)

        return txManager.monitorById(placeHolderTx.id)
    }

    override suspend fun refreshUtxos(tAddr: String, since: BlockHeight): Int? {
        return processor.refreshUtxos(tAddr, since)
    }

    override suspend fun getTransparentBalance(tAddr: String): WalletBalance {
        return processor.getUtxoCacheBalance(tAddr)
    }

    override suspend fun isValidShieldedAddr(address: String) =
        txManager.isValidShieldedAddress(address)

    override suspend fun isValidTransparentAddr(address: String) =
        txManager.isValidTransparentAddress(address)

    override suspend fun isValidUnifiedAddr(address: String) =
        txManager.isValidUnifiedAddress(address)

    override suspend fun validateAddress(address: String): AddressType {
        @Suppress("TooGenericExceptionCaught")
        return try {
            if (isValidShieldedAddr(address)) {
                Shielded
            } else if (isValidTransparentAddr(address)) {
                Transparent
            } else if (isValidUnifiedAddr(address)) {
                Unified
            } else {
                AddressType.Invalid("Not a Zcash address")
            }
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            AddressType.Invalid(error.message ?: "Invalid")
        }
    }

    override suspend fun validateConsensusBranch(): ConsensusMatchType {
        val serverBranchId = tryNull { processor.downloader.getServerInfo().consensusBranchId }
        val sdkBranchId = tryNull {
            (txManager as PersistentTransactionManager).encoder.getConsensusBranchId()
        }
        return ConsensusMatchType(
            sdkBranchId?.let { ConsensusBranchId.fromId(it) },
            serverBranchId?.let { ConsensusBranchId.fromHex(it) }
        )
    }

    interface Erasable {
        /**
         * Erase content related to this SDK.
         *
         * @param appContext the application context.
         * @param network the network corresponding to the data being erased. Data is segmented by
         * network in order to prevent contamination.
         * @param alias identifier for SDK content. It is possible for multiple synchronizers to
         * exist with different aliases.
         *
         * @return true when content was found for the given alias. False otherwise.
         */
        suspend fun erase(
            appContext: Context,
            network: ZcashNetwork,
            alias: String = ZcashSdk.DEFAULT_ALIAS
        ): Boolean
    }
}

/**
 * Provides a way of constructing a synchronizer where dependencies are injected in.
 *
 * See the helper methods for generating default values.
 */
internal object DefaultSynchronizerFactory {

    fun new(
        repository: DerivedDataRepository,
        txManager: OutboundTransactionManager,
        processor: CompactBlockProcessor,
        rustBackend: RustBackend
    ): Synchronizer {
        return SdkSynchronizer(
            repository,
            txManager,
            processor,
            rustBackend
        )
    }

    internal suspend fun defaultRustBackend(
        context: Context,
        network: ZcashNetwork,
        alias: String,
        blockHeight: BlockHeight,
        saplingParamTool: SaplingParamTool
    ): RustBackend {
        val coordinator = DatabaseCoordinator.getInstance(context)

        return RustBackend.init(
            coordinator.cacheDbFile(network, alias),
            coordinator.dataDbFile(network, alias),
            saplingParamTool.properties.paramsDirectory,
            network,
            blockHeight
        )
    }

    @Suppress("LongParameterList")
    internal suspend fun defaultDerivedDataRepository(
        context: Context,
        rustBackend: RustBackend,
        zcashNetwork: ZcashNetwork,
        checkpoint: Checkpoint,
        seed: ByteArray?,
        viewingKeys: List<UnifiedFullViewingKey>
    ): DerivedDataRepository =
        DbDerivedDataRepository(DerivedDataDb.new(context, rustBackend, zcashNetwork, checkpoint, seed, viewingKeys))

    internal fun defaultCompactBlockRepository(context: Context, cacheDbFile: File, zcashNetwork: ZcashNetwork):
        CompactBlockRepository =
        DbCompactBlockRepository.new(
            context,
            zcashNetwork,
            cacheDbFile
        )

    fun defaultService(context: Context, lightWalletEndpoint: LightWalletEndpoint): LightWalletService =
        LightWalletGrpcService.new(context, lightWalletEndpoint)

    internal fun defaultEncoder(
        rustBackend: RustBackend,
        saplingParamTool: SaplingParamTool,
        repository: DerivedDataRepository
    ): TransactionEncoder = WalletTransactionEncoder(rustBackend, saplingParamTool, repository)

    fun defaultDownloader(
        service: LightWalletService,
        blockStore: CompactBlockRepository
    ): CompactBlockDownloader = CompactBlockDownloader(service, blockStore)

    internal suspend fun defaultTxManager(
        context: Context,
        zcashNetwork: ZcashNetwork,
        alias: String,
        encoder: TransactionEncoder,
        service: LightWalletService
    ): OutboundTransactionManager {
        val databaseFile = DatabaseCoordinator.getInstance(context).pendingTransactionsDbFile(
            zcashNetwork,
            alias
        )

        return PersistentTransactionManager.new(
            context,
            zcashNetwork,
            encoder,
            service,
            databaseFile
        )
    }

    internal fun defaultProcessor(
        rustBackend: RustBackend,
        downloader: CompactBlockDownloader,
        repository: DerivedDataRepository
    ): CompactBlockProcessor = CompactBlockProcessor(
        downloader,
        repository,
        rustBackend,
        rustBackend.birthdayHeight
    )
}
